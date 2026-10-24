package com.killer560.hub.livemap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Special Room Guess: an unopened room the held map shows is one tile across is split-colored with the special rooms
 * it can still be. killer560, 2026-10-15: "the map should determine if i dont have the cheater map on that this single
 * room is likley a puzzle or yellow room or trap and show like a split color even if i havent looked here before. I
 * believe noamm or devonian have this as an option for their map". Neither does - Odin's map has it ("isKnown1x1" with
 * a guess color per tile, split two or three ways); the rule here is written from that idea, not from its code.
 *
 * <p><b>One tile across:</b> on every side the tile is either the edge of the floor or an OPENED room the map does not
 * join to it. An opened room's extent is known, so the tile cannot be part of it; an unopened or unknown neighbor
 * could be the rest of this room, and then nothing is guessed.
 *
 * <p><b>What it can still be:</b> Puzzle while fewer puzzle rooms are opened than the tab list's "Puzzles: (N)" (or
 * that count is not known), Trap while no trap room is opened, Miniboss (yellow) while no miniboss room is opened -
 * the three special rooms that are always one tile. Only what the map shows is read; nothing here looks at the world.
 */
public final class SpecialRoomGuess {

    /** Tab list "Puzzles: (3)" - Hypixel's own line, bounded so nobody's name can feed it a huge number. */
    private static final Pattern PUZZLES = Pattern.compile("^\\s*Puzzles: \\((\\d{1,2})\\)\\s*$");
    private static int puzzleCount = -1;
    private static long puzzleReadMs = 0L;

    private SpecialRoomGuess() {
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-livemap");

    /** Logging only: each tile's last logged guess as bits (1 blood, 2 champion, 4 trap, 8 puzzle); asked per frame, so
     *  a line is written only when a tile's guess changes. */
    private static final int[] LOGGED_GUESS = new int[DungeonLayout.GRID * DungeonLayout.GRID];

    private static List<String> noted(int tile, List<String> out, String why) {
        int bits = bitsOf(out);
        if (tile >= 0 && tile < LOGGED_GUESS.length && LOGGED_GUESS[tile] != bits) {
            LOGGED_GUESS[tile] = bits;
            LOGGER.info("[MapLayout] special room guess for tile {},{}: {} ({})", tile % DungeonLayout.GRID,
                    tile / DungeonLayout.GRID, out.isEmpty() ? "none" : out, why);
        }
        return out;
    }

    /** The special room types tile {@code tile} (11x11 index) may still be - "CHAMPION", "TRAP", "PUZZLE", in that
     *  order - or an empty list when it is not an unopened one-tile room or nothing special is left. */
    public static List<String> candidates(int tile) {
        List<String> out = new ArrayList<>();
        if (!HeldMap.available() || HeldMap.roomState(tile) != HeldMap.STATE_UNOPENED) {
            return noted(tile, out, "no map, or the tile is not an unopened room");
        }
        // killer560, 2026-10-19 (15:23:57): an unopened room with the red blood door beside it was split yellow/purple
        // with a "?". The Blood Room is one tile and a dead end, and the blood door is drawn once the room on its other
        // side is opened - so an unopened tile across a blood door from an opened room is the Blood Room.
        if (behindBloodDoor(tile)) {
            out.add("BLOOD");
            return noted(tile, out, "its only door is the blood door and no Blood Room is opened");
        }
        if (!oneTile(tile)) {
            return noted(tile, out, "not known to be one tile (a neighbor is unopened or joined)");
        }
        int puzzles = 0;
        boolean trap = false;
        boolean champion = false;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if ((idx % DungeonLayout.GRID) % 2 != 0 || (idx / DungeonLayout.GRID) % 2 != 0) {
                continue;
            }
            String type = HeldMap.roomType(idx);
            if (type == null) {
                continue;
            }
            switch (type) {
                case "PUZZLE" -> puzzles++;
                case "TRAP" -> trap = true;
                case "CHAMPION", "MINIBOSS", "YELLOW" -> champion = true;
                default -> {
                }
            }
        }
        // Rooms Predictive Rooms has named but nobody has opened yet count too (killer560, 2026-10-19: "it still isnt
        // auto filling yellow room ever"): a named trap or puzzle ahead is one the guessed tile cannot be, which is what
        // leaves the miniboss room as the only thing a lone tile can still be. Only predictions off this tile, unopened.
        if (com.killer560.hub.livemap.predict.PredictiveRoomsConfig.getInstance().isEnabled()) {
            java.util.Set<String> counted = new java.util.HashSet<>(); // one room can be named from two target tiles
            for (com.killer560.hub.livemap.predict.RoomPredictor.Prediction p
                    : com.killer560.hub.livemap.predict.RoomPredictor.predictions()) {
                boolean here = false;
                boolean opened = false;
                for (int t : p.certainTiles()) {
                    here |= t == tile;
                    opened |= HeldMap.roomOpened(t);
                }
                for (int t : p.uncertainTiles()) {
                    here |= t == tile;
                }
                if (here || opened || p.certainTiles().length == 0 || !counted.add(p.name())) {
                    continue; // an opened room is already counted above from the map
                }
                switch (p.type()) {
                    case "PUZZLE" -> puzzles++;
                    case "TRAP" -> trap = true;
                    case "CHAMPION", "MINIBOSS", "YELLOW" -> champion = true;
                    default -> {
                    }
                }
            }
        }
        int total = puzzleCount();
        if (!champion) {
            out.add("CHAMPION");
        }
        if (!trap) {
            out.add("TRAP");
        }
        if (total < 0 || puzzles < total) {
            out.add("PUZZLE");
        }
        if (out.isEmpty()) {
            return noted(tile, out, "every special room is accounted for");
        }
        // The reason string is only built when the guess changed (it is asked per frame).
        return LOGGED_GUESS[tile] == bitsOf(out) ? out : noted(tile, out, "one tile; puzzles counted " + puzzles + " of "
                + (total < 0 ? "unknown" : String.valueOf(total)) + ", trap seen=" + trap + ", miniboss seen=" + champion);
    }

    private static int bitsOf(List<String> out) {
        int bits = 0;
        for (String s : out) {
            bits |= switch (s) {
                case "BLOOD" -> 1;
                case "CHAMPION" -> 2;
                case "TRAP" -> 4;
                default -> 8;
            };
        }
        return bits;
    }

    /**
     * The blood door is on one side of {@code tile} and it is the tile's only door the map shows, and no Blood Room is
     * opened anywhere. A blood door has a room on both sides; the Blood Room is the dead end of the two, the one whose
     * only door it is (the room it is reached from always has the door he came in by) - the rule
     * {@code MapPainter.unopenedBloodRoom} uses for the room under it.
     */
    static boolean behindBloodDoor(int tile) {
        int gx = tile % DungeonLayout.GRID;
        int gz = tile / DungeonLayout.GRID;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        boolean found = false;
        for (int[] d : dirs) {
            int nx = gx + d[0];
            int nz = gz + d[1];
            if (nx < 0 || nz < 0 || nx >= DungeonLayout.GRID || nz >= DungeonLayout.GRID) {
                continue;
            }
            int door = HeldMap.doorType(nz * DungeonLayout.GRID + nx);
            if (door == DungeonLayout.DOOR_BLOOD) {
                found = true;
            } else if (door != DungeonLayout.DOOR_NONE) {
                return false;
            }
        }
        if (!found) {
            return false;
        }
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if ((idx % DungeonLayout.GRID) % 2 == 0 && (idx / DungeonLayout.GRID) % 2 == 0
                    && "BLOOD".equals(HeldMap.roomType(idx))) {
                return false;
            }
        }
        return true;
    }

    /** Every side is the floor's edge or an opened room the map keeps separate. */
    static boolean oneTile(int tile) {
        int gx = tile % DungeonLayout.GRID;
        int gz = tile / DungeonLayout.GRID;
        int[] floor = HeldMap.floorTiles();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = gx + 2 * d[0];
            int nz = gz + 2 * d[1];
            if (nx < 0 || nz < 0 || nx / 2 >= floor[0] || nz / 2 >= floor[1]) {
                continue;
            }
            int connector = (gz + d[1]) * DungeonLayout.GRID + gx + d[0];
            if (HeldMap.joined(connector)) {
                return false;
            }
            if (!HeldMap.roomOpened(nz * DungeonLayout.GRID + nx)) {
                return false;
            }
        }
        return true;
    }

    /** "Puzzles: (N)" from the tab list, read at most once a second; -1 while unknown. */
    public static int puzzleCount() {
        long now = System.currentTimeMillis();
        if (now - puzzleReadMs < 1000L) {
            return puzzleCount;
        }
        puzzleReadMs = now;
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return puzzleCount;
        }
        int found = -1;
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = com.killer560.hub.util.TabText.plain(info);
            if (plain == null) {
                continue;
            }
            Matcher m = PUZZLES.matcher(plain);
            if (m.matches()) {
                found = Integer.parseInt(m.group(1));
                break;
            }
        }
        if (found != puzzleCount) {
            LOGGER.info("[MapLayout] tab list puzzle count {} -> {}", puzzleCount, found);
        }
        puzzleCount = found;
        return puzzleCount;
    }
}
