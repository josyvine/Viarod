package com.viaro.firebase;

import android.util.Log;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.viaro.models.BusModel;
import com.viaro.models.UserLocationModel;
import com.viaro.utils.AppConstants;

public class FirebaseHelper {
    private static DatabaseReference mDatabase = null;

    private static DatabaseReference getRef() {
        if (mDatabase == null) {
            try {
                // If standard initialization is loaded, retrieve instance
                mDatabase = FirebaseDatabase.getInstance().getReference();
            } catch (Exception e) {
                // Write detailed trace to Logcat to expose Firebase database connection failures
                Log.e("FirebaseHelper", "DATABASE REFERENCE INITIALIZATION FAILED. " +
                        "Verify google-services.json configuration, dependencies, and database region URL. Error: " + e.getMessage(), e);
                mDatabase = null;
            }
        }
        return mDatabase;
    }

    public static DatabaseReference getBusesReference() {
        DatabaseReference ref = getRef();
        return ref != null ? ref.child(AppConstants.FIREBASE_BUS_PATH) : null;
    }

    public static DatabaseReference getMeetupReference(String roomCode) {
        DatabaseReference ref = getRef();
        return ref != null ? ref.child(AppConstants.FIREBASE_MEET_PATH).child(roomCode) : null;
    }

    public static void updateBusLocation(BusModel bus) {
        DatabaseReference ref = getBusesReference();
        if (ref != null) {
            ref.child(bus.getId()).setValue(bus);
        }
    }

    public static void removeBus(String busId) {
        DatabaseReference ref = getBusesReference();
        if (ref != null) {
            ref.child(busId).removeValue();
        }
    }

    public static void updateUserLocation(String roomCode, UserLocationModel userLocation) {
        DatabaseReference ref = getMeetupReference(roomCode);
        if (ref != null) {
            ref.child(userLocation.getUserId()).setValue(userLocation);
        }
    }

    public static void removeMeetupRoom(String roomCode) {
        DatabaseReference ref = getMeetupReference(roomCode);
        if (ref != null) {
            ref.removeValue();
        }
    }
}