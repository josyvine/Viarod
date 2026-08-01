package com.viaro.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

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
import com.viaro.utils.SpatialContextManager;
import com.vineyard.viaro.app.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

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
    private final List<Marker> mSearchPOIMarkers = new ArrayList<>(); // Dedicated isolated search layers

    // Real-Time Simulated Drive Components
    private Marker mSimulatedCarMarker;
    private List<GeoPoint> mCurrentRoutePoints;
    private int mCurrentRouteIndex = 0;
    private GeoPoint mSimulatedCarPosition;
    private double mSimulationSpeedMs = 0.0;
    private boolean mIsSimulationRunning = false;
    private final Handler mSimulationHandler = new Handler(Looper.getMainLooper());
    private Runnable mSimulationRunnable;

    // Proximity geofencing & Visited locks for spoken alerts [1]
    private final Set<String> mTriggeredWaypointIds = new HashSet<>();

    // Hardware GPS & Location Services
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationListener mNativeLocationListener;
    private Location mCurrentLocation;
    private long mLastLocationUpdateTimeMs = 0;

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
        mMapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);

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

        // 3. Attach Map Single-Tap Event Receiver to forward coordinate taps straight to Javascript [1.2]
        MapEventsReceiver mapTapReceiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript(String.format(Locale.US,
                                "if(window.onMapTapped){ window.onMapTapped(%f, %f); }",
                                p.getLatitude(), p.getLongitude()
                        ), null);
                    }
                });
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };
        MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(mapTapReceiver);
        mMapView.getOverlays().add(mapEventsOverlay);

        // 4. Initialize Embedded WebView for map_assistance.html
        mWebView = findViewById(R.id.webview_map_assistance);
        configureWebView();

        // 5. Initialize Hardware Compass Sensors
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        // 6. Initialize Hardware GPS Location Services
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

        // Inline WebViewAssetLoader for virtual HTTPS asset routing
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        // WebChromeClient to grant WebRTC audio capture permissions
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                        Log.d(TAG, "WebRTC Permission Granted for Gemini Live Audio Capture");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to grant WebRTC audio permission: " + e.getMessage());
                        request.deny();
                    }
                });
            }
        });

        // GLITCH FIX: GestureDetector to intercept double-taps on the transparent WebView and clear overlays
        final GestureDetector doubleTapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                runOnUiThread(() -> mWebView.evaluateJavascript("if(window.onDisplayDoubleTapped){ window.onDisplayDoubleTapped(); }", null));
                return true;
            }
        });

        // Forward standard touch and zoom gestures from WebView straight to OSMDroid MapView
        mWebView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                doubleTapDetector.onTouchEvent(event);
                mMapView.dispatchTouchEvent(event);
                return false;
            }
        });

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
        mLastLocationUpdateTimeMs = System.currentTimeMillis();
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        GeoPoint currentPos = new GeoPoint(lat, lng);

        // Update Bridge memory
        if (mBridge != null) {
            mBridge.updateLocation(location);
        }

        // User Location Marker with OnMarkerClickListener Popup
        if (mUserLocationMarker == null) {
            mUserLocationMarker = new Marker(mMapView);
            mUserLocationMarker.setIcon(getResources().getDrawable(R.drawable.ic_location, null));
            mUserLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mUserLocationMarker.setTitle("Your Location");
            mUserLocationMarker.setInfoWindow(null); // Disable default popup balloon

            // Attach marker click listener to show exact location details dialog
            mUserLocationMarker.setOnMarkerClickListener((marker, mapView) -> {
                showLocationDetailsDialog(
                        "Your GPS Position",
                        marker.getPosition(),
                        mCurrentLocation != null && mCurrentLocation.hasSpeed() ? mCurrentLocation.getSpeed() : 0.0f,
                        mCurrentCompassHeading,
                        mCurrentLocation != null && mCurrentLocation.hasAltitude() ? mCurrentLocation.getAltitude() : 0.0,
                        mCurrentLocation != null && mCurrentLocation.hasAccuracy() ? mCurrentLocation.getAccuracy() : 10.0f,
                        mLastLocationUpdateTimeMs
                );
                return true;
            });

            mMapView.getOverlays().add(mUserLocationMarker);
            mUserLocationMarker.setPosition(currentPos);
            mController.animateTo(currentPos);
        } else {
            // Do not glide user location if simulation drive is active to avoid camera jump conflicts
            if (!mIsSimulationRunning) {
                MapUtils.glideMarker(mUserLocationMarker, currentPos, 1000);
            }
        }

        if (location.hasBearing() && !mIsSimulationRunning) {
            mUserLocationMarker.setRotation(360.0f - location.getBearing());
        }

        mMapView.invalidate();

        // Update waypoint markers if an active route is being followed
        if (mTargetDestination != null && !mIsSimulationRunning) {
            checkAndFilterWaypointsProximity(currentPos);
        }
    }

    /**
     * Shows a native location details dialog when clicking on the GPS location marker.
     */
    private void showLocationDetailsDialog(
            String displayTitle,
            GeoPoint pos,
            float speedMs,
            float headingDeg,
            double altitude,
            float accuracyMeters,
            long lastUpdateMs
    ) {
        if (isFinishing() || isDestroyed()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0F172A"));
        int paddingPx = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView logoIv = new ImageView(this);
        int logoSize = (int) (38 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
        logoIv.setLayoutParams(logoParams);
        logoIv.setImageResource(R.drawable.viaro);

        TextView titleTv = new TextView(this);
        titleTv.setText(displayTitle);
        titleTv.setTextSize(17f);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setTextColor(Color.parseColor("#FFFFFF"));

        header.addView(logoIv);
        header.addView(titleTv);
        layout.addView(header);

        TextView detailsTv = new TextView(this);
        detailsTv.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        detailsTv.setTextSize(14f);
        detailsTv.setTextColor(Color.parseColor("#F8FAFC"));

        double lat = pos != null ? pos.getLatitude() : 0.0;
        double lon = pos != null ? pos.getLongitude() : 0.0;
        double speedKmh = speedMs * 3.6;
        long timeAgoSec = lastUpdateMs > 0 ? Math.max(0, (System.currentTimeMillis() - lastUpdateMs) / 1000) : 0;
        String timeText = lastUpdateMs > 0 ? (timeAgoSec + "s ago") : "Just now";

        String initialText = String.format(
                Locale.US,
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

        AlertDialog dialog = builder.create();
        dialog.show();

        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#38BDF8"));
        }

        // Reverse Geocoding in background thread
        Executors.newSingleThreadExecutor().execute(() -> {
            String addressName = "Location Address";
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
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
                addressName = String.format(Locale.US, "Area near %.4f, %.4f", lat, lon);
            }

            final String finalAddress = addressName;
            runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    String updatedText = String.format(
                            Locale.US,
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

                if (mUserLocationMarker != null && !mIsSimulationRunning && (mCurrentLocation == null || !mCurrentLocation.hasSpeed() || mCurrentLocation.getSpeed() < 0.5f)) {
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

    /* --- REAL-TIME TOY CAR DRIVE SIMULATION ENGINE --- */

    /**
     * JS BRIDGE ENDPOINT: Initiates the live driving simulation loop at the specified speed (5 to 80 km/h) [1].
     */
    public void startDriveSimulation(final double speedKmh) {
        if (mActiveRoutePolyline == null) {
            Toast.makeText(this, "No active route to simulate.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Convert speed from km/h to meters per second [1]
        mSimulationSpeedMs = (speedKmh * 1000.0) / 3600.0; // 5km/h = ~1.39m/s, 80km/h = ~22.2m/s [1]
        mCurrentRoutePoints = mActiveRoutePolyline.getActualPoints();

        if (mCurrentRoutePoints == null || mCurrentRoutePoints.size() < 2) return;

        if (mIsSimulationRunning) {
            // Simply update speed parameter in real-time [1]
            mSimulationSpeedMs = (speedKmh * 1000.0) / 3600.0;
            return;
        }

        mIsSimulationRunning = true;
        mCurrentRouteIndex = 0;
        mSimulatedCarPosition = mCurrentRoutePoints.get(0);

        // Hide standard user marker and show simulated toy car marker
        if (mUserLocationMarker != null) {
            mUserLocationMarker.setVisible(false);
        }

        if (mSimulatedCarMarker == null) {
            mSimulatedCarMarker = new Marker(mMapView);
            mSimulatedCarMarker.setIcon(getResources().getDrawable(R.drawable.custom_marker, null));
            mSimulatedCarMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            mSimulatedCarMarker.setInfoWindow(null);
            mMapView.getOverlays().add(mSimulatedCarMarker);
        }
        mSimulatedCarMarker.setVisible(true);
        mSimulatedCarMarker.setPosition(mSimulatedCarPosition);
        mMapView.invalidate();

        mSimulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mIsSimulationRunning || mCurrentRoutePoints == null || mCurrentRouteIndex >= mCurrentRoutePoints.size() - 1) {
                    stopDriveSimulation();
                    return;
                }

                // Dynamic Zoom-Compensated Velocity Algorithm to make speed feel realistic on zoom out [1]
                double currentZoom = mMapView.getZoomLevelDouble();
                double zoomDifference = 17.5 - currentZoom;
                double velocityMultiplier = 1.0;
                if (zoomDifference > 0) {
                    velocityMultiplier = Math.pow(1.6, zoomDifference);
                }

                // Distance the car should travel in 50ms tick (incorporating dynamic multiplier) [1]
                double stepMeters = mSimulationSpeedMs * 0.05 * velocityMultiplier; 
                GeoPoint currentPt = mSimulatedCarPosition;
                GeoPoint nextPt = mCurrentRoutePoints.get(mCurrentRouteIndex + 1);

                double segmentDistance = currentPt.distanceToAsDouble(nextPt);

                while (stepMeters >= segmentDistance) {
                    stepMeters -= segmentDistance;
                    mCurrentRouteIndex++;
                    if (mCurrentRouteIndex >= mCurrentRoutePoints.size() - 1) {
                        // Reached absolute destination
                        mSimulatedCarPosition = mCurrentRoutePoints.get(mCurrentRoutePoints.size() - 1);
                        mSimulatedCarMarker.setPosition(mSimulatedCarPosition);
                        mMapView.invalidate();
                        stopDriveSimulation();
                        Toast.makeText(MapAssistanceActivity.this, "Simulated drive completed successfully!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    currentPt = mCurrentRoutePoints.get(mCurrentRouteIndex);
                    nextPt = mCurrentRoutePoints.get(mCurrentRouteIndex + 1);
                    segmentDistance = currentPt.distanceToAsDouble(nextPt);
                }

                // Interpolate exact segment coordinate [1]
                double fraction = stepMeters / segmentDistance;
                double lat = currentPt.getLatitude() + fraction * (nextPt.getLatitude() - currentPt.getLatitude());
                double lng = currentPt.getLongitude() + fraction * (nextPt.getLongitude() - currentPt.getLongitude());

                mSimulatedCarPosition = new GeoPoint(lat, lng);
                mSimulatedCarMarker.setPosition(mSimulatedCarPosition);

                // Align rotation and rotate the entire OSMDroid map to keep the car driving North [1]
                float segmentBearing = MapUtils.calculateBearing(currentPt, nextPt);
                mSimulatedCarMarker.setRotation(360.0f - segmentBearing);
                mMapView.setMapOrientation(-segmentBearing);
                mController.setCenter(mSimulatedCarPosition);

                // GEOFENCE PROXIMITY spoken alert triggers [1]
                checkAndTriggerWaypointSpokenAlerts(mSimulatedCarPosition);

                // Calculate remaining OSRM route distance [1]
                double remainingDistance = calculateRemainingRouteDistance();

                // Find nearest upcoming waypoint/bus stop along the remaining waypoints list [1]
                String nextWaypointName = "None";
                double nextWaypointDistance = 0.0;
                if (mTenMeterWaypointMarkers != null && !mTenMeterWaypointMarkers.isEmpty()) {
                    double minDistance = Double.MAX_VALUE;
                    for (Marker marker : mTenMeterWaypointMarkers) {
                        String stopName = marker.getTitle();
                        if (stopName != null && !stopName.equals("Your Location") && !stopName.equals("Target Attraction") && !mTriggeredWaypointIds.contains(stopName)) {
                            double d = mSimulatedCarPosition.distanceToAsDouble(marker.getPosition());
                            if (d < minDistance) {
                                minDistance = d;
                                nextWaypointName = stopName;
                                nextWaypointDistance = d;
                            }
                        }
                    }
                }

                // Push dynamic remaining metrics and upcoming stops directly to the glassmorphic HTML panel [1]
                final double finalDistance = remainingDistance;
                final String finalWaypointName = nextWaypointName;
                final double finalWaypointDistance = nextWaypointDistance;
                runOnUiThread(() -> {
                    if (mBridge != null) {
                        mBridge.updateSimulationProgress(finalDistance, finalWaypointName, finalWaypointDistance);
                    }
                });

                mMapView.invalidate();
                mSimulationHandler.postDelayed(this, 50); // Tick every 50ms (20 FPS)
            }
        };
        mSimulationHandler.postDelayed(mSimulationRunnable, 50);
    }

    /**
     * Calculates the exact remaining path distance in meters from the simulated car to destination.
     */
    private double calculateRemainingRouteDistance() {
        double dist = 0.0;
        if (mCurrentRoutePoints != null && mCurrentRouteIndex < mCurrentRoutePoints.size() - 1) {
            dist += mSimulatedCarPosition.distanceToAsDouble(mCurrentRoutePoints.get(mCurrentRouteIndex + 1));
            for (int i = mCurrentRouteIndex + 1; i < mCurrentRoutePoints.size() - 1; i++) {
                dist += mCurrentRoutePoints.get(i).distanceToAsDouble(mCurrentRoutePoints.get(i + 1));
            }
        }
        return dist;
    }

    /**
     * Proximity Geofencing Alerts: Checks if the car has entered a 15-meter radius of any stop [1].
     */
    private void checkAndTriggerWaypointSpokenAlerts(GeoPoint carPos) {
        for (Marker marker : mTenMeterWaypointMarkers) {
            String stopName = marker.getTitle();
            if (stopName != null && !stopName.equals("Your Location") && !stopName.equals("Target Attraction") && !mTriggeredWaypointIds.contains(stopName)) {
                double distMeters = carPos.distanceToAsDouble(marker.getPosition());
                if (distMeters < 15.0) { // 15m proximity boundary [1]
                    mTriggeredWaypointIds.add(stopName);
                    
                    // Dispatch spoken event to Gemini Live WebSocket turns [1]
                    runOnUiThread(() -> mWebView.evaluateJavascript("if(window.onReachedWaypoint){ window.onReachedWaypoint('" + stopName.replace("'", "\\'") + "'); }", null));
                    Log.d(TAG, "GEOFENCE TRIGGERED: Reached milestone: " + stopName + " | Spoken turn injected.");
                }
            }
        }
    }

    /**
     * JS BRIDGE ENDPOINT: Pauses/stops the simulated drive immediately.
     */
    public void stopDriveSimulation() {
        mIsSimulationRunning = false;
        if (mSimulationHandler != null && mSimulationRunnable != null) {
            mSimulationHandler.removeCallbacks(mSimulationRunnable);
        }
        if (mUserLocationMarker != null) {
            mUserLocationMarker.setVisible(true); // Restore user GPS pin
        }
        if (mSimulatedCarMarker != null) {
            mSimulatedCarMarker.setVisible(false); // Hide toy car
        }
        mMapView.setMapOrientation(0.0f); // Reset map orientation to North
        mMapView.invalidate();
    }

    /**
     * JS BRIDGE ENDPOINT: Wipes out the current active OSRM route, destination flag, and waypoint markers.
     */
    public void clearRouteOverlay() {
        stopDriveSimulation();
        mTriggeredWaypointIds.clear(); // Reset geofencing spoken locks [1]
        
        if (mActiveRoutePolyline != null) {
            mMapView.getOverlays().remove(mActiveRoutePolyline);
            mActiveRoutePolyline = null;
        }
        if (mDestinationMarker != null) {
            mMapView.getOverlays().remove(mDestinationMarker);
            mDestinationMarker = null;
        }
        for (Marker marker : mTenMeterWaypointMarkers) {
            mMapView.getOverlays().remove(marker);
        }
        mTenMeterWaypointMarkers.clear();

        // Safely clear search results overlays [1.2]
        for (Marker marker : mSearchPOIMarkers) {
            mMapView.getOverlays().remove(marker);
        }
        mSearchPOIMarkers.clear();

        mTargetDestination = null;
        mMapView.invalidate();
    }

    /**
     * JS BRIDGE ENDPOINT: Receives and processes a list of multiple target markers.
     * Clears old searches and plots interactive blue pins with click listeners. [1.2, 1.3]
     */
    public void plotMultipleMarkers(final String jsonMarkers) {
        runOnUiThread(() -> {
            try {
                JSONArray markersArray = new JSONArray(jsonMarkers);

                // 1. Safely remove old search markers without touching map touch listeners
                for (Marker marker : mSearchPOIMarkers) {
                    mMapView.getOverlays().remove(marker);
                }
                mSearchPOIMarkers.clear();

                // 2. Loop through and plot each place candidate [1.2, 5.1]
                for (int i = 0; i < markersArray.length(); i++) {
                    JSONObject obj = markersArray.getJSONObject(i);
                    final String name = obj.getString("name");
                    final double lat = obj.getDouble("lat");
                    final double lng = obj.getDouble("lng");
                    final String info = obj.optString("info", "No information available.");

                    Marker marker = new Marker(mMapView);
                    marker.setPosition(new GeoPoint(lat, lng)); [1.2]
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                    marker.setTitle(name); [1.2]
                    marker.setSnippet(info); // Populate popup info bubble [1.4]
                    
                    // Style using standard coordinate icon resource
                    marker.setIcon(getResources().getDrawable(R.drawable.ic_location, null));

                    // 3. Register click listener to navigate to this specific marker upon tap [1.3]
                    marker.setOnMarkerClickListener((m, mapView) -> {
                        m.showInfoWindow(); [1.4]
                        mMapView.getController().animateTo(m.getPosition());
                        
                        // Clear old polyline/waypoint markers before generating new route
                        clearRouteOverlay();
                        
                        // Fetch OSRM routing [1.1]
                        plotRouteToDestination(name, lat, lng);
                        
                        // Start simulation drive immediately [1.1]
                        startDriveSimulation(simulationSpeedKmh);
                        return true;
                    });

                    mMapView.getOverlays().add(marker); [1.2]
                    mSearchPOIMarkers.add(marker);
                }

                // 4. If only 1 place is found, automatically start route [1.1]
                if (markersArray.length() == 1) {
                    JSONObject singleObj = markersArray.getJSONObject(0);
                    String name = singleObj.getString("name");
                    double lat = singleObj.getDouble("lat");
                    double lng = singleObj.getDouble("lng");

                    plotRouteToDestination(name, lat, lng);
                    startDriveSimulation(simulationSpeedKmh);
                } else if (markersArray.length() > 1) {
                    Toast.makeText(this, "Found " + markersArray.length() + " places. Tap a pin to navigate.", Toast.LENGTH_LONG).show();
                }

                mMapView.invalidate(); [1.2]

            } catch (Exception e) {
                Log.e(TAG, "Error parsing or plotting multiple markers: " + e.getMessage());
            }
        });
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