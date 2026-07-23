package com.viaro.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.vineyard.viaro.app.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.viaro.firebase.FirebaseHelper;
import com.viaro.models.BusModel;
import com.viaro.utils.AppConstants;
import com.viaro.utils.LocationHelper;

public class LocationService extends Service {

    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private LocationListener mNativeLocationListener;
    private PowerManager.WakeLock mWakeLock;
    
    private String busId;
    private String busName;
    private String startPoint;
    private String endPoint;

    @Override
    public void onCreate() {
        super.onCreate();
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    updateFirebase(location);
                }
            }
        };

        mNativeLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                updateFirebase(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(@NonNull String provider) {}

            @Override
            public void onProviderDisabled(@NonNull String provider) {}
        };

        // Acquire a partial wake lock to keep the CPU running
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "viaro:LocationServiceWakeLock");
            mWakeLock.acquire(10 * 60 * 1000L); // 10 minutes max fallback
        }
    }

    private void updateFirebase(Location location) {
        if (location == null) return;

        // Fetch last known if available for immediate update
        if (busId == null) return;
        
        double speedKmh = location.getSpeed() * 3.6; // Convert m/s to km/h
        BusModel bus = new BusModel(
            busId,
            busName != null ? busName : "Live Bus",
            startPoint != null ? startPoint : "Start",
            endPoint != null ? endPoint : "Destination",
            location.getLatitude(),
            location.getLongitude(),
            speedKmh
        );
        
        FirebaseHelper.updateBusLocation(bus);
        
        // Broadcast local update
        Intent intent = new Intent("viaro.LOCATION_UPDATE");
        intent.putExtra("latitude", location.getLatitude());
        intent.putExtra("longitude", location.getLongitude());
        intent.putExtra("speed", speedKmh);
        sendBroadcast(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            busId = intent.getStringExtra("bus_id");
            busName = intent.getStringExtra("bus_name");
            startPoint = intent.getStringExtra("start_point");
            endPoint = intent.getStringExtra("end_point");
        }

        createNotificationChannel();
        
        Intent notificationIntent = new Intent();
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification notification = new NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("viaro Live Broadcast")
                .setContentText("Broadcasting location for: " + (busName != null ? busName : "Bus route"))
                .setSmallIcon(R.drawable.ic_bus)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
        startLocationUpdates();

        return START_STICKY;
    }

    private void startLocationUpdates() {
        LocationHelper.startDualEngineLocationUpdates(
            this,
            mFusedLocationClient,
            mLocationCallback,
            mNativeLocationListener,
            getMainLooper()
        );

        // Immediate last known location fix to avoid cold start delay
        Location lastLoc = LocationHelper.getLastKnownLocation(this, mFusedLocationClient);
        if (lastLoc != null) {
            updateFirebase(lastLoc);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    AppConstants.NOTIFICATION_CHANNEL_ID,
                    AppConstants.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocationHelper.stopDualEngineLocationUpdates(
            this,
            mFusedLocationClient,
            mLocationCallback,
            mNativeLocationListener
        );
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (busId != null) {
            FirebaseHelper.removeBus(busId);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
