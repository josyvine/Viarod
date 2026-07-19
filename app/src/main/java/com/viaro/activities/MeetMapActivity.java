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

    private TextView tvStatus, tvDistance;

    private String roomCode, role, myId, friendId;
    private DatabaseReference mRoomRef;
    private ValueEventListener mRoomListener;

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;

    private final ArrayList<GeoPoint> mMyPath = new ArrayList<>();

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
                    GeoPoint myPos = new GeoPoint(location.getLatitude(), location.getLongitude());
                    mMyPath.add(myPos);

                    // Update my marker on map
                    if (mMyMarker == null) {
                        mMyMarker = new Marker(mMapView);
                        mMyMarker.setIcon(getResources().getDrawable(R.drawable.ic_person, null));
                        mMyMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
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

                    // Push coordinates to Firebase Room node
                    UserLocationModel userLoc = new UserLocationModel(myId, location.getLatitude(), location.getLongitude());
                    if (mRoomRef != null) {
                        mRoomRef.child(myId).setValue(userLoc);
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

                        // Render friend's marker on map
                        if (mFriendMarker == null) {
                            mFriendMarker = new Marker(mMapView);
                            mFriendMarker.setIcon(getResources().getDrawable(R.drawable.custom_marker, null));
                            mFriendMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
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
