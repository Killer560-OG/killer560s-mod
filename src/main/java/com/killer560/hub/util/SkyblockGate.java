package com.killer560.hub.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * "Skyblock Only" (2026-09-15, killer560: a toggle so "none of the mods work outside of sky block or P3 Sim ... it
 * wouldn't actually turn the mods off ... I would be able to rejoin sky block, and all of the mods would immediately
 * be right back on").
 * <p>
 * Features AND their master "enabled" getters with {@link #allows()}; their saved settings are never touched. While
 * the toggle is on, {@link #allows()} is false anywhere that isn't Hypixel Skyblock (sidebar title contains
 * "SKYBLOCK") or p3sim.net. The sidebar briefly disappears on every world switch, so the last verdict is held for
 * up to {@link #NO_SIDEBAR_HOLD_MS} while no sidebar is shown. The mod's own screens always see the real values
 * so the settings menu keeps showing what is actually turned on.
 */
public final class SkyblockGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-skyblockgate");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-skyblockonly.json");
    private static final long NO_SIDEBAR_HOLD_MS = 10_000L;

    private static boolean enabled = false;
    private static boolean loaded = false;
    private static volatile boolean onSkyblock = false;
    private static long noSidebarSinceMs = 0L;
    private static int tickCounter = 0;

    private SkyblockGate() {
    }

    public static void register() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter < 5) {
                return;
            }
            tickCounter = 0;
            try {
                update(client);
            } catch (RuntimeException e) {
                LOGGER.warn("[SkyblockGate] Detection failed: {}", e.toString());
            }
        });
    }

    /** @return false only when "Skyblock Only" is on and you're somewhere other than Skyblock / p3sim. */
    public static boolean allows() {
        if (!isEnabled() || onSkyblock) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        // The mod's own screens (settings menu, HUD editor, pickers) show the real saved values.
        return client != null && client.screen != null && client.screen.getClass().getName().startsWith("com.killer560.hub.");
    }

    public static boolean isOnSkyblock() {
        return onSkyblock;
    }

    private static void update(Minecraft client) {
        boolean before = onSkyblock;
        if (client.level == null || client.player == null) {
            holdOrExpire();
        } else if (isP3Sim(client)) {
            onSkyblock = true;
            noSidebarSinceMs = 0L;
        } else {
            Objective sidebar = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
            if (sidebar == null) {
                holdOrExpire();
            } else {
                String title = ChatFormatting.stripFormatting(sidebar.getDisplayName().getString());
                onSkyblock = title != null && title.toUpperCase(Locale.ROOT).replace(" ", "").contains("SKYBLOCK");
                noSidebarSinceMs = 0L;
            }
        }
        if (before != onSkyblock) {
            LOGGER.info("[SkyblockGate] On Skyblock/p3sim: {} -> {} (Skyblock Only {})", before, onSkyblock, isEnabled() ? "ON" : "OFF");
        }
    }

    private static void holdOrExpire() {
        long now = System.currentTimeMillis();
        if (noSidebarSinceMs == 0L) {
            noSidebarSinceMs = now;
        } else if (now - noSidebarSinceMs > NO_SIDEBAR_HOLD_MS) {
            onSkyblock = false;
        }
    }

    private static boolean isP3Sim(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }

    public static boolean isEnabled() {
        if (!loaded) {
            load();
        }
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            }
        } catch (Exception e) {
            LOGGER.warn("[SkyblockGate] Couldn't read {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    /** Re-reads the file (used after a settings profile is applied). */
    public static synchronized void reload() {
        loaded = false;
        load();
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
