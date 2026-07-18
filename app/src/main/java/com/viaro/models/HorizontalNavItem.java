package com.viaro.models;

public class HorizontalNavItem {
    private int id;
    private String label;
    private int iconResId;

    public HorizontalNavItem(int id, String label, int iconResId) {
        this.id = id;
        this.label = label;
        this.iconResId = iconResId;
    }

    public int getId() { return id; }
    public String getLabel() { return label; }
    public int getIconResId() { return iconResId; }
}
