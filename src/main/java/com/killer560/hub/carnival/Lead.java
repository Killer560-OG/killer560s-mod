package com.killer560.hub.carnival;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a moving target will be when a shot or a cast gets there: each entity's horizontal velocity is smoothed from the
 * position it had last tick (a client sees remote entities only as positions), and {@link #ahead} adds that velocity times
 * the flight time (distance over the projectile's speed, plus one tick of latency), scaled by Lead Adjust. Samples are
 * dropped with the entity ({@link #retain}) and at every game start, so the map is bounded by what is on screen.
 */
public final class Lead {

    /** Dart and bobber speed assumed, blocks a tick (unverified in game; Lead Adjust scales the result). */
    static final double SPEED = 3.0;
    private static final Map<Integer, Vec3> LAST = new HashMap<>();
    private static final Map<Integer, Vec3> VEL = new HashMap<>();

    private Lead() {
    }

    /** Call once a tick per entity. */
    static void sample(int id, Vec3 pos) {
        Vec3 prev = LAST.put(id, pos);
        if (prev == null) {
            return;
        }
        Vec3 d = new Vec3(pos.x - prev.x, 0, pos.z - prev.z);
        Vec3 old = VEL.get(id);
        VEL.put(id, old == null ? d : old.scale(0.5).add(d.scale(0.5)));
    }

    static Vec3 velocity(int id) {
        Vec3 v = VEL.get(id);
        return v == null ? Vec3.ZERO : v;
    }

    static void retain(Set<Integer> alive) {
        LAST.keySet().retainAll(alive);
        VEL.keySet().retainAll(alive);
    }

    static void clear() {
        LAST.clear();
        VEL.clear();
    }

    /** {@code point} moved along entity {@code id}'s velocity for the flight time from {@code eye}, scaled by {@code pct}. */
    static Vec3 ahead(int id, Vec3 point, Vec3 eye, int pct) {
        Vec3 v = velocity(id);
        if (pct <= 0 || v.lengthSqr() < 1e-6) {
            return point;
        }
        double ticks = (eye.distanceTo(point) / SPEED + 1) * pct / 100.0;
        return point.add(v.scale(ticks));
    }

    /** Harness: a target moving {@code vx} blocks a tick along x for 6 ticks, {@code dist} blocks away; where the aim lands in x. */
    public static double testAheadX(double vx, double dist, int pct) {
        int id = -987654;
        clear();
        for (int i = 0; i <= 6; i++) {
            sample(id, new Vec3(i * vx, 0, dist));
        }
        double x = ahead(id, new Vec3(6 * vx, 0, dist), Vec3.ZERO, pct).x;
        clear();
        return x - 6 * vx;
    }
}
