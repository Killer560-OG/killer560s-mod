package com.killer560.hub.autosecret;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * STUB - TO BE REPLACED by the real Insta Clear tracker (built in parallel on branch insta-clear). This file only
 * exists so Auto Secret compiles against the agreed API; it knows no entries, so Auto Secret's insta-clear step
 * finds nothing and goes straight to secreting. When the real class is merged, take ITS version of this file
 * whole - the signatures below are the contract, nothing else here matters.
 */
public final class InstaClearTracker {

    private InstaClearTracker() {
    }

    /** STUB: registers nothing. */
    public static void register() {
    }

    /** STUB: no entry is known to insta clear. */
    public static boolean knownToInstaClear(String roomName, String entryKey) {
        return false;
    }

    /** STUB: no entries known. */
    public static List<String> knownEntries(String roomName) {
        return List.of();
    }

    /** STUB: a key built from the arguments only so it is stable; the real tracker defines the format. */
    public static String entryKeyFor(String roomName, BlockPos landing, String fromRoomName) {
        return roomName + "|" + (landing == null ? "?" : landing.toShortString()) + "|" + fromRoomName;
    }
}
