package com.viaro.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.R;
import com.viaro.services.LocationService;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

public class DriverMapActivity extends AppCompatActivity {

    private MapView mMapView;
    private IMapController mController;
    private Marker mCurrentMarker;

    private TextView tvBusName, tvRoute, tvSpeed;
    private Button btnEndTrip;

    private String busId, busName, startPoint, endPoint;
    private LocationReceiver mLocationReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_map);

        busId = intentGet("bus_id");
        busName = intentGet("bus_name");
        startPoint = intentGet("start_point");
        endPoint = intentGet("end_point");

        mMapView = findViewById(R.id.map_driver);
        mMapView.setTileSource(TileSourceFactory.MAPNIK);
        mMapView.setMultiTouchControls(true);

        mController = mMapView.getController();
        mController.setZoom(16.5);

        tvBusName = findViewById(R.id.tv_driver_bus_name);
        tvRoute = findViewById(R.id.tv_driver_route);
        tvSpeed = findViewById(R.id.tv_driver_speed);
        btnEndTrip = findViewById(R.id.btn_end_trip);

        tvBusName.setText(busName != null ? busName : "Live Transit");
        tvRoute.setText("Route: " + (startPoint != null ? startPoint : "Start") + " ➔ " + (endPoint != null ? endPoint : "Destination"));

        btnEndTrip.setOnClickListener(v -> endTrip());

        startTrackingService();

        // Register local location receiver
        mLocationReceiver = new LocationReceiver();
        registerReceiverCompat(mLocationReceiver, new IntentFilter("viaro.LOCATION_UPDATE"));
    }

    private String intentGet(String key) {
        return getIntent() != null ? getIntent().getStringExtra(key) : "";
    }

    private void registerReceiverCompat(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void startTrackingService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        serviceIntent.putExtra("bus_id", busId);
        serviceIntent.putExtra("bus_name", busName);
        serviceIntent.putExtra("start_point", startPoint);
        serviceIntent.putExtra("end_point", endPoint);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopTrackingService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        stopService(serviceIntent);
    }

    private void endTrip() {
        stopTrackingService();
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
        if (mLocationReceiver != null) {
            unregisterReceiver(mLocationReceiver);
        }
        stopTrackingService();
        if (mMapView != null) mMapView.onDetach();
    }

    private class LocationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                double lat = intent.getDoubleExtra("latitude", 0.0);
                double lng = intent.getDoubleExtra("longitude", 0.0);
                double speed = intent.getDoubleExtra("speed", 0.0);

                tvSpeed.setText(String.format("Current Speed: %.1f km/h", speed));

                GeoPoint currentPos = new GeoPoint(lat, lng);
                mController.animateTo(currentPos);

                if (mCurrentMarker == null) {
                    mCurrentMarker = new Marker(mMapView);
                    mCurrentMarker.setIcon(getResources().getDrawable(R.drawable.custom_marker, null));
                    mCurrentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                    mMapView.getOverlays().add(mCurrentMarker);
                }
                mCurrentMarker.setPosition(currentPos);
                mMapView.invalidate();
            }
        }
    }
}
