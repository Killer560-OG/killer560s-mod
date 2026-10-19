package com.killer560.hub.modusers;

import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who in the current relay room shows themselves as a mod user, and as which variant (killer560, 2026-10-19). Filled from the
 * relay's welcome and presence events; the relay only lists players who chose to show. Cleared when the room changes.
 */
public final class ModUsers {

    private static final Map<String, String> SHOWN = new ConcurrentHashMap<>();

    /** Bumped on every change to {@link #SHOWN}, so per-row caches (the tab list) know to look again. */
    private static volatile int version = 0;

    private ModUsers() {
    }

    public static int version() {
        return version;
    }

    /** The variant a player shows as ("legit" or "cheat"), or null when they show nothing. */
    public static String variantOf(String name) {
        return name == null ? null : SHOWN.get(name);
    }

    /** Called on the relay welcome: the full list of who shows. */
    public static void onWelcome(JsonObject mods) {
        SHOWN.clear();
        if (mods == null) {
            return;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : mods.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                SHOWN.put(e.getKey(), e.getValue().getAsString());
            }
        }
        version++;
    }

    /** A presence event: {@code shareAs} null means that player stopped showing (or left). */
    public static void onPresence(String name, String shareAs) {
        if (name == null) {
            return;
        }
        if (shareAs == null) {
            SHOWN.remove(name);
        } else {
            SHOWN.put(name, shareAs);
        }
        version++;
    }

    /** Tells the relay this build's variant and the player's sharing choice. Safe to call when not connected. */
    public static void announce() {
        ModUserConfig cfg = ModUserConfig.getInstance();
        RelayClient.sendHello(ModUserConfig.ownVariant(), cfg.isShare(), cfg.getShareAs());
    }
}
