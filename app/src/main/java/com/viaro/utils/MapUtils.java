package com.viaro.utils;

import android.os.Handler;
import android.os.SystemClock;
import android.view.animation.LinearInterpolator;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;
import java.util.ArrayList;
import java.util.List;

public class MapUtils {

    /**
     * Decodes an OSRM/Google encoded polyline string into a list of GeoPoints.
     */
    public static List<GeoPoint> decodePolyline(String encoded) {
        List<GeoPoint> poly = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            GeoPoint p = new GeoPoint((((double) lat / 1E5)), (((double) lng / 1E5)));
            poly.add(p);
        }
        return poly;
    }

    /**
     * Calculates the bearing (angle) between two GeoPoints.
     */
    public static float calculateBearing(GeoPoint startPoint, GeoPoint endPoint) {
        double lat1 = Math.toRadians(startPoint.getLatitude());
        double lng1 = Math.toRadians(startPoint.getLongitude());
        double lat2 = Math.toRadians(endPoint.getLatitude());
        double lng2 = Math.toRadians(endPoint.getLongitude());

        double dLon = (lng2 - lng1);

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        double brng = Math.atan2(y, x);

        return (float) ((Math.toDegrees(brng) + 360) % 360);
    }

    /**
     * Animates/glides a marker smoothly between its current position and a new target position.
     */
    public static void glideMarker(final Marker marker, final GeoPoint toPosition, final long durationMs) {
        final GeoPoint fromPosition = marker.getPosition();
        final Handler handler = new Handler();
        final long start = SystemClock.uptimeMillis();
        final LinearInterpolator interpolator = new LinearInterpolator();

        handler.post(new Runnable() {
            @Override
            public void run() {
                long elapsed = SystemClock.uptimeMillis() - start;
                float t = interpolator.getInterpolation((float) elapsed / durationMs);

                double lat = t * toPosition.getLatitude() + (1 - t) * fromPosition.getLatitude();
                double lng = t * toPosition.getLongitude() + (1 - t) * fromPosition.getLongitude();

                marker.setPosition(new GeoPoint(lat, lng));

                if (t < 1.0) {
                    // Post next frame (approx 16ms for 60fps)
                    handler.postDelayed(this, 16);
                } else {
                    marker.setPosition(toPosition);
                }
            }
        });
    }

    /**
     * Takes the bearing angle of Device A (user's heading) and the target bearing to Device B,
     * and returns a semantic directional instruction based on the angular difference.
     */
    public static String getDirectionInstruction(float myBearing, float targetBearing) {
        float diff = (targetBearing - myBearing + 360) % 360;
        if (diff > 180) {
            diff -= 360;
        }
        if (Math.abs(diff) <= 45) {
            return "Go Straight";
        } else if (diff > 45 && diff <= 135) {
            return "Turn Right";
        } else if (diff < -45 && diff >= -135) {
            return "Turn Left";
        } else {
            return "Take a U-Turn";
        }
    }
}
