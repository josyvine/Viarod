package com.viaro.bridge;

import android.content.Context;
import android.content.SharedPreferences;
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

public class MapAssistanceBridge {

    private static final String TAG = "MapAssistanceBridge";

    private final Context mContext;
    private final WebView mWebView;
    private final MapAssistanceActivity mActivity;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;

    // Memory Cache for Hardware GPS and Compass Data
    private double mCurrentLat = 0.0;
    private double mCurrentLng = 0.0;
    private double mCurrentSpeed = 0.0;
    private double mCurrentAccuracy = 999.0;

    private float mCompassHeadingDegrees = 0.0f;
    private String mCompassCardinalDirection = "NORTH";

    public MapAssistanceBridge(Context context, WebView webView, MapAssistanceActivity activity) {
        this.mContext = context;
        this.mWebView = webView;
        this.mActivity = activity;
        this.mHandler = new Handler(Looper.getMainLooper());
        this.mPrefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Called by MapAssistanceActivity whenever new hardware GPS fix arrives.
     */
    public void updateLocation(Location location) {
        if (location != null) {
            this.mCurrentLat = location.getLatitude();
            this.mCurrentLng = location.getLongitude();
            this.mCurrentSpeed = location.hasSpeed() ? (location.getSpeed() * 3.6) : 0.0; // Convert m/s to km/h
            this.mCurrentAccuracy = location.hasAccuracy() ? location.getAccuracy() : 50.0;
        }
    }

    /**
     * Called by MapAssistanceActivity whenever new compass sensor orientation arrives.
     */
    public void updateCompass(float degrees, String cardinalDirection) {
        this.mCompassHeadingDegrees = degrees;
        this.mCompassCardinalDirection = cardinalDirection;
    }

    // --- JS INTERFACE METHODS EXPOSED TO map_assistance.html ---

    @JavascriptInterface
    public String getGpsLocation() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("lat", mCurrentLat);
            obj.put("lng", mCurrentLng);
            obj.put("speed", mCurrentSpeed);
            obj.put("accuracy", mCurrentAccuracy);
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

    @JavascriptInterface
    public void showToast(final String message) {
        if (message == null) return;
        mHandler.post(() -> Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show());
    }

    public void destroy() {
        Log.d(TAG, "MapAssistanceBridge destroyed cleanly.");
    }
}