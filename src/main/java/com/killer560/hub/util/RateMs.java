package com.killer560.hub.util;

import com.google.gson.JsonObject;
import java.util.concurrent.ThreadLocalRandom;

/** Click rates as a gap in milliseconds (sliderunits-1118, killer560 2026-11-18: no more CPS). 50 ms is one click a client
 *  tick, the fastest the mod-wide gate allows (the old 20 CPS); 1000 ms is the old 1 CPS. */
public final class RateMs {
    public static final int MIN = 50;
    public static final int MAX = 1000;

    private RateMs() {
    }

    public static int clamp(int ms) {
        return Math.max(MIN, Math.min(MAX, ms));
    }

    /** An old clicks-per-second value as the nearest gap: 16 CPS is 63 ms, 0 or less is the slowest. */
    public static int fromCps(double cps) {
        return cps <= 0 ? MAX : clamp((int) Math.round(1000.0 / cps));
    }

    /** {@code msKey} if saved, else the old {@code cpsKey} (clicks per second) converted, else {@code def}. */
    public static int read(JsonObject o, String msKey, String cpsKey, int def) {
        if (o.has(msKey)) {
            return ConfigJson.getInt(o, msKey, def);
        }
        return o.has(cpsKey) ? fromCps(ConfigJson.getInt(o, cpsKey, 0)) : def;
    }

    /** A fresh gap in ms uniformly inside {@code lo..hi} (lo may equal hi). */
    public static double roll(int lo, int hi) {
        return hi > lo ? ThreadLocalRandom.current().nextDouble(lo, hi) : lo;
    }
}
