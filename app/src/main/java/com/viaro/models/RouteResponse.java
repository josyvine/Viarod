package com.viaro.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class RouteResponse {
    @SerializedName("routes")
    private List<Route> routes;

    public List<Route> getRoutes() { return routes; }
    public void setRoutes(List<Route> routes) { this.routes = routes; }

    public static class Route {
        @SerializedName("geometry")
        private String geometry;

        @SerializedName("legs")
        private List<RouteLeg> legs;

        public String getGeometry() { return geometry; }
        public void setGeometry(String geometry) { this.geometry = geometry; }

        public List<RouteLeg> getLegs() { return legs; }
        public void setLegs(List<RouteLeg> legs) { this.legs = legs; }
    }
}
