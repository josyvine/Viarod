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
                    // 1. Accuracy and Age Filter (Glitch 1)
                    if (location.hasAccuracy() && location.getAccuracy() > 30.0f) {
                        continue; // Skip inaccurate fixes
                    }
                    long ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L;
                    if (ageMs > 15000L) {
                        continue; // Skip stale location updates
                    }

                    GeoPoint myPos = new GeoPoint(location.getLatitude(), location.getLongitude());

                    // 2. Displacement/Drift Filter (Glitch 3)
                    if (!mMyPath.isEmpty()) {
                        GeoPoint lastPoint = mMyPath.get(mMyPath.size() - 1);
                        float[] results = new float[1];
                        Location.distanceBetween(
                            lastPoint.getLatitude(), lastPoint.getLongitude(),
                            location.getLatitude(), location.getLongitude(),
                            results
                        );
                        if (results[0] < 4.0f) {
                            // Suppress uploading/appending drift, but update local UI / directions smoothly
                            if (mMyMarker != null) {
                                mMyMarker.setPosition(myPos);
                                if (mFriendMarker != null) {
                                    updateNavigationGuidance(location, mFriendMarker.getPosition(), mFriendLastAltitude);
                                }
                            }
                            continue;
                        }
                    }

                    mMyPath.add(myPos);

                    // Update my marker on map
                    if (mMyMarker == null) {
                        mMyMarker = new Marker(mMapView);
                        mMyMarker.setIcon(getResources().getDrawable(R.drawable.ic_person, null));
                        mMyMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                        mMyMarker.setInfoWindow(null);
                        mMyMarker.setOnMarkerClickListener((marker, mapView1) -> true);
                        mMapView.getOverlays().add(mMyMarker);
                        mController.animateTo(myPos);
                    }
                    mMyMarker.setPosition(myPos);

                    // Draw breadcrumb trailing line
                    if (mBreadcrumbPolyline == null) {
                        mBreadcrumbPolyline = new Polyline();
                        mBreadcrumbPolyline.setColor(Color.BLUE);
                        mBreadcrumbPolyline.setWidth(6.0f);
                        mMapView.getOverlays().add(mBreadcrumbPolyline);
                    }
                    mBreadcrumbPolyline.setPoints(mMyPath);

                    mMapView.invalidate();

                    // Push coordinates and altitude to Firebase Room node
                    double myAlt = location.hasAltitude() ? location.getAltitude() : 0.0;
                    UserLocationModel userLoc = new UserLocationModel(myId, location.getLatitude(), location.getLongitude(), myAlt);
                    if (mRoomRef != null) {
                        mRoomRef.child(myId).setValue(userLoc);
                    }

                    // Update directions if friend's marker exists
                    if (mFriendMarker != null) {
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
                        GeoPoint friendPos = new GeoPoint(friendLoc.getLatitude(), friendLoc.getLongitude());
                        mFriendLastAltitude = friendLoc.getAltitude();
                        mHasFriendAltitude = (mFriendLastAltitude != 0.0);

                        // Render friend's marker on map
                        if (mFriendMarker == null) {
                            mFriendMarker = new Marker(mMapView);
                            mFriendMarker.setIcon(getResources().getDrawable(R.drawable.custom_marker, null));
                            mFriendMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                            mFriendMarker.setInfoWindow(null);
                            mFriendMarker.setOnMarkerClickListener((marker, mapView) -> true);
                            mMapView.getOverlays().add(mFriendMarker);
                        }
                        mFriendMarker.setPosition(friendPos);
                        mMapView.invalidate();

                        // Compute great-circle distance
                        if (mMyMarker != null) {
                            float[] results = new float[1];
                            Location.distanceBetween(
                                mMyMarker.getPosition().getLatitude(), mMyMarker.getPosition().getLongitude(),
                                friendPos.getLatitude(), friendPos.getLongitude(),
                                results
                            );
                            float distanceMeters = results[0];
                            if (distanceMeters >= 1000) {
                                tvDistance.setText(String.format("Distance: %.2f km", distanceMeters / 1000.0));
                            } else {
                                tvDistance.setText(String.format("Distance: %.0f meters", distanceMeters));
                            }

                            // Dynamic guidance update when friend moves
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
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
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

        tvGuidance.setText("Directions: " + turnInstruction + elevationInfo);
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
