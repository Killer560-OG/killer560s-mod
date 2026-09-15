package com.killer560.hub.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One saved waypoint route - an ordered list of block positions walked in order. */
public final class Route {

    public static final int DEFAULT_COLOR = 0xFFFFA040;
    public static final double DEFAULT_RADIUS = 3.0;
    public static final double MIN_RADIUS = 0.5;
    public static final double MAX_RADIUS = 10.0;

    /** Stable id - the per-area active map points at this, so renaming never breaks it. */
    public final String id;
    public String name;
    public int color = DEFAULT_COLOR;
    private double radius = DEFAULT_RADIUS;
    public boolean loop = true;
    public final List<Point> points = new ArrayList<>();

    public Route(String id, String name) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    /** A block position in a route. {@code label} is optional ("" = just the number). */
    public record Point(int x, int y, int z, String label) {
        public Point {
            label = label == null ? "" : label;
        }
    }
}
