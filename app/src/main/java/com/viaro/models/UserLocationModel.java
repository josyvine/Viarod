package com.viaro.models;

public class UserLocationModel {
    private String userId;
    private double latitude;
    private double longitude;
    private double altitude;
    private long lastUpdated;
    private float bearing;
    private float speed;
    private float accuracy;
    private long timestamp;
    private float heading;
    private String navigationState;

    public UserLocationModel() {
        // Default constructor for Firebase
    }

    public UserLocationModel(String userId, double latitude, double longitude) {
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = 0.0;
        this.lastUpdated = System.currentTimeMillis();
        this.timestamp = System.currentTimeMillis();
    }

    public UserLocationModel(String userId, double latitude, double longitude, double altitude) {
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.lastUpdated = System.currentTimeMillis();
        this.timestamp = System.currentTimeMillis();
    }

    public UserLocationModel(String userId, double latitude, double longitude, double altitude, float bearing, float speed, float accuracy, long timestamp, float heading, String navigationState) {
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.bearing = bearing;
        this.speed = speed;
        this.accuracy = accuracy;
        this.timestamp = timestamp;
        this.heading = heading;
        this.navigationState = navigationState;
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getAltitude() { return altitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

    public float getBearing() { return bearing; }
    public void setBearing(float bearing) { this.bearing = bearing; }

    public float getSpeed() { return speed; }
    public void setSpeed(float speed) { this.speed = speed; }

    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public float getHeading() { return heading; }
    public void setHeading(float heading) { this.heading = heading; }

    public String getNavigationState() { return navigationState; }
    public void setNavigationState(String navigationState) { this.navigationState = navigationState; }
}
