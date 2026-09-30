package com.killer560.hub.mapping;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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
 */
public final class MappingFeature {

    // Real, extremely stable vanilla constant - every Minecraft version's map item has always been a
    // 128x128 grid of color-index bytes (MapItemSavedData.MAP_SIZE).
    private static final int MAP_SIZE = 128;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Logger LOGGER = ModLog.get("killer560smod-mapping");

    private MappingFeature() {
    }

    /** Real, callable-now action wired to "/killer560 mapdump" (2026-09-20: its Dungeon Map tab button was dropped
     *  when the Mapping tab was deleted - see {@code LiveMapTab}'s class doc - so the command is the only way to
     *  trigger it now).
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
