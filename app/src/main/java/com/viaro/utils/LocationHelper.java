package com.viaro.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.provider.Settings;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.Priority;

public class LocationHelper {

    public static boolean isGpsEnabled(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return locationManager != null && (
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        );
    }

    public static void showGpsSettingsPrompt(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Create location request configured for high responsiveness and fast cold start.
     * setWaitForAccurateLocation(false) ensures initial GPS fixes are delivered immediately.
     */
    public static LocationRequest createLocationRequest() {
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setWaitForAccurateLocation(false)
                .setMinUpdateDistanceMeters(0.0f)
                .setMaxUpdateDelayMillis(0)
                .build();
    }

    public static boolean isGmsAvailable(Context context) {
        try {
            GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
            int resultCode = apiAvailability.isGooglePlayServicesAvailable(context);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Gets fast last known location across GMS & Native providers (GPS, Network, Passive).
     */
    @SuppressLint("MissingPermission")
    public static Location getLastKnownLocation(Context context, FusedLocationProviderClient fusedClient) {
        Location bestLocation = null;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) {
            try {
                Location gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                Location passiveLoc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);

                if (gpsLoc != null) bestLocation = gpsLoc;
                if (netLoc != null && (bestLocation == null || netLoc.getTime() > bestLocation.getTime())) {
                    bestLocation = netLoc;
                }
                if (passiveLoc != null && (bestLocation == null || passiveLoc.getTime() > bestLocation.getTime())) {
                    bestLocation = passiveLoc;
                }
            } catch (Exception ignored) {}
        }
        return bestLocation;
    }

    /**
     * Dual-Engine Location Update Registration:
     * Tries GMS FusedLocationProviderClient first.
     * Registers Native LocationManager as backup / non-GMS fallback ONLY if GMS is unavailable or fails.
     */
    @SuppressLint("MissingPermission")
    public static void startDualEngineLocationUpdates(
            Context context,
            FusedLocationProviderClient fusedClient,
            LocationCallback gmsCallback,
            LocationListener nativeListener,
            Looper looper
    ) {
        // 1. Try GMS Fused Location Provider if available
        if (fusedClient != null && gmsCallback != null && isGmsAvailable(context)) {
            try {
                fusedClient.requestLocationUpdates(createLocationRequest(), gmsCallback, looper)
                        .addOnFailureListener(e -> {
                            startNativeLocationUpdates(context, nativeListener, looper);
                        });
            } catch (Exception e) {
                startNativeLocationUpdates(context, nativeListener, looper);
            }
        } else {
            // 2. Fall back to native framework listener if GMS is unavailable
            startNativeLocationUpdates(context, nativeListener, looper);
        }
    }

    @SuppressLint("MissingPermission")
    public static void startNativeLocationUpdates(Context context, LocationListener listener, Looper looper) {
        if (listener == null) return;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.0f, listener, looper);
            }
        } catch (Exception ignored) {}

        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0.0f, listener, looper);
            }
        } catch (Exception ignored) {}
    }

    public static void stopDualEngineLocationUpdates(
            Context context,
            FusedLocationProviderClient fusedClient,
            LocationCallback gmsCallback,
            LocationListener nativeListener
    ) {
        if (fusedClient != null && gmsCallback != null) {
            try {
                fusedClient.removeLocationUpdates(gmsCallback);
            } catch (Exception ignored) {}
        }
        if (nativeListener != null) {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                try {
                    lm.removeUpdates(nativeListener);
                } catch (Exception ignored) {}
            }
        }
    }
}