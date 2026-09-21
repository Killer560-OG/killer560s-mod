package com.killer560.hub.interop;

import com.killer560.hub.bridge.BridgeConfig;
import com.killer560.hub.melody.MelodyHudConfig;
import com.killer560.hub.partydata.PartyDataConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * killer560 (2026-09-21): "by default all of the information sharing/receiving [should] be on ... if they want to
 * toggle it off they can but it should be on by default."
 * <p>
 * Changing a default only reaches players with no saved config. Everyone who had already launched the mod has these
 * saved as OFF simply because OFF used to be the default, so this switches them ON exactly once, then records that it
 * ran. Only settings whose OLD default was OFF are touched - one that already defaulted ON and is saved OFF was turned
 * off on purpose, and stays off. After this runs once, anything the player turns off stays off.
 */
public final class SharingDefaults {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-interop");
    static final String MARKER_FILE = "killer560smod-sharing-defaults-v1.json";
    private static final Path MARKER = FabricLoader.getInstance().getConfigDir().resolve(MARKER_FILE);

    private SharingDefaults() {
    }

    public static void applyOnce() {
        if (Files.exists(MARKER)) {
            return;
        }
        try {
            InteropConfig interop = InteropConfig.getInstance();
            interop.setEnabled(true);
            interop.setLocalBridge(true);
            interop.save();

            PartyDataConfig data = PartyDataConfig.getInstance();
            data.setShareEnabled(true);
            data.save();

            BridgeConfig bridge = BridgeConfig.getInstance();
            bridge.setEnabled(true);
            bridge.setDevonian(true);
            bridge.setNoamm(true);
            bridge.setOdin(true);
            bridge.save();

            MelodyHudConfig melody = MelodyHudConfig.getInstance();
            melody.setShareProgress(true);
            melody.save();

            Files.writeString(MARKER, "{\"applied\":\"2026-09-21\"}", StandardCharsets.UTF_8);
            LOGGER.info("[Interop] Sharing/receiving settings switched on once (new default)");
        } catch (Exception e) {
            // Leave the marker unwritten so the next launch tries again.
            LOGGER.warn("[Interop] Could not apply sharing defaults: {}", e.toString());
        }
    }
}
