package com.viaro.utils;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

public class SpatialContextManager {

    /**
     * Converts a raw compass heading (0.0° - 360.0°) into a human-readable cardinal direction string.
     */
    public static String getCardinalDirection(float degrees) {
        float normalized = (degrees % 360.0f + 360.0f) % 360.0f;

        if (normalized >= 337.5 || normalized < 22.5) {
            return "NORTH";
        } else if (normalized >= 22.5 && normalized < 67.5) {
            return "NORTH_EAST";
        } else if (normalized >= 67.5 && normalized < 112.5) {
            return "EAST";
        } else if (normalized >= 112.5 && normalized < 157.5) {
            return "SOUTH_EAST";
        } else if (normalized >= 157.5 && normalized < 202.5) {
            return "SOUTH";
        } else if (normalized >= 202.5 && normalized < 247.5) {
            return "SOUTH_WEST";
        } else if (normalized >= 247.5 && normalized < 292.5) {
            return "WEST";
        } else if (normalized >= 292.5 && normalized < 337.5) {
            return "NORTH_WEST";
        }
        return "NORTH";
    }

    /**
     * Interpolates an OSRM decoded route polyline into exact 10-meter waypoint GeoPoints.
     * This creates markers spaced every 10 meters along the road toward the attraction.
     *
     * @param originalPolyline The list of GeoPoints returned by the OSRM route API.
     * @return A list of interpolated GeoPoints spaced 10 meters apart.
     */
    public static List<GeoPoint> interpolatePointsEvery10Meters(List<GeoPoint> originalPolyline) {
        List<GeoPoint> interpolatedList = new ArrayList<>();
        if (originalPolyline == null || originalPolyline.size() < 2) {
            return originalPolyline != null ? originalPolyline : interpolatedList;
        }

        double intervalMeters = 10.0; // 10 meters requirement

        // Add start point
        interpolatedList.add(originalPolyline.get(0));

        double accumulatedDistance = 0.0;

        for (int i = 0; i < originalPolyline.size() - 1; i++) {
            GeoPoint p1 = originalPolyline.get(i);
            GeoPoint p2 = originalPolyline.get(i + 1);

            double segmentDistance = p1.distanceToAsDouble(p2);

            if (segmentDistance <= 0.0) continue;

            double currentSegmentProgress = 0.0;

            while (currentSegmentProgress + (intervalMeters - accumulatedDistance) <= segmentDistance) {
                double distanceToNextPoint = intervalMeters - accumulatedDistance;
                currentSegmentProgress += distanceToNextPoint;

                double fraction = currentSegmentProgress / segmentDistance;

                double interpLat = p1.getLatitude() + fraction * (p2.getLatitude() - p1.getLatitude());
                double interpLng = p1.getLongitude() + fraction * (p2.getLongitude() - p1.getLongitude());

                interpolatedList.add(new GeoPoint(interpLat, interpLng));
                accumulatedDistance = 0.0; // Reset accumulated distance after adding point
            }

            accumulatedDistance += (segmentDistance - currentSegmentProgress);
        }

        // Add final destination point
        GeoPoint lastPoint = originalPolyline.get(originalPolyline.size() - 1);
        if (interpolatedList.isEmpty() || interpolatedList.get(interpolatedList.size() - 1).distanceToAsDouble(lastPoint) > 2.0) {
            interpolatedList.add(lastPoint);
        }

        return interpolatedList;
    }
}