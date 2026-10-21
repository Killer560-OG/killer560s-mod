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

    /** Most names kept from one room; a hostile or broken relay cannot grow the map past this. */
    private static final int MAX_SHOWN = 1024;

    /** Replaced whole on a welcome, so the render thread never sees a half-filled list. */
    private static volatile Map<String, String> SHOWN = new ConcurrentHashMap<>();

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
        Map<String, String> fresh = new ConcurrentHashMap<>();
        if (mods != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : mods.entrySet()) {
                if (fresh.size() >= MAX_SHOWN) {
                    break;
                }
                if (e.getValue().isJsonPrimitive() && validName(e.getKey()) && validVariant(e.getValue().getAsString())) {
                    fresh.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        SHOWN = fresh;
        version++;
    }

    /** A presence event: {@code shareAs} null means that player stopped showing (or left). */
    public static void onPresence(String name, String shareAs) {
        if (!validName(name)) {
            return;
        }
        Map<String, String> shown = SHOWN;
        if (shareAs == null) {
            shown.remove(name);
        } else if (validVariant(shareAs) && (shown.size() < MAX_SHOWN || shown.containsKey(name))) {
            shown.put(name, shareAs);
        } else {
            return;
        }
        version++;
    }

    private static boolean validVariant(String v) {
        return "legit".equals(v) || "cheat".equals(v);
    }

    /** A Minecraft name: 1-16 of letters, digits, underscore. */
    private static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** Tells the relay this build's variant and the player's sharing choice. Safe to call when not connected. */
    public static void announce() {
        ModUserConfig cfg = ModUserConfig.getInstance();
        RelayClient.sendHello(ModUserConfig.ownVariant(), cfg.isShare(), cfg.getShareAs());
    }
}
