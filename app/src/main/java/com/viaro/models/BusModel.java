package com.viaro.models;

public class BusModel {
    private String id;
    private String name;
    private String startPoint;
    private String endPoint;
    private double latitude;
    private double longitude;
    private double speed;
    private long lastUpdated;

    public BusModel() {
        // Default constructor for Firebase serialization
    }

    public BusModel(String id, String name, String startPoint, String endPoint, double latitude, double longitude, double speed) {
        this.id = id;
        this.name = name;
        this.startPoint = startPoint;
        this.endPoint = endPoint;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speed = speed;
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStartPoint() { return startPoint; }
    public void setStartPoint(String startPoint) { this.startPoint = startPoint; }

    public String getEndPoint() { return endPoint; }
    public void setEndPoint(String endPoint) { this.endPoint = endPoint; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
}
