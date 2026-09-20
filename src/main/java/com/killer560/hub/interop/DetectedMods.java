package com.killer560.hub.interop;

import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which other Skyblock dungeon mods are installed in THIS Minecraft.
 * <p>
 * Used for two different things, and it is worth being clear which is which:
 * <ul>
 * <li>The Interop tab's status line, so it is obvious what is and is not around.</li>
 * <li>{@link LocalModBridge}, which is the only thing that actually depends on a mod being installed here.
 * The chat parser does not - it reads party messages, which arrive whether or not we have the mod that sent
 * them, and that is the whole point.</li>
 * </ul>
 * The ids are the real {@code fabric.mod.json} ids, read out of the jars in killer560's own
 * "26.1.2 (Dungeons)" instance (2026-09-20), not guessed.
 */
public final class DetectedMods {

    public static final String NOAMM_ADDONS = "noammaddons";
    public static final String ODIN = "odin";
    public static final String SKYHANNI = "skyhanni";
    public static final String DEVONIAN = "devonian";
    public static final String QUOI = "quoi";
    public static final String SKYBLOCKER = "skyblocker";
    public static final String SECRET_ROUTES = "secretroutesmod";

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(NOAMM_ADDONS, "NoammAddons");
        NAMES.put(ODIN, "Odin");
        NAMES.put(SKYHANNI, "SkyHanni");
        NAMES.put(DEVONIAN, "Devonian");
        NAMES.put(QUOI, "QUOI");
        NAMES.put(SKYBLOCKER, "Skyblocker");
        NAMES.put(SECRET_ROUTES, "SecretRoutes");
    }

    // Resolved once - FabricLoader's mod set cannot change while the game is running.
    private static volatile List<String> present;

    private DetectedMods() {
    }

    public static boolean isLoaded(String modId) {
        try {
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Display names of the mods above that are installed here, in the order listed. */
    public static List<String> presentNames() {
        List<String> cached = present;
        if (cached != null) {
            return cached;
        }
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            if (isLoaded(entry.getKey())) {
                found.add(entry.getValue());
            }
        }
        present = List.copyOf(found);
        return present;
    }

    /** "NoammAddons, Odin, SkyHanni" - or "none" for the ordinary case of a player with only this mod. */
    public static String describe() {
        List<String> names = presentNames();
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
