package com.viaro.activities;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
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
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import java.util.ArrayList;

public class MeetMapActivity extends AppCompatActivity implements SensorEventListener {

    private MapView mMapView;
    private IMapController mController;
    private Marker mMyMarker, mFriendMarker;
    private Polyline mBreadcrumbPolyline;
    private Polyline mFriendBreadcrumbPolyline;
    private final ArrayList<GeoPoint> mFriendPath = new ArrayList<>();

    private TextView tvStatus, tvDistance, tvGuidance;
    private ImageButton btnShareLocation, btnExplore;

    private String roomCode, role, myId, friendId;
    private DatabaseReference mRoomRef;
    private ValueEventListener mRoomListener;

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationListener mNativeLocationListener;

    private final ArrayList<GeoPoint> mMyPath = new ArrayList<>();

    private double mMyLastAltitude = 0.0;
    private boolean mHasMyAltitude = false;
    private double mFriendLastAltitude = 0.0;
    private boolean mHasFriendAltitude = false;
    private float mMyLastHeading = 0.0f;

    // Temporal altitude smoothing fields to handle raw vertical sensor errors
    private double mMySmoothAltitude = 0.0;
    private double mFriendSmoothAltitude = 0.0;

    // Control toggles for the enhanced features
    private boolean mIsRotationPaused = false;
    private boolean mIsLiveSharing = true;

    // Hardware GNSS Status fields and references
    private LocationManager mLocationManager;
    private GnssStatus.Callback mGnssStatusCallback;
    private boolean mIsGnssActive = false;

    private RelativeLayout mLayoutGnssOverlay;
    private CardView mCardGnssMinimized, mCardGnssTip;
    private TextView mTvGnssMinimizedStatus, mTvGnssSatsCount, mTvGnssHardwarePrecision, mTvGnssAccuracy, mTvGnssSignalStrength, mTvGnssTip;
    private ProgressBar mBarGnssSignalAvg;
    private LinearLayout mLayoutSatelliteList;

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
    
    private double mMySpeedMs = 0.0;
    private double mMyHeading = 0.0;
    private double mFriendSpeedMs = 0.0;
    private double mFriendHeading = 0.0;
    private double mLastFriendGpsAccuracy = 0.0;

    // Last accepted location for drift filtering
    private Location mLastAcceptedLocation = null;
    private double mLastUploadedHeading = 0.0;

    // Marker active animators to prevent concurrent conflicts
    private final java.util.Map<Marker, ValueAnimator> mMarkerAnimators = new java.util.HashMap<>();

    public static class GpsKalmanFilter {
        private double lat;
        private double lon;
        private double variance; // Error covariance in degrees squared
        private long lastTimeMs;
        // Process noise in degrees (1e-5 degrees is approximately 1.1 meters)
        private static final double PROCESS_NOISE_DEG = 1e-5; 

        public GpsKalmanFilter() {
            this.variance = -1.0;
            this.lastTimeMs = 0;
        }

        public void reset() {
            this.variance = -1.0;
            this.lastTimeMs = 0;
        }

        public GeoPoint filter(double newLat, double newLon, double accuracyMeters, long timeMs) {
            // Convert accuracy in meters to geographic degrees
            double accuracyDegrees = accuracyMeters / 111000.0;
            double measurementVariance = accuracyDegrees * accuracyDegrees; // in degrees squared

            if (variance < 0) {
                this.lat = newLat;
                this.lon = newLon;
                this.variance = measurementVariance;
                this.lastTimeMs = timeMs;
                return new GeoPoint(newLat, newLon);
            }

            long durationMs = timeMs - lastTimeMs;
            if (durationMs <= 0) durationMs = 1;
            lastTimeMs = timeMs;

            double dtSeconds = durationMs / 1000.0;
            // Update error variance in degree coordinates
            this.variance += dtSeconds * PROCESS_NOISE_DEG * PROCESS_NOISE_DEG;

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

    private GeoPoint predictNextPosition(GeoPoint current, double speedMs, double bearingDegrees, double dtSeconds) {
        if (speedMs <= 0.1) return current;
        
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
                    if (elapsed > 2500) { // Remote update is delayed
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

        // Register gestures overlay to intercept long-press events for rotation pause
        MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                mIsRotationPaused = !mIsRotationPaused;
                runOnUiThread(() -> {
                    if (mIsRotationPaused) {
                        android.widget.Toast.makeText(MeetMapActivity.this, "Map orientation locked (Rotation paused)", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        android.widget.Toast.makeText(MeetMapActivity.this, "Map orientation unlocked (Rotation active)", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            }
        });
        mMapView.getOverlays().add(0, mapEventsOverlay);

        // Bind and setup the new Explore (Compass Orientation Reset) Button
        btnExplore = findViewById(R.id.btn_explore);
        btnExplore.setOnClickListener(v -> {
            mMapView.setMapOrientation(0.0f);
            android.widget.Toast.makeText(this, "Map oriented to North", android.widget.Toast.LENGTH_SHORT).show();
        });

        // Bind and setup the new Live Share Location / End toggle button
        btnShareLocation = findViewById(R.id.btn_share_location);
        btnShareLocation.setOnClickListener(v -> {
            mIsLiveSharing = !mIsLiveSharing;
            if (mIsLiveSharing) {
                // Set green background color tint when active
                btnShareLocation.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#22C55E")));
                android.widget.Toast.makeText(this, "Location Sharing Live", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                // Set red background color tint when ended/paused
                btnShareLocation.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#EF4444")));
                android.widget.Toast.makeText(this, "Location Sharing Paused", android.widget.Toast.LENGTH_SHORT).show();
                // Clean up location data on Firebase while sharing is toggled off
                if (mRoomRef != null && myId != null) {
                    mRoomRef.child(myId).removeValue();
                }
            }
        });

        // Bind the new GNSS HUD components
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mLayoutGnssOverlay = findViewById(R.id.layout_gnss_overlay);
        mCardGnssMinimized = findViewById(R.id.card_gnss_minimized);
        mCardGnssTip = findViewById(R.id.card_gnss_tip);

        mTvGnssMinimizedStatus = findViewById(R.id.tv_gnss_minimized_status);
        mTvGnssSatsCount = findViewById(R.id.tv_gnss_sats_count);
        mTvGnssHardwarePrecision = findViewById(R.id.tv_gnss_hardware_precision);
        mTvGnssAccuracy = findViewById(R.id.tv_gnss_accuracy);
        mTvGnssSignalStrength = findViewById(R.id.tv_gnss_signal_strength);
        mTvGnssTip = findViewById(R.id.tv_gnss_tip);
        mBarGnssSignalAvg = findViewById(R.id.bar_gnss_signal_avg);
        mLayoutSatelliteList = findViewById(R.id.layout_satellite_list);

        // Bind GNSS triggers and handlers
        findViewById(R.id.btn_gnss).setOnClickListener(v -> {
            mLayoutGnssOverlay.setVisibility(View.VISIBLE);
            mCardGnssMinimized.setVisibility(View.GONE);
            mIsGnssActive = true;
        });

        findViewById(R.id.btn_gnss_minimize).setOnClickListener(v -> {
            mLayoutGnssOverlay.setVisibility(View.GONE);
            mCardGnssMinimized.setVisibility(View.VISIBLE);
        });

        findViewById(R.id.btn_gnss_maximize).setOnClickListener(v -> {
            mLayoutGnssOverlay.setVisibility(View.VISIBLE);
            mCardGnssMinimized.setVisibility(View.GONE);
        });

        findViewById(R.id.btn_gnss_close).setOnClickListener(v -> {
            mLayoutGnssOverlay.setVisibility(View.GONE);
            mCardGnssMinimized.setVisibility(View.GONE);
            mIsGnssActive = false;
        });

        findViewById(R.id.btn_gnss_minimized_close).setOnClickListener(v -> {
            mLayoutGnssOverlay.setVisibility(View.GONE);
            mCardGnssMinimized.setVisibility(View.GONE);
            mIsGnssActive = false;
        });

        // Setup the GNSS Status Callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mGnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                    updateGnssDisplay(status);
                }
            };
        }

        findViewById(R.id.btn_my_location).setOnClickListener(v -> {
            if (mMyMarker != null) {
                mController.animateTo(mMyMarker.getPosition());
                mController.setZoom(17.5);
            } else if (mLastAcceptedLocation != null) {
                mController.animateTo(new GeoPoint(mLastAcceptedLocation.getLatitude(), mLastAcceptedLocation.getLongitude()));
                mController.setZoom(17.5);
            } else {
                android.widget.Toast.makeText(this, "Acquiring GPS location...", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

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

    /**
     * Aggregates and displays real-time GNSS satellite details inside the overlays
     */
    private void updateGnssDisplay(GnssStatus status) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        int totalCount = status.getSatelliteCount();
        int usedInFixCount = 0;
        float accumulatedSignal = 0f;
        boolean hasDualBandTracking = false;

        for (int i = 0; i < totalCount; i++) {
            if (status.usedInFix(i)) {
                usedInFixCount++;
            }
            accumulatedSignal += status.getCn0DbHz(i);
            
            // Check for modern L5 high-precision dual-band tracking
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (status.hasCarrierFrequencyHz(i)) {
                    double freq = status.getCarrierFrequencyHz(i);
                    // L5 carrier frequency band falls around 1.176 GHz (1.15e9 to 1.2e9 Hz range)
                    if (freq >= 1.15e9 && freq <= 1.20e9) {
                        hasDualBandTracking = true;
                    }
                }
            }
        }

        float avgSignal = totalCount > 0 ? (accumulatedSignal / totalCount) : 0f;

        // Update top-level HUD summary components
        mTvGnssSatsCount.setText(String.format("Active Satellites: %d used / %d in view", usedInFixCount, totalCount));
        mTvGnssHardwarePrecision.setText(hasDualBandTracking ? "Precision Mode: L1+L5 Dual-Band (High)" : "Precision Mode: L1 Single-Band (Standard)");
        mTvGnssSignalStrength.setText(String.format("%.1f dB-Hz (%s)", avgSignal, avgSignal > 30 ? "Strong Lock" : avgSignal > 18 ? "Weak/Bounced" : "No Lock"));
        mBarGnssSignalAvg.setProgress((int) avgSignal);

        // Adjust UI bar tint color based on signal health
        if (avgSignal > 30) {
            mBarGnssSignalAvg.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#22C55E")));
        } else if (avgSignal > 18) {
            mBarGnssSignalAvg.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#F59E0B")));
        } else {
            mBarGnssSignalAvg.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#EF4444")));
        }

        // Update minimized floating card data
        mTvGnssMinimizedStatus.setText(String.format("📡 %d/%d Sats | Avg: %.0f dB", usedInFixCount, totalCount, avgSignal));

        // Inject smart context-aware tips based on GNSS satellite configuration
        if (usedInFixCount < 4) {
            mTvGnssTip.setText("Tip: Acquiring satellite signals... If indoors, step near a window to establish a line-of-sight location fix.");
            mCardGnssTip.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#26EF4444"))); // Translucent Red
            mTvGnssTip.setTextColor(Color.parseColor("#FCA5A5"));
        } else if (usedInFixCount >= 8 && avgSignal > 28) {
            mTvGnssTip.setText("Tip: Strong signal lock. Location updates are highly precise. Relative floor elevation tracking is active.");
            mCardGnssTip.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#2622C55E"))); // Translucent Green
            mTvGnssTip.setTextColor(Color.parseColor("#86EFAC"));
        } else {
            mTvGnssTip.setText("Tip: Moderate signal lock. Signal reflection may occur in dense urban spaces or under heavy tree cover.");
            mCardGnssTip.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#26F59E0B"))); // Translucent Amber
            mTvGnssTip.setTextColor(Color.parseColor("#FDE047"));
        }

        // Build list of active satellites programmatically to avoid heavy XML recycler adapter overhead
        mLayoutSatelliteList.removeAllViews();
        for (int i = 0; i < totalCount; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, (int) (8 * getResources().getDisplayMetrics().density));

            // Constellation Type Symbol + SVID
            TextView tvSatInfo = new TextView(this);
            tvSatInfo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f));
            tvSatInfo.setTextColor(Color.parseColor("#F8FAFC"));
            tvSatInfo.setTextSize(13);
            int constType = status.getConstellationType(i);
            String constSymbol = "🛰️";
            if (constType == GnssStatus.CONSTELLATION_GPS) constSymbol = "🇺🇸 GPS";
            else if (constType == GnssStatus.CONSTELLATION_GLONASS) constSymbol = "🇷🇺 GLON";
            else if (constType == GnssStatus.CONSTELLATION_GALILEO) constSymbol = "🇪🇺 GAL";
            else if (constType == GnssStatus.CONSTELLATION_BEIDOU) constSymbol = "🇨🇳 BDS";
            else if (constType == GnssStatus.CONSTELLATION_QZSS) constSymbol = "🇯🇵 QZSS";
            tvSatInfo.setText(constSymbol + " #" + status.getSvid(i));

            // Used in active calculation state
            TextView tvFixInfo = new TextView(this);
            tvFixInfo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            tvFixInfo.setTextSize(12);
            if (status.usedInFix(i)) {
                tvFixInfo.setTextColor(Color.parseColor("#22C55E"));
                tvFixInfo.setText("✔️ Fix");
            } else {
                tvFixInfo.setTextColor(Color.parseColor("#94A3B8"));
                tvFixInfo.setText("⭕ View");
            }

            // Signal bar progress indicators
            ProgressBar pBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, (int) (6 * getResources().getDisplayMetrics().density), 2.0f);
            barParams.setMarginStart((int) (8 * getResources().getDisplayMetrics().density));
            barParams.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
            pBar.setLayoutParams(barParams);
            pBar.setMax(45);
            float cn0 = status.getCn0DbHz(i);
            pBar.setProgress((int) cn0);

            if (cn0 > 30) {
                pBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#22C55E")));
            } else if (cn0 > 18) {
                pBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#F59E0B")));
            } else {
                pBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#EF4444")));
            }

            // Exact strength readout in decibels
            TextView tvDbVal = new TextView(this);
            tvDbVal.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            tvDbVal.setTextColor(Color.parseColor("#94A3B8"));
            tvDbVal.setTextSize(12);
            tvDbVal.setGravity(android.view.Gravity.END);
            tvDbVal.setText(String.format("%.1f dB", cn0));

            row.addView(tvSatInfo);
            row.addView(tvFixInfo);
            row.addView(pBar);
            row.addView(tvDbVal);

            mLayoutSatelliteList.addView(row);
        }
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
                if (mMySpeedMs < 1.0) {
                    mMyHeading = mCurrentCompassHeading;
                    if (mMyMarker != null) {
                        mMyMarker.setRotation((float) (360.0 - mMyHeading));
                        if (!mIsRotationPaused) {
                            rotateMapSmoothly((float) mMyHeading);
                        }
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
        mNativeLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                handleRawLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(@NonNull String provider) {}

            @Override
            public void onProviderDisabled(@NonNull String provider) {}
        };

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    handleRawLocation(location);
                }
            }
        };

        LocationHelper.startDualEngineLocationUpdates(
            this,
            mFusedLocationClient,
            mLocationCallback,
            mNativeLocationListener,
            getMainLooper()
        );

        // Immediate last known location fix for fast cold start (isolate from handleRawLocation tracking filter)
        Location lastKnown = LocationHelper.getLastKnownLocation(this, mFusedLocationClient);
        if (lastKnown != null) {
            GeoPoint lastKnownPoint = new GeoPoint(lastKnown.getLatitude(), lastKnown.getLongitude());
            mController.setCenter(lastKnownPoint);
        }
    }

    private void handleRawLocation(Location location) {
        if (location == null) return;

        double lat = location.getLatitude();
        double lon = location.getLongitude();
        double alt = location.hasAltitude() ? location.getAltitude() : 0.0;
        double acc = location.hasAccuracy() ? location.getAccuracy() : 999.0;

        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "RAW LOCATION RECEIVED: Lat=" + lat + ", Lon=" + lon + ", Alt=" + alt + ", Accuracy=" + acc + "m");

        // Relaxed filters for cold start vs ongoing updates (Relaxed to 120.0m to prevent asymmetric freezes indoors)
        if (mLastAcceptedLocation == null) {
            if (acc > 150.0) {
                return;
            }
        } else {
            if (acc > 120.0) {
                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FILTER REJECTED: Low accuracy (" + acc + "m > 120m)");
                return;
            }

            float[] distResults = new float[1];
            Location.distanceBetween(
                mLastAcceptedLocation.getLatitude(), mLastAcceptedLocation.getLongitude(),
                lat, lon,
                distResults
            );
            float distance = distResults[0];
            long timeDiffMs = location.getTime() - mLastAcceptedLocation.getTime();
            if (timeDiffMs > 0) {
                // Ensure we only validate speed jumps if the time update is not an instantaneous/rapid double firing
                if (timeDiffMs >= 1000) {
                    double speedMs = distance / (timeDiffMs / 1000.0);
                    double speedKmh = speedMs * 3.6;
                    if (speedKmh > 300.0) {
                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FILTER REJECTED: Impossible jump speed = " + speedKmh + " km/h");
                        return;
                    }
                }
            }
        }

        mLastAcceptedLocation = location;
        mLastMyGpsUpdateTimeMs = System.currentTimeMillis();

        // Apply corrected 2D Kalman Filter
        final GeoPoint kalmanPoint = mMyKalmanFilter.filter(lat, lon, acc, mLastMyGpsUpdateTimeMs);

        // Update local speed and heading variables
        mMySpeedMs = location.hasSpeed() ? location.getSpeed() : 0.0;
        if (location.hasBearing()) {
            mMyHeading = location.getBearing();
        } else if (mMySpeedMs < 1.0) {
            mMyHeading = mCurrentCompassHeading;
        }

        // Keep the GNSS accuracy UI overlay fully in sync with standard Android GPS updates
        if (mTvGnssAccuracy != null) {
            float vAcc = 0.0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vAcc = location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : 0.0f;
            }
            mTvGnssAccuracy.setText(String.format("Error Threshold: Horizontal ±%.1fm | Vertical ±%.1fm", acc, vAcc));
        }

        // Bypass road snapping for indoor/pedestrian meetups to avoid snapping artifacts
        processSnappedMyUpdate(kalmanPoint, location);
    }

    private void processSnappedMyUpdate(GeoPoint snappedPoint, Location location) {
        double lat = snappedPoint.getLatitude();
        double lon = snappedPoint.getLongitude();
        double alt = location.hasAltitude() ? location.getAltitude() : 0.0;
        double acc = location.hasAccuracy() ? location.getAccuracy() : 10.0;

        GeoPoint oldPos = (mMyMarker != null) ? mMyMarker.getPosition() : null;

        boolean shouldAddBreadcrumb = false;
        float displacement = 0.0f;

        if (acc <= 75.0) {
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

            if (mRoomRef != null) {
                String ptKey = mRoomRef.child(myId + "_path").push().getKey();
                if (ptKey != null) {
                    mRoomRef.child(myId + "_path").child(ptKey).setValue(new UserLocationModel(myId, lat, lon, alt));
                }
            }
        }

        // ALWAYS update local marker position smoothly
        if (mMyMarker == null) {
            mMyMarker = new Marker(mMapView);
            mMyMarker.setIcon(createLetterMarker(R.drawable.ic_location, myId.equals("user_a") ? "A" : "B"));
            mMyMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mMyMarker.setInfoWindow(null);
            mMyMarker.setOnMarkerClickListener((marker, mapView1) -> {
                String roleLabel = "creator".equals(role) ? "Creator (You)" : "Joiner (You)";
                showLocationDetailsDialog(
                    roleLabel,
                    myId,
                    marker.getPosition(),
                    (float) mMySpeedMs,
                    (float) mMyHeading,
                    mMyLastAltitude,
                    mLastAcceptedLocation != null ? mLastAcceptedLocation.getAccuracy() : 0.0f,
                    mLastMyGpsUpdateTimeMs
                );
                return true;
            });
            mMapView.getOverlays().add(mMyMarker);
            mMyMarker.setPosition(snappedPoint);
            mController.animateTo(snappedPoint);
        } else {
            animateMarkerSmoothly(mMyMarker, oldPos != null ? oldPos : mMyMarker.getPosition(), snappedPoint, 800);
        }

        mMyMarker.setRotation((float) (360.0 - mMyHeading));

        // Camera follow behavior
        GeoPoint cameraTarget = calculateCameraTarget(snappedPoint, (float) mMyHeading);
        mController.animateTo(cameraTarget);
        if (!mIsRotationPaused) {
            rotateMapSmoothly((float) mMyHeading);
        }

        // ALWAYS publish live location to Firebase on every GPS fix, provided that Live Sharing toggle is active
        if (mIsLiveSharing) {
            mLastUploadedHeading = mMyHeading;
            UserLocationModel userLoc = new UserLocationModel(
                myId,
                lat,
                lon,
                alt,
                location.hasBearing() ? (double) location.getBearing() : 0.0,
                location.hasSpeed() ? (double) location.getSpeed() : 0.0,
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
            mFriendMarker.setOnMarkerClickListener((marker, mapView) -> {
                String roleLabel = "creator".equals(role) ? "Joiner (Friend)" : "Creator (Friend)";
                showLocationDetailsDialog(
                    roleLabel,
                    friendId,
                    marker.getPosition(),
                    (float) mFriendSpeedMs,
                    (float) mFriendHeading,
                    mFriendLastAltitude,
                    (float) mLastFriendGpsAccuracy,
                    mLastFriendGpsUpdateTimeMs
                );
                return true;
            });
            mMapView.getOverlays().add(mFriendMarker);
            mFriendMarker.setPosition(friendSnappedPos);

            if (mMyMarker == null) {
                mController.animateTo(friendSnappedPos);
            }
        } else {
            GeoPoint start = mFriendMarker.getPosition();
            animateMarkerSmoothly(mFriendMarker, start, friendSnappedPos, 1000);
        }

        mFriendMarker.setRotation((float) (360.0 - mFriendHeading));
        mMapView.invalidate();

        updateDistanceDisplay(friendSnappedPos.getLatitude(), friendSnappedPos.getLongitude());

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

    private void showLocationDetailsDialog(
            String displayTitle,
            String userId,
            GeoPoint pos,
            float speedMs,
            float headingDeg,
            double altitude,
            float accuracyMeters,
            long lastUpdateMs
    ) {
        if (isFinishing() || isDestroyed()) return;

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0F172A"));
        int paddingPx = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView logoIv = new android.widget.ImageView(this);
        int logoSize = (int) (38 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams logoParams = new android.widget.LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
        logoIv.setLayoutParams(logoParams);
        logoIv.setImageResource(R.drawable.viaro);

        android.widget.TextView titleTv = new android.widget.TextView(this);
        titleTv.setText(displayTitle + " (" + userId + ")");
        titleTv.setTextSize(17f);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setTextColor(Color.parseColor("#FFFFFF"));

        header.addView(logoIv);
        header.addView(titleTv);
        layout.addView(header);

        android.widget.TextView detailsTv = new android.widget.TextView(this);
        detailsTv.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        detailsTv.setTextSize(14f);
        detailsTv.setTextColor(Color.parseColor("#F8FAFC"));

        double lat = pos != null ? pos.getLatitude() : 0.0;
        double lon = pos != null ? pos.getLongitude() : 0.0;
        double speedKmh = speedMs * 3.6;
        long timeAgoSec = lastUpdateMs > 0 ? Math.max(0, (System.currentTimeMillis() - lastUpdateMs) / 1000) : 0;
        String timeText = lastUpdateMs > 0 ? (timeAgoSec + "s ago") : "Just now";

        String initialText = String.format(
            "📍 Location Name:\nFetching address...\n\n" +
            "🌐 Exact Coordinates:\nLat: %.6f\nLon: %.6f\n\n" +
            "⚡ Speed & Direction:\n%.1f km/h | Bearing: %.0f°\n\n" +
            "⛰️ Altitude & Accuracy:\nAlt: %.1f m | Acc: ±%.1f m\n\n" +
            "⏱️ Last Update: %s",
            lat, lon, speedKmh, headingDeg, altitude, accuracyMeters, timeText
        );
        detailsTv.setText(initialText);
        layout.addView(detailsTv);

        builder.setView(layout);
        builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
        if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#38BDF8"));
        }

        // Reverse Geocoding in background thread
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String addressName = "Location Address";
            try {
                android.location.Geocoder geocoder = new android.location.Geocoder(this, java.util.Locale.getDefault());
                java.util.List<android.location.Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    android.location.Address addr = addresses.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (addr.getMaxAddressLineIndex() >= 0) {
                        sb.append(addr.getAddressLine(0));
                    } else {
                        if (addr.getThoroughfare() != null) sb.append(addr.getThoroughfare()).append(", ");
                        if (addr.getLocality() != null) sb.append(addr.getLocality()).append(", ");
                        if (addr.getAdminArea() != null) sb.append(addr.getAdminArea());
                    }
                    addressName = sb.toString();
                }
            } catch (Exception e) {
                addressName = String.format("Area near %.4f, %.4f", lat, lon);
            }

            final String finalAddress = addressName;
            runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    String updatedText = String.format(
                        "📍 Location Name:\n%s\n\n" +
                        "🌐 Exact Coordinates:\nLat: %.6f\nLon: %.6f\n\n" +
                        "⚡ Speed & Direction:\n%.1f km/h | Bearing: %.0f°\n\n" +
                        "⛰️ Altitude & Accuracy:\nAlt: %.1f m | Acc: ±%.1f m\n\n" +
                        "⏱️ Last Update: %s",
                        finalAddress, lat, lon, speedKmh, headingDeg, altitude, accuracyMeters, timeText
                    );
                    detailsTv.setText(updatedText);
                }
            });
        });
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
                        mFriendHeading = friendLoc.getHeading() != 0.0 ? friendLoc.getHeading() : friendLoc.getBearing();

                        double friendAcc = friendLoc.getAccuracy() > 0 ? friendLoc.getAccuracy() : 15.0;
                        mLastFriendGpsAccuracy = friendAcc;

                        GeoPoint friendFilteredPos = mFriendKalmanFilter.filter(fLat, fLon, friendAcc, mLastFriendGpsUpdateTimeMs);

                        // Bypass road snapping for friend updates to maintain raw accuracy alignment
                        processFriendSnappedUpdate(friendFilteredPos, friendLoc);
                    }
                }

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

        String elevationInfo = "";
        if (myLocation.hasAltitude()) {
            double rawAlt = myLocation.getAltitude();
            // Low-pass exponential smoothing to stabilize local vertical sensor jitter
            mMySmoothAltitude = (mMySmoothAltitude == 0.0) ? rawAlt : (mMySmoothAltitude * 0.9 + rawAlt * 0.1);
            mMyLastAltitude = mMySmoothAltitude;
            mHasMyAltitude = true;
        }

        if (mHasMyAltitude && friendAltitude != 0.0) {
            // Low-pass exponential smoothing to stabilize remote vertical sensor jitter
            mFriendSmoothAltitude = (mFriendSmoothAltitude == 0.0) ? friendAltitude : (mFriendSmoothAltitude * 0.9 + friendAltitude * 0.1);
            
            double altDiff = mFriendSmoothAltitude - mMySmoothAltitude;
            if (altDiff > 2.5) { // Threshold adjusted to 2.5m to mitigate trigger jitter
                elevationInfo = " (Friend is upstairs)";
            } else if (altDiff < -2.5) {
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
            if (myId != null) {
                mRoomRef.child(myId).removeValue();
            }
        }
        LocationHelper.stopDualEngineLocationUpdates(
            this,
            mFusedLocationClient,
            mLocationCallback,
            mNativeLocationListener
        );
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
        
        // Register the active GNSS tracking receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mLocationManager != null && mGnssStatusCallback != null) {
            try {
                mLocationManager.registerGnssStatusCallback(mGnssStatusCallback, new Handler(Looper.getMainLooper()));
            } catch (SecurityException ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMapView != null) mMapView.onPause();
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        
        // Unregister the GNSS receiver to prevent memory leaks and save battery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mLocationManager != null && mGnssStatusCallback != null) {
            mLocationManager.unregisterGnssStatusCallback(mGnssStatusCallback);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        endMeetup();
        if (mMapView != null) mMapView.onDetach();
    }
}