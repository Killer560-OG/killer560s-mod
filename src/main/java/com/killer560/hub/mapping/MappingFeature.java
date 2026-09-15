package com.killer560.hub.mapping;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Real dungeon-map data-gathering tool - see the "Honesty note" on {@link MappingConfig} for why the
 * actual funny-map/extra-info/mimic-highlight/class-recolor toggles don't draw anything yet. What this
 * class DOES do for real: read the raw pixel data off whatever vanilla map item you're holding
 * ({@link MapItemSavedData#colors}, a real 128x128 byte array that's been a stable public field in
 * vanilla's Mojmap source across many past versions - not independently decompiled against this specific
 * 26.1.2 build, so if it's moved/renamed here this simply won't compile, matching every other honestly-
 * disclosed "pattern matched, not build-verified" piece of this session's work) and save it to a plain
 * text file you can open, save copies of at different real moments (map open, mimic room visible, after
 * a class icon shows up), and diff against each other to find the actual pixel encoding.
 * <p>
 * Also logs a lightweight periodic diagnostic (map id + byte checksum + non-zero pixel count) while
 * you're in a dungeon holding a map, purely so the log itself shows when the held map's content is
 * changing tick-to-tick, without spamming the full 16384-byte grid every time.
 */
public final class MappingFeature {

    // Real, extremely stable vanilla constant - every Minecraft version's map item has always been a
    // 128x128 grid of color-index bytes (MapItemSavedData.MAP_SIZE).
    private static final int MAP_SIZE = 128;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-mapping");

    private static int tickCounter = 0;
    private static int lastLoggedChecksum = Integer.MIN_VALUE;
    private static String lastLoggedGates = null;
    private static boolean lastHadHeldMap = false;

    private MappingFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String gates = "enabled=" + MappingConfig.getInstance().isEnabled() + " inDungeon=" + DungeonState.isInDungeon();
            if (!gates.equals(lastLoggedGates)) {
                LOGGER.info("[Mapping] Gates changed: {}", gates);
                lastLoggedGates = gates;
            }
            if (!MappingConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
                return;
            }
            tickCounter++;
            if (tickCounter < 100) {
                return;
            }
            tickCounter = 0;
            logDiagnostic();
        });
    }

    private static void logDiagnostic() {
        HeldMap held = findHeldMap();
        if ((held != null) != lastHadHeldMap) {
            lastHadHeldMap = held != null;
            LOGGER.info("[Mapping] Holding a filled map with loaded data: {}", lastHadHeldMap);
        }
        if (held == null) {
            return;
        }
        byte[] colors = held.data.colors;
        int checksum = 0;
        int nonZero = 0;
        for (byte b : colors) {
            checksum = checksum * 31 + b;
            if (b != 0) {
                nonZero++;
            }
        }
        if (checksum != lastLoggedChecksum) {
            LOGGER.info("[Mapping] Held map id={} changed: checksum={} nonZeroPixels={}/{}",
                    held.mapId, checksum, nonZero, colors.length);
            lastLoggedChecksum = checksum;
        }
    }

    /** Real, callable-now action wired to "/killer560 mapdump" and the Mapping tab's button.
     *  @return a user-facing status message (success with the file path, or why it couldn't dump). */
    public static String dumpHeldMap() {
        HeldMap held = findHeldMap();
        if (held == null) {
            return "§c[Mapping] You need to be holding a filled map to dump it.";
        }
        byte[] colors = held.data.colors;
        StringBuilder sb = new StringBuilder();
        sb.append("# Killer560's Mod - map dump\n");
        sb.append("# mapId=").append(held.mapId).append('\n');
        sb.append("# scale=").append(held.data.scale).append(" locked=").append(held.data.locked).append('\n');
        sb.append("# Each line is one row of the real 128x128 color-index grid, as 2-digit hex bytes.\n");
        sb.append("# Save a copy of this file at different real moments (map open, mimic room visible,\n");
        sb.append("# after a class icon appears) and diff them to find which bytes/positions changed.\n");
        for (int row = 0; row < MAP_SIZE; row++) {
            for (int col = 0; col < MAP_SIZE; col++) {
                sb.append(String.format("%02x", colors[row * MAP_SIZE + col] & 0xFF));
                if (col < MAP_SIZE - 1) {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }

        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mapdumps");
        String filename = "mapdump-" + LocalDateTime.now().format(FILE_TIMESTAMP) + "-id" + held.mapId + ".txt";
        Path file = dir.resolve(filename);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            LOGGER.info("[Mapping] Dumped held map id={} to {}", held.mapId, file);
            return "[Mapping] Dumped held map to " + file;
        } catch (IOException e) {
            LOGGER.warn("[Mapping] Failed to write map dump", e);
            return "§c[Mapping] Failed to write map dump: " + e.getMessage();
        }
    }

    private static HeldMap findHeldMap() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return null;
        }
        ItemStack main = client.player.getMainHandItem();
        ItemStack off = client.player.getOffhandItem();
        MapId mapId = main.get(DataComponents.MAP_ID);
        if (mapId == null) {
            mapId = off.get(DataComponents.MAP_ID);
        }
        if (mapId == null) {
            return null;
        }
        MapItemSavedData data = client.level.getMapData(mapId);
        if (data == null) {
            return null;
        }
        return new HeldMap(mapId, data);
    }

    private static final class HeldMap {
        final MapId mapId;
        final MapItemSavedData data;

        HeldMap(MapId mapId, MapItemSavedData data) {
            this.mapId = mapId;
            this.data = data;
        }
    }
}
