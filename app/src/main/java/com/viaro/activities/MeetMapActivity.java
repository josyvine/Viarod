package com.viaro.activities;

import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.vineyard.viaro.app.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.viaro.firebase.FirebaseHelper;
import com.viaro.models.UserLocationModel;
import com.viaro.utils.LocationHelper;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import java.util.ArrayList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;

public class MeetMapActivity extends AppCompatActivity implements SensorEventListener {

    private MapView mMapView;
    private IMapController mController;
    private Marker mMyMarker, mFriendMarker;
    private Polyline mBreadcrumbPolyline;
    private Polyline mFriendBreadcrumbPolyline;
    private final ArrayList<GeoPoint> mFriendPath = new ArrayList<>();

    private TextView tvStatus, tvDistance, tvGuidance;

    private String roomCode, role, myId, friendId;
    private DatabaseReference mRoomRef;
    private ValueEventListener mRoomListener;

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;

    private final ArrayList<GeoPoint> mMyPath = new ArrayList<>();

    private double mMyLastAltitude = 0.0;
    private boolean mHasMyAltitude = false;
    private double mFriendLastAltitude = 0.0;
    private boolean mHasFriendAltitude = false;
    private float mMyLastHeading = 0.0f;

    // Sensor Fusion & Compass fields
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private Sensor mMagnetometer;
    private final float[] mGravity = new float[3];
    private final float[] mGeomagnetic = new float[3];
    private boolean mHasGravity = false;
    private boolean mHasGeomagnetic = false;
    private float mCurrentCompassHeading = 0.0f;

    // Kalman filters for local & remote smoothing
    private final GpsKalmanFilter mMyKalmanFilter = new GpsKalmanFilter();
    private final GpsKalmanFilter mFriendKalmanFilter = new GpsKalmanFilter();

    // Dead reckoning fields
    private final Handler mDeadReckoningHandler = new Handler(Looper.getMainLooper());
    private Runnable mDeadReckoningRunnable;
    private long mLastMyGpsUpdateTimeMs = 0;
    private long mLastFriendGpsUpdateTimeMs = 0;
    
    private float mMySpeedMs = 0.0f;
    private float mMyHeading = 0.0f;
    private float mFriendSpeedMs = 0.0f;
    private float mFriendHeading = 0.0f;

    // Last accepted location for drift filtering
    private Location mLastAcceptedLocation = null;
    private float mLastUploadedHeading = 0.0f;

    // Marker active animators to prevent concurrent conflicts
    private final java.util.Map<Marker, ValueAnimator> mMarkerAnimators = new java.util.HashMap<>();

    public static class GpsKalmanFilter {
        private double lat;
        private double lon;
        private double variance; // error covariance
        private long lastTimeMs;
        private static final double PROCESS_NOISE = 1e-5; // Q: Process noise covariance (meters per second)

        public GpsKalmanFilter() {
            this.variance = -1.0;
            this.lastTimeMs = 0;
        }

        public void reset() {
            this.variance = -1.0;
            this.lastTimeMs = 0;
        }

        public GeoPoint filter(double newLat, double newLon, float accuracyMeters, long timeMs) {
            if (variance < 0) {
                this.lat = newLat;
                this.lon = newLon;
                this.variance = accuracyMeters * accuracyMeters;
                this.lastTimeMs = timeMs;
                return new GeoPoint(newLat, newLon);
            }

            long durationMs = timeMs - lastTimeMs;
            if (durationMs <= 0) durationMs = 1;
            lastTimeMs = timeMs;

            double dtSeconds = durationMs / 1000.0;
            this.variance += dtSeconds * PROCESS_NOISE * PROCESS_NOISE;

            double measurementVariance = accuracyMeters * accuracyMeters;
            double kGain = this.variance / (this.variance + measurementVariance);

            this.lat = this.lat + kGain * (newLat - this.lat);
            this.lon = this.lon + kGain * (newLon - this.lon);

            this.variance = (1.0 - kGain) * this.variance;

            return new GeoPoint(this.lat, this.lon);
        }
    }

    private void snapToRoadAsync(final GeoPoint point, final RoadSnapCallback callback) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                double lon = point.getLongitude();
                double lat = point.getLatitude();
                String urlStr = "https://router.project-osrm.org/nearest/v1/driving/" + lon + "," + lat + "?number=1";
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                
                if (conn.getResponseCode() == 200) {
                    java.io.InputStream in = conn.getInputStream();
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    if ("Ok".equals(json.optString("code"))) {
                        org.json.JSONArray waypoints = json.optJSONArray("waypoints");
                        if (waypoints != null && waypoints.length() > 0) {
                            org.json.JSONObject wp = waypoints.getJSONObject(0);
                            double distance = wp.optDouble("distance", 999.0);
                            if (distance <= 50.0) { // Only snap if within 50 meters
                                org.json.JSONArray locArr = wp.optJSONArray("location");
                                if (locArr != null && locArr.length() == 2) {
                                    double snappedLon = locArr.getDouble(0);
                                    double snappedLat = locArr.getDouble(1);
                                    runOnUiThread(() -> callback.onSnapped(new GeoPoint(snappedLat, snappedLon)));
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Silently fall back to raw point
            }
            runOnUiThread(() -> callback.onSnapped(point));
        });
    }

    public interface RoadSnapCallback {
        void onSnapped(GeoPoint snappedPoint);
    }

    private void animateMarkerSmoothly(final Marker marker, final GeoPoint startPos, final GeoPoint endPos, int duration) {
        if (marker == null) return;
        
        ValueAnimator active = mMarkerAnimators.get(marker);
        if (active != null) {
            active.cancel();
        }
        
        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        animator.setDuration(duration);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            double lat = startPos.getLatitude() + fraction * (endPos.getLatitude() - startPos.getLatitude());
            double lon = startPos.getLongitude() + fraction * (endPos.getLongitude() - startPos.getLongitude());
            GeoPoint intermediate = new GeoPoint(lat, lon);
            marker.setPosition(intermediate);
            mMapView.invalidate();
        });
        mMarkerAnimators.put(marker, animator);
        animator.start();
    }

    private GeoPoint predictNextPosition(GeoPoint current, float speedMs, float bearingDegrees, double dtSeconds) {
        if (speedMs <= 0.1f) return current;
        
        double R = 6371000.0; // Earth radius in meters
        double bearingRad = Math.toRadians(bearingDegrees);
        double latRad = Math.toRadians(current.getLatitude());
        
        double distance = speedMs * dtSeconds;
        double dLat = (distance * Math.cos(bearingRad)) / R;
        double dLon = (distance * Math.sin(bearingRad)) / (R * Math.cos(latRad));
        
        double newLat = current.getLatitude() + Math.toDegrees(dLat);
        double newLon = current.getLongitude() + Math.toDegrees(dLon);
        
        return new GeoPoint(newLat, newLon);
    }

    private void startDeadReckoningTimer() {
        mDeadReckoningRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                
                // Local user prediction
                if (mMyMarker != null && mLastMyGpsUpdateTimeMs > 0) {
                    long elapsed = now - mLastMyGpsUpdateTimeMs;
                    if (elapsed > 1500) { // GPS update is delayed
                        double dt = 0.2; // 200ms step
                        GeoPoint current = mMyMarker.getPosition();
                        GeoPoint predicted = predictNextPosition(current, mMySpeedMs, mMyHeading, dt);
                        animateMarkerSmoothly(mMyMarker, current, predicted, 200);
                    }
                }
                
                // Remote friend user prediction
                if (mFriendMarker != null && mLastFriendGpsUpdateTimeMs > 0) {
                    long elapsed = now - mLastFriendGpsUpdateTimeMs;
                    if (elapsed > 2500) { // Remote update is delayed (usually Firebase has latency)
                        double dt = 0.2; // 200ms step
                        GeoPoint current = mFriendMarker.getPosition();
                        GeoPoint predicted = predictNextPosition(current, mFriendSpeedMs, mFriendHeading, dt);
                        animateMarkerSmoothly(mFriendMarker, current, predicted, 200);
                    }
                }
                
                mDeadReckoningHandler.postDelayed(this, 200);
            }
        };
        mDeadReckoningHandler.postDelayed(mDeadReckoningRunnable, 200);
    }

    private GeoPoint calculateCameraTarget(GeoPoint vehiclePos, float headingDegrees) {
        double offsetDistanceDegrees = 0.00027; // Offset for zoom level ~17
        double headingRad = Math.toRadians(headingDegrees);
        
        double offsetLat = offsetDistanceDegrees * Math.cos(headingRad);
        double offsetLon = offsetDistanceDegrees * Math.sin(headingRad) / Math.cos(Math.toRadians(vehiclePos.getLatitude()));
        
        return new GeoPoint(vehiclePos.getLatitude() + offsetLat, vehiclePos.getLongitude() + offsetLon);
    }

    private BitmapDrawable createLetterMarker(int drawableId, String letter) {
        Drawable drawable = getResources().getDrawable(drawableId, null);
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0) width = 96;
        if (height <= 0) height = 96;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);

        if (drawableId == R.drawable.ic_location) {
            Paint whitePaint = new Paint();
            whitePaint.setColor(Color.WHITE);
            whitePaint.setAntiAlias(true);
            canvas.drawCircle(width * 0.5f, height * 0.375f, width * 0.13f, whitePaint);
        }

        Paint paint = new Paint();
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        float x = width * 0.5f;
        float y;
        if (drawableId == R.drawable.custom_marker) {
            y = height * 0.388f;
            paint.setColor(Color.parseColor("#E11D48"));
            paint.setTextSize(width * 0.28f);
        } else {
            y = height * 0.375f;
            paint.setColor(Color.parseColor("#2563EB"));
            paint.setTextSize(width * 0.24f);
        }

        Rect bounds = new Rect();
        paint.getTextBounds(letter, 0, letter.length(), bounds);
        float textHeight = bounds.height();
        y += textHeight * 0.35f;

        canvas.drawText(letter, x, y, paint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meet_map);

        roomCode = getIntent().getStringExtra("room_code");
        role = getIntent().getStringExtra("role");

        if ("creator".equals(role)) {
            myId = "user_a";
            friendId = "user_b";
        } else {
            myId = "user_b";
            friendId = "user_a";
        }

        mMapView = findViewById(R.id.map_meet);
        mMapView.setTileSource(TileSourceFactory.MAPNIK);
        mMapView.setMultiTouchControls(true);

        mController = mMapView.getController();
        mController.setZoom(16.0);

        tvStatus = findViewById(R.id.tv_meet_status);
        tvDistance = findViewById(R.id.tv_meet_distance);
        tvGuidance = findViewById(R.id.tv_meet_guidance);

        tvStatus.setText("Room Code: " + roomCode + " (Role: " + role + ")");

        findViewById(R.id.btn_end_meet).setOnClickListener(v -> endMeetup());

        com.viaro.utils.LogReporter.init(this);
        com.viaro.utils.LogReporter.log(this, "Session started. Room: " + roomCode + ", Role: " + role + ", My ID: " + myId + ", Friend ID: " + friendId);

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationUpdates();

        mRoomRef = FirebaseHelper.getMeetupReference(roomCode);
        setupFirebaseListener();

        // Initialize Sensors
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        
        // Start movement prediction system
        startDeadReckoningTimer();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, mGravity, 0, event.values.length);
            mHasGravity = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, mGeomagnetic, 0, event.values.length);
            mHasGeomagnetic = true;
        }

        if (mHasGravity && mHasGeomagnetic) {
            float[] R_mat = new float[9];
            float[] I_mat = new float[9];
            boolean success = SensorManager.getRotationMatrix(R_mat, I_mat, mGravity, mGeomagnetic);
            if (success) {
                float[] orientation = new float[3];
                SensorManager.getOrientation(R_mat, orientation);
                float azimuth = orientation[0];
                float headingDegrees = (float) Math.toDegrees(azimuth);
                if (headingDegrees < 0) {
                    headingDegrees += 360f;
                }
                mCurrentCompassHeading = smoothHeading(mCurrentCompassHeading, headingDegrees);
                
                // Update heading if device is nearly standing still
                if (mMySpeedMs < 1.0f) {
                    mMyHeading = mCurrentCompassHeading;
                    if (mMyMarker != null) {
                        mMyMarker.setRotation(360f - mMyHeading);
                        rotateMapSmoothly(mMyHeading);
                    }
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private float smoothHeading(float current, float target) {
        float diff = target - current;
        while (diff < -180.0f) diff += 360.0f;
        while (diff > 180.0f) diff -= 360.0f;
        return (current + 0.15f * diff + 360.0f) % 360.0f;
    }

    private void rotateMapSmoothly(float targetHeading) {
        float currentOrient = mMapView.getMapOrientation();
        float diff = -targetHeading - currentOrient;
        while (diff < -180.0f) diff += 360.0f;
        while (diff > 180.0f) diff -= 360.0f;
        mMapView.setMapOrientation(currentOrient + 0.12f * diff);
    }

    private void setupLocationUpdates() {
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    double lat = location.getLatitude();
                    double lon = location.getLongitude();
                    double alt = location.hasAltitude() ? location.getAltitude() : 0.0;
                    float acc = location.hasAccuracy() ? location.getAccuracy() : 999.0f;

                    com.viaro.utils.LogReporter.log(MeetMapActivity.this, "RAW LOCATION RECEIVED: Lat=" + lat + ", Lon=" + lon + ", Alt=" + alt + ", Accuracy=" + acc + "m");

                    // 1. Highly Permissive Filters for Live Updates with high-quality threshold
                    if (location.hasAccuracy() && acc > 20.0f) {
                        if (mLastAcceptedLocation != null) {
                            com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FILTER REJECTED: Low accuracy (" + acc + "m > 20m) and we have a better fix.");
                            continue;
                        }
                    }

                    // Reject impossible jumps (> 250 km/h)
                    if (mLastAcceptedLocation != null) {
                        float[] distResults = new float[1];
                        Location.distanceBetween(
                            mLastAcceptedLocation.getLatitude(), mLastAcceptedLocation.getLongitude(),
                            lat, lon,
                            distResults
                        );
                        float distance = distResults[0];
                        long timeDiffMs = location.getTime() - mLastAcceptedLocation.getTime();
                        if (timeDiffMs > 0) {
                            double speedMs = distance / (timeDiffMs / 1000.0);
                            double speedKmh = speedMs * 3.6;
                            if (speedKmh > 250.0) {
                                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FILTER REJECTED: Impossible jump speed = " + speedKmh + " km/h");
                                continue;
                            }
                        }
                    }

                    mLastAcceptedLocation = location;
                    mLastMyGpsUpdateTimeMs = System.currentTimeMillis();

                    // Apply 2D Kalman Filter
                    final GeoPoint kalmanPoint = mMyKalmanFilter.filter(lat, lon, acc, mLastMyGpsUpdateTimeMs);

                    // Update local speed and heading variables
                    mMySpeedMs = location.hasSpeed() ? location.getSpeed() : 0.0f;
                    if (location.hasBearing()) {
                        mMyHeading = location.getBearing();
                    } else if (mMySpeedMs < 1.0f) {
                        mMyHeading = mCurrentCompassHeading;
                    }

                    // Snap to Nearest Road using OSRM Web API async
                    snapToRoadAsync(kalmanPoint, new RoadSnapCallback() {
                        @Override
                        public void onSnapped(GeoPoint snappedPoint) {
                            processSnappedMyUpdate(snappedPoint, location);
                        }
                    });
                }
            }
        };

        try {
            mFusedLocationClient.requestLocationUpdates(
                LocationHelper.createLocationRequest(),
                mLocationCallback,
                getMainLooper()
            );
        } catch (SecurityException ignored) {
        }
    }

    private void processSnappedMyUpdate(GeoPoint snappedPoint, Location location) {
        double lat = snappedPoint.getLatitude();
        double lon = snappedPoint.getLongitude();
        double alt = location.hasAltitude() ? location.getAltitude() : 0.0;
        float acc = location.hasAccuracy() ? location.getAccuracy() : 10.0f;

        GeoPoint oldPos = (mMyMarker != null) ? mMyMarker.getPosition() : null;

        // 2. High-Fidelity Path-Building Logic with Drift and Teleport Filters
        boolean shouldAddBreadcrumb = false;
        float displacement = 0.0f;

        if (acc <= 75.0f) {
            if (mMyPath.isEmpty()) {
                shouldAddBreadcrumb = true;
                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "BREADCRUMB PATH STARTED: First point added.");
            } else {
                GeoPoint lastPoint = mMyPath.get(mMyPath.size() - 1);
                float[] results = new float[1];
                Location.distanceBetween(
                    lastPoint.getLatitude(), lastPoint.getLongitude(),
                    lat, lon,
                    results
                );
                displacement = results[0];

                if (displacement > 150.0f) {
                    mMyPath.clear();
                    shouldAddBreadcrumb = true;
                    com.viaro.utils.LogReporter.log(MeetMapActivity.this, "BREADCRUMB PATH RESET: Teleportation detected (" + displacement + "m). Starting fresh.");
                } else {
                    boolean isMoving = true;
                    float threshold = 4.0f;

                    if (location.hasSpeed()) {
                        if (location.getSpeed() < 0.3f) {
                            isMoving = false;
                        }
                    } else {
                        threshold = 10.0f;
                    }

                    if (isMoving && displacement >= threshold) {
                        shouldAddBreadcrumb = true;
                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "BREADCRUMB ADDED: Moved " + String.format("%.2f", displacement) + "m >= " + threshold + "m threshold.");
                    }
                }
            }
        }

        if (shouldAddBreadcrumb) {
            mMyPath.add(snappedPoint);
            if (mBreadcrumbPolyline == null) {
                mBreadcrumbPolyline = new Polyline();
                mBreadcrumbPolyline.setColor(Color.BLUE);
                mBreadcrumbPolyline.setWidth(6.0f);
                mMapView.getOverlays().add(mBreadcrumbPolyline);
            }
            mBreadcrumbPolyline.setPoints(mMyPath);

            // Upload local breadcrumb point to Firebase
            if (mRoomRef != null) {
                String ptKey = mRoomRef.child(myId + "_path").push().getKey();
                if (ptKey != null) {
                    mRoomRef.child(myId + "_path").child(ptKey).setValue(new UserLocationModel(myId, lat, lon, alt));
                }
            }
        }

        // 3. ALWAYS update local marker position smoothly with interpolation
        if (mMyMarker == null) {
            mMyMarker = new Marker(mMapView);
            mMyMarker.setIcon(createLetterMarker(R.drawable.ic_location, myId.equals("user_a") ? "A" : "B"));
            mMyMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mMyMarker.setInfoWindow(null);
            mMyMarker.setOnMarkerClickListener((marker, mapView1) -> true);
            mMapView.getOverlays().add(mMyMarker);
            mMyMarker.setPosition(snappedPoint);
            mController.animateTo(snappedPoint);
        } else {
            animateMarkerSmoothly(mMyMarker, oldPos != null ? oldPos : mMyMarker.getPosition(), snappedPoint, 800);
        }

        mMyMarker.setRotation(360f - mMyHeading);

        // Camera follow behavior
        GeoPoint cameraTarget = calculateCameraTarget(snappedPoint, mMyHeading);
        mController.animateTo(cameraTarget);
        rotateMapSmoothly(mMyHeading);

        // Determine if we should upload to Firebase (movement > 1 meter or heading changes significantly)
        boolean shouldUpload = false;
        if (oldPos == null) {
            shouldUpload = true;
        } else {
            float[] distRes = new float[1];
            Location.distanceBetween(oldPos.getLatitude(), oldPos.getLongitude(), lat, lon, distRes);
            float distMoved = distRes[0];
            float headingDiff = Math.abs(mMyHeading - mLastUploadedHeading);
            while (headingDiff > 180.0f) headingDiff = 360.0f - headingDiff;

            if (distMoved >= 1.0f || headingDiff >= 15.0f) {
                shouldUpload = true;
            }
        }

        if (shouldUpload) {
            mLastUploadedHeading = mMyHeading;
            UserLocationModel userLoc = new UserLocationModel(
                myId,
                lat,
                lon,
                alt,
                location.hasBearing() ? location.getBearing() : 0.0f,
                location.hasSpeed() ? location.getSpeed() : 0.0f,
                acc,
                System.currentTimeMillis(),
                mMyHeading,
                "navigating"
            );
            if (mRoomRef != null) {
                mRoomRef.child(myId).setValue(userLoc);
                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "LIVE LOCATION PUBLISHED: Lat=" + lat + ", Lon=" + lon + ", Bearing=" + userLoc.getBearing() + ", Speed=" + userLoc.getSpeed());
            }
        }

        // Update UI distance and directions locally
        if (mFriendMarker != null) {
            updateDistanceDisplay(mFriendMarker.getPosition().getLatitude(), mFriendMarker.getPosition().getLongitude());
            updateNavigationGuidance(location, mFriendMarker.getPosition(), mFriendLastAltitude);
        } else {
            updateDistanceDisplay(0.0, 0.0);
        }
    }

    private void processFriendSnappedUpdate(GeoPoint friendSnappedPos, UserLocationModel friendLoc) {
        if (mFriendMarker == null) {
            mFriendMarker = new Marker(mMapView);
            mFriendMarker.setIcon(createLetterMarker(R.drawable.custom_marker, friendId.equals("user_a") ? "A" : "B"));
            mFriendMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mFriendMarker.setInfoWindow(null);
            mFriendMarker.setOnMarkerClickListener((marker, mapView) -> true);
            mMapView.getOverlays().add(mFriendMarker);
            mFriendMarker.setPosition(friendSnappedPos);

            if (mMyMarker == null) {
                mController.animateTo(friendSnappedPos);
            }
        } else {
            GeoPoint start = mFriendMarker.getPosition();
            animateMarkerSmoothly(mFriendMarker, start, friendSnappedPos, 1000);
        }

        mFriendMarker.setRotation(360f - mFriendHeading);
        mMapView.invalidate();

        // Compute distance even if local fix hasn't registered a full marker yet
        updateDistanceDisplay(friendSnappedPos.getLatitude(), friendSnappedPos.getLongitude());

        // Dynamic guidance update when friend moves
        if (mMyMarker != null) {
            Location myMockLoc = new Location("gps");
            myMockLoc.setLatitude(mMyMarker.getPosition().getLatitude());
            myMockLoc.setLongitude(mMyMarker.getPosition().getLongitude());
            if (mHasMyAltitude) {
                myMockLoc.setAltitude(mMyLastAltitude);
            }
            updateNavigationGuidance(myMockLoc, friendSnappedPos, mFriendLastAltitude);
        }
    }

    private void updateDistanceDisplay(double fLat, double fLon) {
        if (tvDistance == null) return;

        double myLat = 0.0;
        double myLon = 0.0;
        boolean hasMyLoc = false;

        if (mMyMarker != null) {
            myLat = mMyMarker.getPosition().getLatitude();
            myLon = mMyMarker.getPosition().getLongitude();
            hasMyLoc = true;
        }

        if (!hasMyLoc) {
            tvDistance.setText("Distance: Waiting for your GPS fix...");
            return;
        }

        if (fLat == 0.0 && fLon == 0.0) {
            tvDistance.setText("Distance: Waiting for friend's signal...");
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(
            myLat, myLon,
            fLat, fLon,
            results
        );
        float rawDistanceMeters = results[0];
        double displayDistanceMeters = com.viaro.utils.MapUtils.getDisplayDistance(rawDistanceMeters);
        double displayDistanceFeet = displayDistanceMeters * 3.28084;

        if (rawDistanceMeters >= 1000) {
            tvDistance.setText(String.format("Distance: %.2f km", rawDistanceMeters / 1000.0));
        } else if (displayDistanceMeters <= 0.3) {
            tvDistance.setText(String.format("Distance: %.1f m (%.1f ft) - Arrived!", displayDistanceMeters, displayDistanceFeet));
        } else if (displayDistanceMeters < 10.0) {
            tvDistance.setText(String.format("Distance: %.1f m (%.1f ft)", displayDistanceMeters, displayDistanceFeet));
        } else {
            tvDistance.setText(String.format("Distance: %.0f meters", displayDistanceMeters));
        }

        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "DISTANCE COMPUTED: Raw=" + String.format("%.2f", rawDistanceMeters) + "m, Display=" + String.format("%.2f", displayDistanceMeters) + "m (" + String.format("%.2f", displayDistanceFeet) + " ft)");
    }

    private void setupFirebaseListener() {
        if (mRoomRef == null) return;

        mRoomListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                DataSnapshot friendSnapshot = snapshot.child(friendId);
                if (friendSnapshot.exists()) {
                    final UserLocationModel friendLoc = friendSnapshot.getValue(UserLocationModel.class);
                    if (friendLoc != null) {
                        double fLat = friendLoc.getLatitude();
                        double fLon = friendLoc.getLongitude();
                        double fAlt = friendLoc.getAltitude();
                        mFriendLastAltitude = fAlt;
                        mHasFriendAltitude = (mFriendLastAltitude != 0.0);

                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FRIEND UPDATE RECEIVED: Lat=" + fLat + ", Lon=" + fLon + ", Alt=" + fAlt);

                        mLastFriendGpsUpdateTimeMs = System.currentTimeMillis();
                        mFriendSpeedMs = friendLoc.getSpeed();
                        mFriendHeading = friendLoc.getHeading() != 0.0f ? friendLoc.getHeading() : friendLoc.getBearing();

                        // Filter friend location using friend's Kalman Filter
                        float friendAcc = friendLoc.getAccuracy() > 0 ? friendLoc.getAccuracy() : 15.0f;
                        GeoPoint friendFilteredPos = mFriendKalmanFilter.filter(fLat, fLon, friendAcc, mLastFriendGpsUpdateTimeMs);

                        // Snap friend to nearest road async
                        snapToRoadAsync(friendFilteredPos, new RoadSnapCallback() {
                            @Override
                            public void onSnapped(GeoPoint friendSnappedPos) {
                                processFriendSnappedUpdate(friendSnappedPos, friendLoc);
                            }
                        });
                    }
                }

                // Listen and render friend's breadcrumb trail so blue lines show on both phones!
                DataSnapshot friendPathSnapshot = snapshot.child(friendId + "_path");
                if (friendPathSnapshot.exists()) {
                    mFriendPath.clear();
                    for (DataSnapshot ptSnap : friendPathSnapshot.getChildren()) {
                        UserLocationModel pt = ptSnap.getValue(UserLocationModel.class);
                        if (pt != null) {
                            mFriendPath.add(new GeoPoint(pt.getLatitude(), pt.getLongitude()));
                        }
                    }
                    if (!mFriendPath.isEmpty()) {
                        if (mFriendBreadcrumbPolyline == null) {
                            mFriendBreadcrumbPolyline = new Polyline();
                            mFriendBreadcrumbPolyline.setColor(Color.BLUE);
                            mFriendBreadcrumbPolyline.setWidth(6.0f);
                            mMapView.getOverlays().add(mFriendBreadcrumbPolyline);
                        }
                        mFriendBreadcrumbPolyline.setPoints(mFriendPath);
                        mMapView.invalidate();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FIREBASE ERROR: " + error.getMessage());
            }
        };

        mRoomRef.addValueEventListener(mRoomListener);
    }

    private void updateNavigationGuidance(Location myLocation, GeoPoint friendPos, double friendAltitude) {
        if (myLocation == null || friendPos == null || tvGuidance == null) return;

        GeoPoint myPos = new GeoPoint(myLocation.getLatitude(), myLocation.getLongitude());

        // 1. Calculate relative turn instruction
        float myHeading = mMyLastHeading;
        if (myLocation.hasBearing()) {
            myHeading = myLocation.getBearing();
            mMyLastHeading = myHeading;
        } else if (mMyPath.size() >= 2) {
            GeoPoint prev = mMyPath.get(mMyPath.size() - 2);
            myHeading = com.viaro.utils.MapUtils.calculateBearing(prev, myPos);
            mMyLastHeading = myHeading;
        }

        float targetBearing = com.viaro.utils.MapUtils.calculateBearing(myPos, friendPos);
        String turnInstruction = com.viaro.utils.MapUtils.getDirectionInstruction(myHeading, targetBearing);

        // 2. Track altitude differences for indoor vertical elevation changes (Glitch 4)
        String elevationInfo = "";
        if (myLocation.hasAltitude()) {
            mMyLastAltitude = myLocation.getAltitude();
            mHasMyAltitude = true;
        }

        if (mHasMyAltitude && friendAltitude != 0.0) {
            double altDiff = friendAltitude - mMyLastAltitude;
            if (altDiff > 1.5) {
                elevationInfo = " (Friend is upstairs)";
            } else if (altDiff < -1.5) {
                elevationInfo = " (Friend is downstairs)";
            } else {
                elevationInfo = " (Same floor)";
            }
        }

        String finalInstruction = "Directions: " + turnInstruction + elevationInfo;
        tvGuidance.setText(finalInstruction);

        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "GUIDANCE CALC: " + finalInstruction + " [Heading=" + myHeading + "°, TargetBearing=" + targetBearing + "°, MyAlt=" + mMyLastAltitude + ", FriendAlt=" + friendAltitude + "]");
    }

    private void endMeetup() {
        if (mDeadReckoningHandler != null && mDeadReckoningRunnable != null) {
            mDeadReckoningHandler.removeCallbacks(mDeadReckoningRunnable);
        }
        if (mRoomRef != null) {
            if (mRoomListener != null) {
                mRoomRef.removeEventListener(mRoomListener);
            }
            FirebaseHelper.removeMeetupRoom(roomCode);
        }
        if (mFusedLocationClient != null && mLocationCallback != null) {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mMapView != null) mMapView.onResume();
        if (mSensorManager != null) {
            if (mAccelerometer != null) {
                mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (mMagnetometer != null) {
                mSensorManager.registerListener(this, mMagnetometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMapView != null) mMapView.onPause();
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        endMeetup();
        if (mMapView != null) mMapView.onDetach();
    }
}
