package com.killer560.hub.livemap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Special Room Guess: an unopened room the held map shows is one tile across is split-coloured with the special rooms
 * it can still be. killer560, 2026-10-15: "the map should determine if i dont have the cheater map on that this single
 * room is likley a puzzle or yellow room or trap and show like a split color even if i havent looked here before. I
 * believe noamm or devonian have this as an option for their map". Neither does - Odin's map has it ("isKnown1x1" with
 * a guess colour per tile, split two or three ways); the rule here is written from that idea, not from its code.
 *
 * <p><b>One tile across:</b> on every side the tile is either the edge of the floor or an OPENED room the map does not
 * join to it. An opened room's extent is known, so the tile cannot be part of it; an unopened or unknown neighbour
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

    /** The special room types tile {@code tile} (11x11 index) may still be - "CHAMPION", "TRAP", "PUZZLE", in that
     *  order - or an empty list when it is not an unopened one-tile room or nothing special is left. */
    public static List<String> candidates(int tile) {
        List<String> out = new ArrayList<>();
        if (!HeldMap.available() || HeldMap.roomState(tile) != HeldMap.STATE_UNOPENED || !oneTile(tile)) {
            return out;
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
                case "CHAMPION" -> champion = true;
                default -> {
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
        return out;
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
        puzzleCount = found;
        return puzzleCount;
    }
}
