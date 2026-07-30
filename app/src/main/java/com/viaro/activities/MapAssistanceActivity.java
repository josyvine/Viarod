package com.viaro.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.viaro.bridge.MapAssistanceBridge;
import com.viaro.models.RouteResponse;
import com.viaro.network.ApiClient;
import com.viaro.utils.AppConstants;
import com.viaro.utils.LocationHelper;
import com.viaro.utils.MapUtils;
import com.viaro.utils.PuterWebViewClient;
import com.viaro.utils.SpatialContextManager;
import com.vineyard.viaro.app.R;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MapAssistanceActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "MapAssistanceActivity";

    // OSMDroid Map Components
    private MapView mMapView;
    private IMapController mController;
    private Marker mUserLocationMarker;
    private Marker mDestinationMarker;
    private Polyline mActiveRoutePolyline;
    private final List<Marker> mTenMeterWaypointMarkers = new ArrayList<>();

    // Hardware GPS & Location Services
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationListener mNativeLocationListener;
    private Location mCurrentLocation;

    // Hardware Compass & Orientation Sensors
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private Sensor mMagnetometer;
    private final float[] mGravity = new float[3];
    private final float[] mGeomagnetic = new float[3];
    private boolean mHasGravity = false;
    private boolean mHasGeomagnetic = false;
    private float mCurrentCompassHeading = 0.0f;

    // Embedded WebView & JavaScript Bridge
    private WebView mWebView;
    private MapAssistanceBridge mBridge;

    // Navigation State
    private GeoPoint mTargetDestination;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_assistance);

        // 1. Initialize Full-Screen OSMDroid Map
        mMapView = findViewById(R.id.map_assistance_view);
        mMapView.setTileSource(TileSourceFactory.MAPNIK);
        mMapView.setMultiTouchControls(true);

        mController = mMapView.getController();
        mController.setZoom(17.0);

        // Default initial center fallback
        GeoPoint defaultPoint = new GeoPoint(12.9716, 77.5946);
        mController.setCenter(defaultPoint);

        // 2. Setup Native Floating Map Controls
        ImageButton btnGps = findViewById(R.id.btn_recenter_gps);
        ImageButton btnCompass = findViewById(R.id.btn_reset_compass);

        btnGps.setOnClickListener(v -> recenterOnUserLocation());
        btnCompass.setOnClickListener(v -> resetCompassOrientation());

        // 3. Initialize Embedded WebView for map_assistance.html
        mWebView = findViewById(R.id.webview_map_assistance);
        configureWebView();

        // 4. Initialize Hardware Compass Sensors
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        // 5. Initialize Hardware GPS Location Services
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationUpdates();
    }

    private void configureWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Enable Cross-Origin & Secure Origin asset loading
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // Bridge setup
        mBridge = new MapAssistanceBridge(this, mWebView, this);
        mWebView.addJavascriptInterface(mBridge, AppConstants.JS_BRIDGE_NAME);

        // Attach custom WebViewClient for virtual HTTPS asset routing
        mWebView.setWebViewClient(new PuterWebViewClient(this));

        // Make background transparent so map is visible
        mWebView.setBackgroundColor(Color.TRANSPARENT);
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Load map_assistance.html over virtual HTTPS origin
        mWebView.loadUrl("https://appassets.androidplatform.net/assets/map_assistance.html");
    }

    private void setupLocationUpdates() {
        mNativeLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                onNewLocationReceived(location);
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
                    onNewLocationReceived(location);
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

        // Immediate last known location
        Location lastLoc = LocationHelper.getLastKnownLocation(this, mFusedLocationClient);
        if (lastLoc != null) {
            onNewLocationReceived(lastLoc);
        }
    }

    private void onNewLocationReceived(Location location) {
        if (location == null) return;

        mCurrentLocation = location;
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        GeoPoint currentPos = new GeoPoint(lat, lng);

        // Update Bridge memory
        if (mBridge != null) {
            mBridge.updateLocation(location);
        }

        // Update User Location Marker on Map
        if (mUserLocationMarker == null) {
            mUserLocationMarker = new Marker(mMapView);
            mUserLocationMarker.setIcon(getResources().getDrawable(R.drawable.ic_location, null));
            mUserLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mUserLocationMarker.setTitle("Your Location");
            mMapView.getOverlays().add(mUserLocationMarker);
            mUserLocationMarker.setPosition(currentPos);
            mController.animateTo(currentPos);
        } else {
            MapUtils.glideMarker(mUserLocationMarker, currentPos, 1000);
        }

        if (location.hasBearing()) {
            mUserLocationMarker.setRotation(360.0f - location.getBearing());
        }

        mMapView.invalidate();

        // Update waypoint markers if an active route is being followed
        if (mTargetDestination != null) {
            checkAndFilterWaypointsProximity(currentPos);
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
                    headingDegrees += 360.0f;
                }

                mCurrentCompassHeading = smoothHeading(mCurrentCompassHeading, headingDegrees);
                String cardinalDir = SpatialContextManager.getCardinalDirection(mCurrentCompassHeading);

                if (mBridge != null) {
                    mBridge.updateCompass(mCurrentCompassHeading, cardinalDir);
                }

                if (mUserLocationMarker != null && (mCurrentLocation == null || !mCurrentLocation.hasSpeed() || mCurrentLocation.getSpeed() < 0.5f)) {
                    mUserLocationMarker.setRotation(360.0f - mCurrentCompassHeading);
                    mMapView.invalidate();
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private float smoothHeading(float current, float target) {
        float diff = target - current;
        while (diff < -180.0f) diff += 360.0f;
        while (diff > 180.0f) diff -= 360.0f;
        return (current + 0.15f * diff + 360.0f) % 360.0f;
    }

    private void recenterOnUserLocation() {
        if (mCurrentLocation != null) {
            GeoPoint pos = new GeoPoint(mCurrentLocation.getLatitude(), mCurrentLocation.getLongitude());
            mController.animateTo(pos);
            mController.setZoom(17.5);
            Toast.makeText(this, "Centered on your location", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Acquiring GPS fix...", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetCompassOrientation() {
        mMapView.setMapOrientation(0.0f);
        Toast.makeText(this, "Map oriented to North", Toast.LENGTH_SHORT).show();
    }

    /**
     * Called by MapAssistanceBridge when Gemini or the user selects a route destination.
     * Fetches OSRM route geometry and plots 10-meter interpolated markers along the road.
     */
    public void plotRouteToDestination(String destinationName, double targetLat, double targetLng) {
        if (mCurrentLocation == null) {
            Toast.makeText(this, "GPS position not ready for routing.", Toast.LENGTH_SHORT).show();
            return;
        }

        mTargetDestination = new GeoPoint(targetLat, targetLng);
        String coordinates = mCurrentLocation.getLongitude() + "," + mCurrentLocation.getLatitude() + ";" + targetLng + "," + targetLat;

        ApiClient.getOSRMApiService().getRoute(coordinates, "full", "polyline").enqueue(new Callback<RouteResponse>() {
            @Override
            public void onResponse(Call<RouteResponse> call, Response<RouteResponse> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().getRoutes().isEmpty()) {
                    RouteResponse.Route route = response.body().getRoutes().get(0);
                    String geometry = route.getGeometry();
                    List<GeoPoint> routePolyline = MapUtils.decodePolyline(geometry);

                    drawRoutePolylineAnd10MeterMarkers(routePolyline, destinationName);
                } else {
                    Toast.makeText(MapAssistanceActivity.this, "Route not found to destination.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<RouteResponse> call, Throwable t) {
                Toast.makeText(MapAssistanceActivity.this, "OSRM Routing API Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void drawRoutePolylineAnd10MeterMarkers(List<GeoPoint> polylinePoints, String destinationName) {
        // Clear previous route overlays
        if (mActiveRoutePolyline != null) {
            mMapView.getOverlays().remove(mActiveRoutePolyline);
        }
        for (Marker marker : mTenMeterWaypointMarkers) {
            mMapView.getOverlays().remove(marker);
        }
        mTenMeterWaypointMarkers.clear();

        // 1. Draw main route line
        mActiveRoutePolyline = new Polyline();
        mActiveRoutePolyline.setPoints(polylinePoints);
        mActiveRoutePolyline.setColor(Color.parseColor("#0284C7"));
        mActiveRoutePolyline.setWidth(10.0f);
        mMapView.getOverlays().add(mActiveRoutePolyline);

        // 2. Add Destination Pin
        GeoPoint destPoint = polylinePoints.get(polylinePoints.size() - 1);
        if (mDestinationMarker != null) {
            mMapView.getOverlays().remove(mDestinationMarker);
        }
        mDestinationMarker = new Marker(mMapView);
        mDestinationMarker.setPosition(destPoint);
        mDestinationMarker.setIcon(getResources().getDrawable(R.drawable.custom_marker, null));
        mDestinationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mDestinationMarker.setTitle(destinationName);
        mMapView.getOverlays().add(mDestinationMarker);

        // 3. Interpolate waypoints every 10 meters along the route
        List<GeoPoint> tenMeterPoints = SpatialContextManager.interpolatePointsEvery10Meters(polylinePoints);

        for (GeoPoint pt : tenMeterPoints) {
            Marker waypointMarker = new Marker(mMapView);
            waypointMarker.setPosition(pt);
            waypointMarker.setIcon(getResources().getDrawable(R.drawable.ic_location, null));
            waypointMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            waypointMarker.setInfoWindow(null);
            mMapView.getOverlays().add(waypointMarker);
            mTenMeterWaypointMarkers.add(waypointMarker);
        }

        mMapView.invalidate();
        mController.animateTo(destPoint);
        Toast.makeText(this, "Plotted 10m markers to " + destinationName, Toast.LENGTH_LONG).show();
    }

    private void checkAndFilterWaypointsProximity(GeoPoint userPos) {
        List<Marker> reachedMarkers = new ArrayList<>();
        for (Marker marker : mTenMeterWaypointMarkers) {
            GeoPoint pt = marker.getPosition();
            double distMeters = userPos.distanceToAsDouble(pt);
            if (distMeters < 12.0) { // Clear markers reached by user
                reachedMarkers.add(marker);
            }
        }
        for (Marker reached : reachedMarkers) {
            mMapView.getOverlays().remove(reached);
            mTenMeterWaypointMarkers.remove(reached);
        }
        if (!reachedMarkers.isEmpty()) {
            mMapView.invalidate();
        }
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
        LocationHelper.stopDualEngineLocationUpdates(
                this,
                mFusedLocationClient,
                mLocationCallback,
                mNativeLocationListener
        );
        if (mBridge != null) {
            mBridge.destroy();
        }
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.destroy();
        }
        if (mMapView != null) mMapView.onDetach();
    }
}