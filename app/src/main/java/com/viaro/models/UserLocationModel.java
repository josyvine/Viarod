package com.viaro.models;

public class UserLocationModel {
    private String userId;
    private double latitude;
    private double longitude;
    private double altitude;
    private long lastUpdated;

    public UserLocationModel() {
        // Default constructor for Firebase
    }

    public UserLocationModel(String userId, double latitude, double longitude) {
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = 0.0;
        this.lastUpdated = System.currentTimeMillis();
    }

    public UserLocationModel(String userId, double latitude, double longitude, double altitude) {
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
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
}
