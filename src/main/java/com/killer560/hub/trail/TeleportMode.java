package com.killer560.hub.trail;

/**
 * What a trail does across a teleport (killer560, 2026-10-15: "Make the custom trail work with etherwarps or
 * teleports. It can have the trail break or draw a trail between the two points or toggles for which"): an etherwarp,
 * an AOTV or any server teleport moves the player a long way in one step, and the trail used to either streak or
 * vanish. Both the Ribbon Trail and the squares Trail have this setting.
 */
public enum TeleportMode {
    /** A gap: the trail up to the teleport stays, a new piece starts where the player lands. */
    BREAK("Break"),
    /** One straight segment between the two points. */
    CONNECT("Connect"),
    /** A curved segment that rises between the two points, like the flight of the warp. */
    ARC("Arc");

    /** A step further than this (blocks, one frame for the ribbon and one tick for the squares) is a teleport. A fall
     *  tops out near 3.9 blocks a tick and sprinting is well under 1, so ordinary movement never reaches it. */
    public static final double MIN_JUMP = 4.0;
    /** The most points a bridged jump lays, so a 60-block etherwarp stays inside the fixed rings. */
    public static final int MAX_BRIDGE_POINTS = 120;

    private final String label;

    TeleportMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True when a step of {@code distance} blocks counts as a teleport. */
    public static boolean isTeleport(double distance) {
        return distance > MIN_JUMP;
    }

    /** How high the Arc rises at its middle for a jump of {@code distance} blocks. */
    public static double arcHeight(double distance) {
        return Math.min(6.0, distance * 0.2);
    }

    /** The extra height at {@code t} (0 at the start, 1 at the end) along a jump of {@code distance} blocks: 0 unless Arc. */
    public double lift(double t, double distance) {
        return this == ARC ? arcHeight(distance) * 4.0 * t * (1.0 - t) : 0.0;
    }

    /** Steps used to bridge {@code distance} blocks at about {@code spacing} blocks apiece, between 1 and the cap. */
    public static int bridgeSteps(double distance, double spacing) {
        return (int) Math.max(1, Math.min(MAX_BRIDGE_POINTS, Math.ceil(distance / spacing)));
    }

    public static TeleportMode parse(String name, TeleportMode fallback) {
        try {
            return name == null ? fallback : valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
