package com.viaro.bridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.viaro.activities.MapAssistanceActivity;
import com.viaro.utils.AppConstants;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapAssistanceBridge {

    private static final String TAG = "MapAssistanceBridge";

    private final Context mContext;
    private final WebView mWebView;
    private final MapAssistanceActivity mActivity;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;
    private final ExecutorService mGeocoderExecutor;

    // Memory Cache for Hardware GPS, Address, and Compass Data
    private double mCurrentLat = 0.0;
    private double mCurrentLng = 0.0;
    private double mCurrentSpeed = 0.0;
    private double mCurrentAccuracy = 999.0;
    private String mCurrentPlaceName = "Acquiring location name...";

    private float mCompassHeadingDegrees = 0.0f;
    private String mCompassCardinalDirection = "NORTH";

    // Memory Cache for Real-Time Toy Car Simulation Progress [1]
    private double mRemainingDistanceMeters = 0.0;
    private String mNextWaypointName = "None";
    private double mNextWaypointDistanceMeters = 0.0;

    public MapAssistanceBridge(Context context, WebView webView, MapAssistanceActivity activity) {
        this.mContext = context;
        this.mWebView = webView;
        this.mActivity = activity;
        this.mHandler = new Handler(Looper.getMainLooper());
        this.mGeocoderExecutor = Executors.newSingleThreadExecutor();
        this.mPrefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Called by MapAssistanceActivity whenever new hardware GPS fix arrives.
     * Performs background reverse-geocoding to resolve exact place name (town/city/street).
     */
    public void updateLocation(Location location) {
        if (location != null) {
            double newLat = location.getLatitude();
            double newLng = location.getLongitude();

            // Check if coordinates shifted significantly to warrant background reverse-geocoding
            boolean shouldGeocode = (mCurrentLat == 0.0 && mCurrentLng == 0.0) ||
                    (Math.abs(newLat - mCurrentLat) > 0.0001 || Math.abs(newLng - mCurrentLng) > 0.0001);

            this.mCurrentLat = newLat;
            this.mCurrentLng = newLng;
            this.mCurrentSpeed = location.hasSpeed() ? (location.getSpeed() * 3.6) : 0.0; // Convert m/s to km/h
            this.mCurrentAccuracy = location.hasAccuracy() ? location.getAccuracy() : 50.0;

            if (shouldGeocode) {
                resolvePlaceNameInBackground(newLat, newLng);
            }
        }
    }

    private void resolvePlaceNameInBackground(final double lat, final double lng) {
        mGeocoderExecutor.execute(() -> {
            String resolvedAddress = "Area near " + String.format(Locale.US, "%.4f, %.4f", lat, lng);
            try {
                Geocoder geocoder = new Geocoder(mContext, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    StringBuilder sb = new StringBuilder();

                    if (addr.getLocality() != null && !addr.getLocality().isEmpty()) {
                        sb.append(addr.getLocality());
                    } else if (addr.getSubAdminArea() != null && !addr.getSubAdminArea().isEmpty()) {
                        sb.append(addr.getSubAdminArea());
                    }

                    if (addr.getThoroughfare() != null && !addr.getThoroughfare().isEmpty()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(addr.getThoroughfare());
                    }

                    if (addr.getAdminArea() != null && !addr.getAdminArea().isEmpty()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(addr.getAdminArea());
                    }

                    if (sb.length() > 0) {
                        resolvedAddress = sb.toString();
                    } else if (addr.getMaxAddressLineIndex() >= 0) {
                        resolvedAddress = addr.getAddressLine(0);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Geocoder reverse lookup exception: " + e.getMessage());
            }

            final String finalPlace = resolvedAddress;
            mHandler.post(() -> mCurrentPlaceName = finalPlace);
        });
    }

    /**
     * Called by MapAssistanceActivity whenever new compass sensor orientation arrives.
     */
    public void updateCompass(float degrees, String cardinalDirection) {
        this.mCompassHeadingDegrees = degrees;
        this.mCompassCardinalDirection = cardinalDirection;
    }

    /**
     * Called by MapAssistanceActivity during active driving simulation ticks [1].
     * Dynamically pushes simulation parameters to Javascript and caches them locally [1].
     */
    public void updateSimulationProgress(final double remainingDistance, final String nextWaypoint, final double nextWaypointDistance) {
        this.mRemainingDistanceMeters = remainingDistance;
        this.mNextWaypointName = nextWaypoint != null ? nextWaypoint : "None";
        this.mNextWaypointDistanceMeters = nextWaypointDistance;

        mHandler.post(() -> {
            String escapedWaypoint = mNextWaypointName.replace("'", "\\'");
            String jsCall = String.format(Locale.US,
                    "if(window.updateSimulationProgress){ window.updateSimulationProgress(%f, '%s', %f); }",
                    remainingDistance, escapedWaypoint, nextWaypointDistance
            );
            mWebView.evaluateJavascript(jsCall, null);
        });
    }

    // --- JS INTERFACE METHODS EXPOSED TO map_assistance.html ---

    /**
     * JS INTERFACE: CORS-bypassing synchronous HTTP HEAD request targeting 
     * Google's maps?cid= redirection. Returns the evaluated 'Location' header value.
     */
    @JavascriptInterface
    public String getCoordinatesFromCid(final String cid) {
        try {
            URL url = new URL("https://maps.google.com/?cid=" + cid);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");

            String location = conn.getHeaderField("Location");
            return location == null ? "" : location;
        } catch (Exception e) {
            Log.e(TAG, "Error expanding Google Maps CID redirection synchronously: " + e.getMessage());
            return "";
        }
    }

    /**
     * JS INTERFACE: Receives a parsed JSON array string containing multiple nearby POI targets.
     * Marshals execution over to MapAssistanceActivity on the main UI thread.
     */
    @JavascriptInterface
    public void plotMultipleMarkers(final String jsonMarkers) {
        mHandler.post(() -> {
            if (mActivity != null) {
                mActivity.plotMultipleMarkers(jsonMarkers);
            }
        });
    }

    @JavascriptInterface
    public String getGpsLocation() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("lat", mCurrentLat);
            obj.put("lng", mCurrentLng);
            obj.put("speed", mCurrentSpeed);
            obj.put("accuracy", mCurrentAccuracy);
            obj.put("placeName", mCurrentPlaceName); // ENRICHED: Human-readable town/street/district
        } catch (Exception e) {
            Log.e(TAG, "Error packaging GPS JSON", e);
        }
        return obj.toString();
    }

    @JavascriptInterface
    public String getCompassHeading() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("degrees", mCompassHeadingDegrees);
            obj.put("direction", mCompassCardinalDirection);
        } catch (Exception e) {
            Log.e(TAG, "Error packaging Compass JSON", e);
        }
        return obj.toString();
    }

    /**
     * JS INTERFACE: Pulls active toy car driving simulation statistics on demand [1].
     */
    @JavascriptInterface
    public String getSimulationProgress() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("remainingDistance", mRemainingDistanceMeters);
            obj.put("nextWaypoint", mNextWaypointName);
            obj.put("nextWaypointDistance", mNextWaypointDistanceMeters);
        } catch (Exception e) {
            Log.e(TAG, "Error packaging simulation progress JSON", e);
        }
        return obj.toString();
    }

    @JavascriptInterface
    public String getGeminiApiKey() {
        return mPrefs.getString(AppConstants.PREF_GEMINI_API_KEY, "");
    }

    @JavascriptInterface
    public void saveGeminiApiKey(String apiKey) {
        mPrefs.edit().putString(AppConstants.PREF_GEMINI_API_KEY, apiKey != null ? apiKey.trim() : "").apply();
    }

    @JavascriptInterface
    public String getGeminiModel() {
        return mPrefs.getString(AppConstants.PREF_GEMINI_MODEL, "gemini-3.1-flash-live-preview");
    }

    @JavascriptInterface
    public void saveGeminiModel(String model) {
        mPrefs.edit().putString(AppConstants.PREF_GEMINI_MODEL, model != null ? model.trim() : "gemini-3.1-flash-live-preview").apply();
    }

    @JavascriptInterface
    public String getGeminiVoice() {
        return mPrefs.getString(AppConstants.PREF_GEMINI_VOICE, "Puck");
    }

    @JavascriptInterface
    public void saveGeminiVoice(String voice) {
        mPrefs.edit().putString(AppConstants.PREF_GEMINI_VOICE, voice != null ? voice.trim() : "Puck").apply();
    }

    @JavascriptInterface
    public boolean getMapGroundingEnabled() {
        return mPrefs.getBoolean(AppConstants.PREF_MAP_GROUNDING, true);
    }

    @JavascriptInterface
    public void saveMapGroundingEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(AppConstants.PREF_MAP_GROUNDING, enabled).apply();
    }

    @JavascriptInterface
    public boolean getSearchGroundingEnabled() {
        return mPrefs.getBoolean(AppConstants.PREF_SEARCH_GROUNDING, true);
    }

    @JavascriptInterface
    public void saveSearchGroundingEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(AppConstants.PREF_SEARCH_GROUNDING, enabled).apply();
    }

    /**
     * Triggered by Gemini JS when Gemini identifies a location destination to navigate towards.
     * Tells MapAssistanceActivity on the main thread to fetch OSRM route and plot 10m markers.
     */
    @JavascriptInterface
    public void plotRouteToDestination(final String destinationName, final double targetLat, final double targetLng) {
        mHandler.post(() -> {
            if (mActivity != null) {
                mActivity.plotRouteToDestination(destinationName, targetLat, targetLng);
            }
        });
    }

    /**
     * JS TRIGGER: Initiates the native simulated drive along the active OSRM polyline.
     */
    @JavascriptInterface
    public void startDriveSimulation(final double speedKmh) {
        mHandler.post(() -> {
            if (mActivity != null) {
                mActivity.startDriveSimulation(speedKmh);
            }
        });
    }

    /**
     * JS TRIGGER: Pauses/stops the native simulated drive immediately.
     */
    @JavascriptInterface
    public void stopDriveSimulation() {
        mHandler.post(() -> {
            if (mActivity != null) {
                mActivity.stopDriveSimulation();
            }
        });
    }

    /**
     * JS TRIGGER: Clears the OSRM route, destination marker, and simulation overlays upon user double-tap.
     */
    @JavascriptInterface
    public void clearRouteOverlay() {
        mHandler.post(() -> {
            if (mActivity != null) {
                mActivity.clearRouteOverlay();
            }
        });
    }

    /**
     * JS TRIGGER: Native clipboard copy implementation to eliminate Webkit "Write Permission Denied" error [1].
     */
    @JavascriptInterface
    public void copyToClipboard(final String text) {
        mHandler.post(() -> {
            ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("ViaroLogs", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(mContext, "Logs copied natively!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void showToast(final String message) {
        if (message == null) return;
        mHandler.post(() -> Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show());
    }

    public void destroy() {
        if (mGeocoderExecutor != null && !mGeocoderExecutor.isShutdown()) {
            mGeocoderExecutor.shutdownNow();
        }
        Log.d(TAG, "MapAssistanceBridge destroyed cleanly.");
    }
}