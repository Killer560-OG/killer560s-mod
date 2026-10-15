package com.killer560.hub.runsummary;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * A logged run's 11x11 map, drawn at any cell size: the run log's detail view and the Dungeon Sim's saved-run cards
 * (a thumbnail) and detail page all draw it through here, so a run's map looks the same everywhere.
 *
 * <p>Rooms are coloured by the room database's type, doors are smaller darker squares in their own colour, a still
 * locked door gets an amber ring. The map keeps its own dark backdrop on every theme: the room colours are the map's,
 * not the theme's. Everything goes out as ONE batched element ({@link GuiRects}), because a page of thumbnails is a
 * few thousand squares.
 */
public final class RunMapPainter {

    public static final int ROOM_NORMAL = 0xFF8E7A5E;
    public static final int ROOM_ENTRANCE = 0xFF2E9B3E;
    public static final int ROOM_BLOOD = 0xFFB33A3A;
    public static final int ROOM_PUZZLE = 0xFF9B4FD1;
    public static final int ROOM_TRAP = 0xFFCC7722;
    public static final int ROOM_MINIBOSS = 0xFFD8C13A;
    public static final int ROOM_FAIRY = 0xFFE07FB8;
    public static final int ROOM_UNKNOWN = 0xFF4A4A4A;
    private static final int DOOR_COLOR = 0xFF5A4630;
    private static final int DOOR_WITHER_COLOR = 0xFF2B2B2B;
    private static final int DOOR_BLOOD_COLOR = 0xFFB33A3A;
    private static final int DOOR_ENTRANCE_COLOR = 0xFF2E9B3E;
    private static final int BACKDROP = 0xFF050505;

    private RunMapPainter() {
    }

    /** The drawn size of a map at {@code cell}, backdrop excluded: {@code GRID * cell}. */
    public static int size(int cell) {
        return RunRecord.MapSnapshot.GRID * cell;
    }

    /**
     * Draws {@code map} with its top-left cell at {@code x, y}, each grid cell {@code cell} units, on a backdrop
     * {@code pad} units wider each side (2 for the full map, 1 for a thumbnail).
     */
    public static void draw(GuiGraphicsExtractor g, RunRecord.MapSnapshot map, int x, int y, int cell, int pad) {
        int grid = RunRecord.MapSnapshot.GRID;
        int size = grid * cell;
        GuiRects r = GuiRects.begin(g);
        r.fill(x - pad, y - pad, x + size + pad, y + size + pad, BACKDROP);
        r.outline(x - pad - 1, y - pad - 1, size + 2 * pad + 2, size + 2 * pad + 2, MenuStyle.BLACK);
        r.outline(x - pad, y - pad, size + 2 * pad, size + 2 * pad, MenuStyle.ACCENT_EDGE);
        int gap = cell >= 6 ? 1 : 0;
        for (int c = 0; c < grid * grid; c++) {
            int cx = x + (c % grid) * cell;
            int cy = y + (c / grid) * cell;
            int room = map.roomAt(c);
            if (room >= 0) {
                RunRecord.MapRoom entry = map.room(room);
                // Rooms join their own connectors, so a multi-tile room reads as one block like the real map.
                int right = sameRoom(map, c, 1, room) ? cx + cell : cx + cell - gap;
                int bottom = sameRoom(map, c, grid, room) ? cy + cell : cy + cell - gap;
                r.fill(cx, cy, right, bottom, entry == null ? ROOM_UNKNOWN : colorForRoom(entry.type()));
                continue;
            }
            int door = map.doorAt(c);
            if (door == 0) {
                continue;
            }
            int inset = Math.max(1, cell / 4);
            int color = switch (door) {
                case 2 -> DOOR_WITHER_COLOR;
                case 3 -> DOOR_BLOOD_COLOR;
                case 4 -> DOOR_ENTRANCE_COLOR;
                default -> DOOR_COLOR;
            };
            r.fill(cx + inset, cy + inset, cx + cell - inset, cy + cell - inset, color);
            if (map.lockedAt(c) && cell >= 6) {
                r.outline(cx + inset - 1, cy + inset - 1, cell - 2 * inset + 2, cell - 2 * inset + 2, 0xFFFFAA00);
            }
        }
        r.submit();
    }

    private static boolean sameRoom(RunRecord.MapSnapshot map, int cell, int step, int room) {
        int grid = RunRecord.MapSnapshot.GRID;
        if (step == 1 && cell % grid == grid - 1) {
            return false;
        }
        return map.roomAt(cell + step) == room;
    }

    public static int colorForRoom(String type) {
        if (type == null) {
            return ROOM_NORMAL;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "entrance" -> ROOM_ENTRANCE;
            case "blood" -> ROOM_BLOOD;
            case "puzzle" -> ROOM_PUZZLE;
            case "trap" -> ROOM_TRAP;
            case "miniboss", "mini_boss", "champion" -> ROOM_MINIBOSS;
            case "fairy" -> ROOM_FAIRY;
            case "normal", "rare" -> ROOM_NORMAL;
            default -> ROOM_UNKNOWN;
        };
    }
}
