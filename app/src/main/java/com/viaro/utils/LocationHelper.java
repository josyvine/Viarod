package com.viaro.utils;

import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.provider.Settings;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.Priority;

public class LocationHelper {

    public static boolean isGpsEnabled(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    public static void showGpsSettingsPrompt(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static LocationRequest createLocationRequest() {
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setWaitForAccurateLocation(true)
                .setMinUpdateDistanceMeters(1.0f)
                .setMaxUpdateDelayMillis(0)
                .build();
    }
}
