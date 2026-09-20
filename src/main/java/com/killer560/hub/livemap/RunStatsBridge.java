package com.killer560.hub.livemap;

import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Public window onto the live map for {@code com.killer560.hub.runstats} - everything the room-clear
 * attribution needs lives on package-private members of {@link LiveMapFeature}, {@link DungeonMapScanner}
 * and {@link InteractiveMapFeature}, so the accessor has to sit in this package.
 *
 * <p>This is the same mechanism NoammAddons uses for its "{@code [NA] Player: 4-7 Rooms | 3 Secrets}" end-of-run
 * lines ({@code utils/dungeons/map/handlers/ClearInfoUpdater.checkSplits} + {@code map/core/RoomTile.state}
 * observer): when a room on the dungeon map item flips from open (undiscovered / discovered / unopened) to done
 * (white check = CLEARED, green = GREEN), whoever is standing in that room at that moment is credited with the
 * clear. Hypixel does <b>not</b> publish per-player room counts anywhere, so this is a derived attribution, not
 * a server-provided number - see {@code RunStatsTracker}'s class doc.
 *
 * <p>{@link InteractiveMapFeature#trackClears} already does exactly this for the interactive map's "cleared by"
 * tooltip, but only while the Interactive Map option is on, and it keeps only the last clearer per room. This
 * bridge runs the same detection on its own state so Run Stats works with the interactive map off; if the two
 * are ever merged, {@code InteractiveMapFeature.clearedBy} is the thing to fold into.
 */
public final class RunStatsBridge {

    /** One room that just finished, plus everyone who was inside it at that moment (may be empty). */
    public record Clear(String roomName, List<String> players) {
    }

    private static final Map<Integer, Integer> LAST_STATE = new HashMap<>();
    private static int seenGeneration = -1;

    private RunStatsBridge() {
    }

    /** True when the dungeon map item is calibrated - without it there are no room states at all (p3sim, boss). */
    public static boolean available() {
        return DungeonMapScanner.isCalibrated();
    }

    /** Bumped by {@link LiveMapFeature} on every grid reset (new run / world change). */
    public static int generation() {
        return LiveMapFeature.resetGeneration();
    }

    /** Drops the remembered room states, so the next {@link #pollClears} starts from scratch. */
    public static void reset() {
        LAST_STATE.clear();
        seenGeneration = LiveMapFeature.resetGeneration();
    }

    /** Self first, then teammates - names only, in the same order the live map draws them. */
    public static List<String> playerNames(Minecraft client) {
        List<String> out = new ArrayList<>();
        for (InteractiveMapFeature.MapPlayer p : InteractiveMapFeature.playersCached(client)) {
            out.add(p.name());
        }
        return out;
    }

    /**
     * Rooms that finished since the previous call, each with the players standing in them right then.
     * Empty (and cheap) when the map isn't calibrated. Call it a few ticks apart, not every tick.
     */
    public static List<Clear> pollClears(Minecraft client) {
        List<Clear> out = new ArrayList<>();
        int generation = LiveMapFeature.resetGeneration();
        if (generation != seenGeneration) {
            seenGeneration = generation;
            LAST_STATE.clear();
        }
        if (client == null || client.player == null || !DungeonMapScanner.isCalibrated()) {
            return out;
        }
        List<InteractiveMapFeature.MapPlayer> players = null;
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            int state = DungeonMapScanner.stateAt(group.mainIdx);
            Integer before = LAST_STATE.put(group.mainIdx, state);
            if (before == null || before == state) {
                continue;
            }
            boolean wasOpen = before == DungeonMapScanner.STATE_UNDISCOVERED
                    || before == DungeonMapScanner.STATE_DISCOVERED
                    || before == DungeonMapScanner.STATE_UNOPENED;
            boolean nowDone = state == DungeonMapScanner.STATE_CLEARED || state == DungeonMapScanner.STATE_GREEN;
            if (!wasOpen || !nowDone || isUncountedRoom(group)) {
                continue;
            }
            if (players == null) {
                players = InteractiveMapFeature.playersCached(client);
            }
            Set<Integer> cells = new HashSet<>();
            for (int c : group.cells) {
                cells.add(c);
            }
            List<String> inside = new ArrayList<>();
            for (InteractiveMapFeature.MapPlayer p : players) {
                int[] cell = LiveMapFeature.gridCellFor(new Vec3(p.worldX(), 70, p.worldZ()));
                if (cells.contains(cell[0] + cell[1] * LiveMapFeature.GRID)) {
                    inside.add(p.name());
                }
            }
            out.add(new Clear(InteractiveMapFeature.roomKey(group), inside));
        }
        return out;
    }

    /** NoammAddons skips fairy and entrance rooms - nobody "clears" those. */
    private static boolean isUncountedRoom(LiveMapFeature.RoomGroup group) {
        RoomEntry entry = group.entry;
        if (entry == null || entry.type == null) {
            return false;
        }
        String type = entry.type.toLowerCase(Locale.US);
        return type.equals("fairy") || type.equals("entrance");
    }
}
