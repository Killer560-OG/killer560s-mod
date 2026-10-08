package com.killer560.hub.livemap;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of what the live map already knows, for features outside this package (Wither Doors' Fairy Door).
 * Nothing here computes anything new: teammates are {@link InteractiveMapFeature#playersCached} (loaded entities first,
 * the rest from the map item's decorations in tab order), and a tile's state is {@link DungeonMapScanner#stateAt}.
 */
public final class LiveMapReads {

    /** A room tile the map item has not shown opened yet (grey with "?"), or not shown at all. */
    public static final int STATE_UNOPENED = DungeonMapScanner.STATE_UNOPENED;

    /** One teammate the map places: name and world x/z. Never includes him. */
    public record Mate(String name, double x, double z) {
    }

    private LiveMapReads() {
    }

    /** Teammates' positions, the same list the map draws its markers from (dead teammates are left out there). */
    public static List<Mate> teammates(Minecraft client) {
        List<Mate> out = new ArrayList<>();
        for (InteractiveMapFeature.MapPlayer p : InteractiveMapFeature.playersCached(client)) {
            if (!p.self()) {
                out.add(new Mate(p.name(), p.worldX(), p.worldZ()));
            }
        }
        return out;
    }

    /** Whether the dungeon map item has been read (false in the sim, p3sim, boss and before the run starts). */
    public static boolean mapItemCalibrated() {
        return DungeonMapScanner.isCalibrated();
    }

    /** The map item's state for a cell (lower = further on; {@link #STATE_UNOPENED} and above = not opened). */
    public static int mapItemState(int idx) {
        return DungeonMapScanner.stateAt(idx);
    }

    /** The map item's room colour id for a cell (82 = Fairy), or 0. */
    public static int mapItemRoomColor(int idx) {
        return DungeonMapScanner.roomColorAt(idx);
    }
}
