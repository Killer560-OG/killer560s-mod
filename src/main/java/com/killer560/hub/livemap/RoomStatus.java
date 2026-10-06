package com.killer560.hub.livemap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A read-only view of every room the live map knows, for features outside this package that need to choose between
 * rooms (Auto Secret). Nothing here computes anything the map does not already: the unfound count is the map's own
 * "found/total" label ({@link MapPainter#secretsText}) read as a number, the state is the map's own
 * {@link MapPainter#visibleState} (which is also how the sim paints a room), and the players are the map's markers.
 * One room, one answer - CLAUDE.md "One room must not have two answers".
 *
 * <p>Client thread only. Room indices are {@link DungeonLayout} room ids of a layout captured in the same tick.
 */
public final class RoomStatus {

    private RoomStatus() {
    }

    /**
     * @param room      the {@link DungeonLayout} room id
     * @param name      the room database name, or "Unknown"
     * @param type      the database type (PUZZLE, TRAP, BLOOD, ENTRANCE, FAIRY, NORMAL, ...), upper case, or ""
     * @param secrets   the room's secret total from the room database
     * @param unfound   secrets not yet found, as the map's label counts them (never below 0)
     * @param cleared   the map shows the room cleared (white or green check)
     * @param failed    the map shows the room failed (a failed puzzle)
     * @param mainTile  the room's top-left tile cell
     * @param tiles     every tile cell of the room
     * @param cells     every cell of the room, connectors included
     */
    public record Room(int room, String name, String type, int secrets, int unfound, boolean cleared, boolean failed,
                       int mainTile, int[] tiles, int[] cells) {
        public boolean isType(String t) {
            return type.equalsIgnoreCase(t);
        }
    }

    /** Every room the live map has grouped, identified or not. */
    public static List<Room> rooms() {
        List<Room> out = new ArrayList<>();
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        for (int gid = 0; gid < groups.size(); gid++) {
            LiveMapFeature.RoomGroup g = groups.get(gid);
            String name = g.entry != null && g.entry.name != null ? g.entry.name : "Unknown";
            String type = g.entry != null && g.entry.type != null ? g.entry.type.toUpperCase(java.util.Locale.ROOT) : "";
            int secrets = g.entry != null ? g.entry.secrets : 0;
            int state = MapPainter.visibleState(g);
            int found = 0;
            if (g.entry != null && secrets > 0) {
                String label = MapPainter.secretsText(g);
                int slash = label.indexOf('/');
                if (slash > 0) {
                    try {
                        found = Integer.parseInt(label.substring(0, slash));
                    } catch (NumberFormatException ignored) {
                        found = 0;
                    }
                }
            }
            out.add(new Room(gid, name, type, secrets, Math.max(0, secrets - found),
                    state == DungeonMapScanner.STATE_GREEN || state == DungeonMapScanner.STATE_CLEARED,
                    state == DungeonMapScanner.STATE_FAILED, g.mainIdx, g.tiles.clone(), g.cells.clone()));
        }
        return out;
    }

    /** True when a teammate (not him) stands in any of these cells - the map's own player markers. */
    public static boolean teammateInside(int[] cells) {
        Minecraft client = Minecraft.getInstance();
        for (InteractiveMapFeature.MapPlayer p : InteractiveMapFeature.playersCached(client)) {
            if (p.self()) {
                continue;
            }
            int[] cell = LiveMapFeature.gridCellFor(new Vec3(p.worldX(), 70, p.worldZ()));
            int idx = cell[0] + cell[1] * LiveMapFeature.GRID;
            for (int c : cells) {
                if (c == idx) {
                    return true;
                }
            }
        }
        return false;
    }
}
