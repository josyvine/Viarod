package com.viaro.utils;

public class AppConstants {
    // Core OSRM & Firebase Paths
    public static final String OSRM_BASE_URL = "https://router.project-osrm.org/";
    public static final String FIREBASE_BUS_PATH = "active_buses";
    public static final String FIREBASE_MEET_PATH = "p2p_meetups";
    public static final String NOTIFICATION_CHANNEL_ID = "viaro_location_channel";
    public static final String NOTIFICATION_CHANNEL_NAME = "viaro_live_tracking";

    // SharedPreferences Name
    public static final String PREF_NAME = "ViaroPrefs";

    // JavaScript Bridge Name (matches window.AndroidInterface in HTML/JS)
    public static final String JS_BRIDGE_NAME = "AndroidInterface";

    // Gemini Settings Preference Keys
    public static final String PREF_GEMINI_API_KEY = "gemini_user_api_key";
    public static final String PREF_GEMINI_MODEL = "gemini_user_model";
    public static final String PREF_GEMINI_VOICE = "gemini_user_voice";
    public static final String PREF_MAP_GROUNDING = "gemini_map_grounding";
    public static final String PREF_SEARCH_GROUNDING = "gemini_search_grounding";

    // Virtual HTTPS Asset URL for Map Assistance HTML
    public static final String LOCAL_MAP_ASSISTANCE_URL = "https://appassets.androidplatform.net/assets/map_assistance.html";
}