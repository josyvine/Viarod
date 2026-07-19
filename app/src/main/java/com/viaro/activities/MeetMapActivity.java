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

public class MeetMapActivity extends AppCompatActivity {

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
                    long ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L;

                    com.viaro.utils.LogReporter.log(MeetMapActivity.this, "RAW LOCATION RECEIVED: Lat=" + lat + ", Lon=" + lon + ", Alt=" + alt + ", Accuracy=" + acc + "m, Age=" + ageMs + "ms");

                    // 1. Relaxed Accuracy & Staleness Filters (for indoor testing and reliable distance fixes!)
                    if (location.hasAccuracy() && acc > 150.0f) {
                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FILTER REJECTED: Extremely low accuracy (" + acc + "m > 150m)");
                        continue;
                    }
                    if (ageMs > 25000L) {
                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FILTER REJECTED: Stale update (" + ageMs + "ms > 25000ms)");
                        continue;
                    }

                    GeoPoint myPos = new GeoPoint(lat, lon);

                    // 2. Separate Live Upload from Breadcrumb Drawing (Accurate Path building filter)
                    boolean shouldAddBreadcrumb = false;
                    float displacement = 0.0f;
                    // Only build trails if accuracy is clean (<= 45m) to avoid zig-zag drift lines
                    if (acc <= 45.0f) {
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
                            if (displacement >= 4.0f) {
                                shouldAddBreadcrumb = true;
                                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "BREADCRUMB ADDED: Moved " + String.format("%.2f", displacement) + "m >= 4.0m threshold.");
                            } else {
                                com.viaro.utils.LogReporter.log(MeetMapActivity.this, "BREADCRUMB SUPPRESSED (DRIFT FILTER): Moved " + String.format("%.2f", displacement) + "m < 4.0m threshold.");
                            }
                        }
                    } else {
                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "BREADCRUMB SUPPRESSED: Accuracy (" + acc + "m) is too low for path building (> 45m).");
                    }

                    if (shouldAddBreadcrumb) {
                        mMyPath.add(myPos);
                        if (mBreadcrumbPolyline == null) {
                            mBreadcrumbPolyline = new Polyline();
                            mBreadcrumbPolyline.setColor(Color.BLUE);
                            mBreadcrumbPolyline.setWidth(6.0f);
                            mMapView.getOverlays().add(mBreadcrumbPolyline);
                        }
                        mBreadcrumbPolyline.setPoints(mMyPath);

                        // Upload the local breadcrumb point to Firebase so the receiver can draw it!
                        if (mRoomRef != null) {
                            String ptKey = mRoomRef.child(myId + "_path").push().getKey();
                            if (ptKey != null) {
                                mRoomRef.child(myId + "_path").child(ptKey).setValue(new UserLocationModel(myId, lat, lon, alt));
                            }
                        }
                    }

                    // 3. ALWAYS update local marker position smoothly (Uses ic_location - blue GPS tag!)
                    if (mMyMarker == null) {
                        mMyMarker = new Marker(mMapView);
                        mMyMarker.setIcon(getResources().getDrawable(R.drawable.ic_location, null));
                        mMyMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                        mMyMarker.setInfoWindow(null);
                        mMyMarker.setOnMarkerClickListener((marker, mapView1) -> true);
                        mMapView.getOverlays().add(mMyMarker);
                        mController.animateTo(myPos);
                    }
                    mMyMarker.setPosition(myPos);
                    mMapView.invalidate();

                    // 4. ALWAYS upload current live coordinate to Firebase (fixes frozen distance!)
                    UserLocationModel userLoc = new UserLocationModel(myId, lat, lon, alt);
                    if (mRoomRef != null) {
                        mRoomRef.child(myId).setValue(userLoc);
                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "LIVE LOCATION PUBLISHED: Lat=" + lat + ", Lon=" + lon + ", Alt=" + alt);
                    }

                    // 5. Update UI distance and directions locally if friend marker exists
                    if (mFriendMarker != null) {
                        updateDistanceDisplay(mFriendMarker.getPosition().getLatitude(), mFriendMarker.getPosition().getLongitude());
                        updateNavigationGuidance(location, mFriendMarker.getPosition(), mFriendLastAltitude);
                    }
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
                    UserLocationModel friendLoc = friendSnapshot.getValue(UserLocationModel.class);
                    if (friendLoc != null) {
                        double fLat = friendLoc.getLatitude();
                        double fLon = friendLoc.getLongitude();
                        double fAlt = friendLoc.getAltitude();
                        GeoPoint friendPos = new GeoPoint(fLat, fLon);
                        mFriendLastAltitude = fAlt;
                        mHasFriendAltitude = (mFriendLastAltitude != 0.0);

                        com.viaro.utils.LogReporter.log(MeetMapActivity.this, "FRIEND UPDATE RECEIVED: Lat=" + fLat + ", Lon=" + fLon + ", Alt=" + fAlt);

                        // Render friend's marker on map (Uses custom_marker - red GPS tag!)
                        if (mFriendMarker == null) {
                            mFriendMarker = new Marker(mMapView);
                            mFriendMarker.setIcon(getResources().getDrawable(R.drawable.custom_marker, null));
                            mFriendMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                            mFriendMarker.setInfoWindow(null);
                            mFriendMarker.setOnMarkerClickListener((marker, mapView) -> true);
                            mMapView.getOverlays().add(mFriendMarker);

                            // If local user has no fix yet, animate map to friend's position so it is visible!
                            if (mMyMarker == null) {
                                mController.animateTo(friendPos);
                            }
                        }
                        mFriendMarker.setPosition(friendPos);
                        mMapView.invalidate();

                        // Compute distance even if local fix hasn't registered a full marker yet
                        updateDistanceDisplay(fLat, fLon);

                        // Dynamic guidance update when friend moves
                        if (mMyMarker != null) {
                            Location myMockLoc = new Location("gps");
                            myMockLoc.setLatitude(mMyMarker.getPosition().getLatitude());
                            myMockLoc.setLongitude(mMyMarker.getPosition().getLongitude());
                            if (mHasMyAltitude) {
                                myMockLoc.setAltitude(mMyLastAltitude);
                            }
                            updateNavigationGuidance(myMockLoc, friendPos, mFriendLastAltitude);
                        }
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMapView != null) mMapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        endMeetup();
        if (mMapView != null) mMapView.onDetach();
    }
}
