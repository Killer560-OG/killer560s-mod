package com.killer560.hub.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Locale;

/** fabric.mod.json already declares "breaks": {"firmament": "*"}, which makes Fabric Loader
 *  refuse to boot with any version of Firmament installed (shown as its own incompatible-mods
 *  screen, before any mod code runs). This is a defense-in-depth backstop for the case where a
 *  future Firmament release ships under a different mod id than "firmament" - it matches on id
 *  or display name containing "firmament" rather than one exact id, and hard-crashes if found. */
public final class ModCompatibility {

    private static final String OWN_MOD_ID = "killer560smod";

    private ModCompatibility() {
    }

    public static void refuseIfFirmamentPresent() {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (OWN_MOD_ID.equals(id)) {
                continue;
            }
            String name = mod.getMetadata().getName();
            if (containsFirmament(id) || containsFirmament(name)) {
                throw new IllegalStateException(
                        "Killer560's Mod refuses to run alongside Firmament (found mod id: \"" + id
                                + "\"). Remove Firmament from your mods folder, then relaunch.");
            }
        }
    }

    private static boolean containsFirmament(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("firmament");
    }
}
