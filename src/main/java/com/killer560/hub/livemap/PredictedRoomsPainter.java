package com.killer560.hub.livemap;

import com.killer560.hub.livemap.predict.PredictiveRoomsConfig;
import com.killer560.hub.livemap.predict.RoomPredictor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws Predictive Rooms ({@link RoomPredictor}) and the Special Room Guess ({@link SpecialRoomGuess}) onto the dungeon
 * map, over whatever the map would otherwise show for those tiles - and only where the map does not already name the
 * room (a room the held map shows opened, or anything on the cheat build's full map, is drawn by {@link MapPainter} as
 * before and left alone here).
 *
 * <p>A predicted room: its certain tiles in its type's colour, joined where they touch, with its name; the tiles it may
 * or may not cover grey with a "?". No doors - the map's doors come from the held map only. A guessed one-tile room:
 * split into vertical strips of the special rooms it can still be (darkened, as the guess they are), with a "?".
 */
public final class PredictedRoomsPainter {

    private static final float GUESS_DARKEN = 0.6f;

    private PredictedRoomsPainter() {
    }

    static void draw(GuiGraphicsExtractor graphics, Font font, LiveMapConfig cfg, float ox, float oy, float ppu) {
        PredictiveRoomsConfig pc = PredictiveRoomsConfig.getInstance();
        Set<Integer> drawn = new HashSet<>();
        if (pc.isEnabled()) {
            Set<String> seen = new HashSet<>();
            for (RoomPredictor.Prediction p : RoomPredictor.predictions()) {
                if (!seen.add(p.name() + java.util.Arrays.toString(p.certainTiles())) || alreadyNamed(p.certainTiles())) {
                    continue;
                }
                drawPrediction(graphics, font, cfg, p, ox, oy, ppu);
                for (int t : p.certainTiles()) {
                    drawn.add(t);
                }
                for (int t : p.uncertainTiles()) {
                    drawn.add(t);
                }
            }
        }
        if (pc.isSpecialGuess() && MapPainter.hideUnrevealed()) {
            for (int tile = 0; tile < DungeonLayout.GRID * DungeonLayout.GRID; tile++) {
                if ((tile % DungeonLayout.GRID) % 2 != 0 || (tile / DungeonLayout.GRID) % 2 != 0 || drawn.contains(tile)) {
                    continue;
                }
                List<String> types = SpecialRoomGuess.candidates(tile);
                if (!types.isEmpty()) {
                    drawGuess(graphics, font, cfg, tile, types, ox, oy, ppu);
                }
            }
        }
    }

    /** Whether the map already names the room on any of these tiles. */
    static boolean alreadyNamed(int[] tiles) {
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        for (int t : tiles) {
            int gid = LiveMapFeature.groupIdAt(t);
            if (gid < 0 || gid >= groups.size()) {
                continue;
            }
            LiveMapFeature.RoomGroup g = groups.get(gid);
            if (g.entry != null && MapPainter.isRevealed(g) && MapPainter.identityRevealed(g)) {
                return true;
            }
        }
        return false;
    }

    private static void drawPrediction(GuiGraphicsExtractor graphics, Font font, LiveMapConfig cfg,
                                       RoomPredictor.Prediction p, float ox, float oy, float ppu) {
        int color = MapPainter.multiply(MapPainter.typeColor(p.type(), cfg), 0.85f);
        Set<Integer> certain = new HashSet<>();
        for (int t : p.certainTiles()) {
            certain.add(t);
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int t : p.certainTiles()) {
            int gx = t % DungeonLayout.GRID;
            int gz = t / DungeonLayout.GRID;
            fill(graphics, ox, oy, ppu, gx, gz, color);
            // Join it to a certain neighbour on the right and below, through the gap between them.
            if (certain.contains(t + 2)) {
                fill(graphics, ox, oy, ppu, gx + 1, gz, color);
            }
            if (certain.contains(t + 2 * DungeonLayout.GRID)) {
                fill(graphics, ox, oy, ppu, gx, gz + 1, color);
            }
            minX = Math.min(minX, gx);
            minZ = Math.min(minZ, gz);
            maxX = Math.max(maxX, gx);
            maxZ = Math.max(maxZ, gz);
        }
        for (int t : p.uncertainTiles()) {
            int gx = t % DungeonLayout.GRID;
            int gz = t / DungeonLayout.GRID;
            fill(graphics, ox, oy, ppu, gx, gz, cfg.getColorUnopened());
            question(graphics, font, cfg, gx, gz, ox, oy, ppu);
        }
        if (minX == Integer.MAX_VALUE) {
            return;
        }
        float cxUnits = (MapPainter.cellPos(minX) + MapPainter.cellPos(maxX) + MapPainter.ROOM_UNITS) / 2f;
        float czUnits = (MapPainter.cellPos(minZ) + MapPainter.cellPos(maxZ) + MapPainter.ROOM_UNITS) / 2f;
        float boxW = (MapPainter.cellPos(maxX) + MapPainter.ROOM_UNITS - MapPainter.cellPos(minX)) * ppu;
        float boxH = (MapPainter.cellPos(maxZ) + MapPainter.ROOM_UNITS - MapPainter.cellPos(minZ)) * ppu;
        String[] lines = p.name().split(" ");
        MapPainter.drawFittedLines(graphics, font, lines, ox + cxUnits * ppu, oy + czUnits * ppu, boxW, boxH,
                0.4f * cfg.getFontScale() * ppu, 0xFFE0E0E0, true);
    }

    private static void drawGuess(GuiGraphicsExtractor graphics, Font font, LiveMapConfig cfg, int tile,
                                  List<String> types, float ox, float oy, float ppu) {
        int gx = tile % DungeonLayout.GRID;
        int gz = tile / DungeonLayout.GRID;
        int x0 = Math.round(ox + MapPainter.cellPos(gx) * ppu);
        int y0 = Math.round(oy + MapPainter.cellPos(gz) * ppu);
        int x1 = Math.round(ox + (MapPainter.cellPos(gx) + MapPainter.ROOM_UNITS) * ppu);
        int y1 = Math.round(oy + (MapPainter.cellPos(gz) + MapPainter.ROOM_UNITS) * ppu);
        int n = types.size();
        for (int i = 0; i < n; i++) {
            int a = x0 + (x1 - x0) * i / n;
            int b = x0 + (x1 - x0) * (i + 1) / n;
            graphics.fill(a, y0, b, y1, MapPainter.multiply(MapPainter.typeColor(types.get(i), cfg), GUESS_DARKEN));
        }
        question(graphics, font, cfg, gx, gz, ox, oy, ppu);
    }

    private static void fill(GuiGraphicsExtractor graphics, float ox, float oy, float ppu, int gx, int gz, int color) {
        int x0 = Math.round(ox + MapPainter.cellPos(gx) * ppu);
        int y0 = Math.round(oy + MapPainter.cellPos(gz) * ppu);
        int x1 = Math.round(ox + (MapPainter.cellPos(gx) + MapPainter.cellSize(gx)) * ppu);
        int y1 = Math.round(oy + (MapPainter.cellPos(gz) + MapPainter.cellSize(gz)) * ppu);
        graphics.fill(x0, y0, x1, y1, color);
    }

    private static void question(GuiGraphicsExtractor graphics, Font font, LiveMapConfig cfg, int gx, int gz,
                                 float ox, float oy, float ppu) {
        MapPainter.drawSampleMark(graphics, DungeonMapScanner.STATE_UNOPENED, cfg,
                ox + (MapPainter.cellPos(gx) + MapPainter.ROOM_UNITS / 2f) * ppu,
                oy + (MapPainter.cellPos(gz) + MapPainter.ROOM_UNITS / 2f) * ppu, ppu);
    }

    /** Test hook: what the painter would put on each tile now - "name" for a predicted tile, "?" for an unsure one,
     *  "guess:TYPE+TYPE" for a guessed one - without drawing. Grid index to text. */
    public static java.util.Map<Integer, String> plan(boolean hideUnrevealed) {
        java.util.Map<Integer, String> out = new java.util.TreeMap<>();
        PredictiveRoomsConfig pc = PredictiveRoomsConfig.getInstance();
        if (pc.isEnabled()) {
            for (RoomPredictor.Prediction p : RoomPredictor.predictions()) {
                if (alreadyNamed(p.certainTiles())) {
                    continue;
                }
                for (int t : p.certainTiles()) {
                    out.put(t, p.name());
                }
                for (int t : p.uncertainTiles()) {
                    out.putIfAbsent(t, "?");
                }
            }
        }
        if (pc.isSpecialGuess() && hideUnrevealed) {
            for (int tile = 0; tile < DungeonLayout.GRID * DungeonLayout.GRID; tile += 2) {
                if ((tile / DungeonLayout.GRID) % 2 != 0 || out.containsKey(tile)) {
                    continue;
                }
                List<String> types = SpecialRoomGuess.candidates(tile);
                if (!types.isEmpty()) {
                    out.put(tile, "guess:" + String.join("+", new ArrayList<>(types)));
                }
            }
        }
        return out;
    }
}
