package com.killer560.hub.roomdatabase;

import java.util.HashMap;
import java.util.Map;

/** Offline stand-in for RotationAudit: just the by-name lookup, filled from a rooms-modern.json. */
public final class RoomDatabase {
    public static final Map<String, RoomEntry> BY_NAME = new HashMap<>();

    /** Offline the table is filled before anything asks, so it is always ready. */
    public static boolean isReady() {
        return true;
    }

    public static void ensureLoading() {
    }

    public static RoomEntry lookupByName(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    public static void load(String json) {
        for (RoomEntry e : new com.google.gson.Gson().fromJson(json, RoomEntry[].class)) {
            if (e.name != null) {
                BY_NAME.put(e.name, e);
            }
        }
    }
}
