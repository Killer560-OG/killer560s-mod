package com.killer560.hub.mining.fossil;

import java.util.ArrayList;
import java.util.List;

/**
 * The eight Glacite fossils as the Fossil Excavator's 9x6 grid shows them, the orientations Hypixel uses for each, and the
 * "Fossil Excavation Progress" percentage one uncovered tile of each shows (one tile over the fossil's tile count). The
 * shapes, orientations and percentages are Skyblocker's ({@code dwarven/fossil/FossilTypes}); the solver around them is
 * this mod's own.
 */
public final class FossilShapes {

    public static final int WIDTH = 9;
    public static final int HEIGHT = 6;

    /** Orientations, applied as Skyblocker applies them: a flip reverses the rows, then a clockwise turn. */
    enum Turn { R0, R90, R180, R270, F0, F90, F180, F270 }

    public enum Fossil {
        CLAW("Claw", 7.7, new String[]{".#....", "####..", ".##.#.", ".#.#.#", "..#.#."}, Turn.R0, Turn.R180, Turn.F90, Turn.F180),
        TUSK("Tusk", 12.5, new String[]{"....#", ".#..#", "#...#", ".#.#.", "..#.."}, Turn.R0, Turn.R90, Turn.R180, Turn.F270),
        UGLY("Ugly", 6.2, new String[]{"..##..", ".####.", "######", ".####."}, Turn.R0, Turn.R90, Turn.R180, Turn.R270),
        HELIX("Helix", 7.1, new String[]{"#####", "#...#", "#.#.#", "#.###"}, Turn.R0, Turn.R90, Turn.R180, Turn.F270),
        WEBBED("Webbed", 10.0, new String[]{"...#...", "#..#..#", ".#.#.#.", "..###.."}, Turn.R0, Turn.F0),
        FOOTPRINT("Footprint", 7.7, new String[]{"#.#.#", "#.#.#", ".###.", ".###.", "..#.."}, Turn.R0, Turn.R90, Turn.R180, Turn.R270),
        CLUBBED("Clubbed", 9.1, new String[]{"......##", ".#....##", "#....#..", ".####..."}, Turn.R0, Turn.R180, Turn.F0, Turn.F180),
        SPINE("Spine", 8.3, new String[]{"..##..", ".####.", "######"}, Turn.R0, Turn.R90, Turn.R180, Turn.R270);

        public final String label;
        public final double percent;
        final boolean[][] grid;
        final Turn[] turns;
        public final int tiles;

        Fossil(String label, double percent, String[] rows, Turn... turns) {
            this.label = label;
            this.percent = percent;
            this.turns = turns;
            grid = new boolean[rows.length][rows[0].length()];
            int n = 0;
            for (int y = 0; y < rows.length; y++) {
                for (int x = 0; x < rows[y].length(); x++) {
                    grid[y][x] = rows[y].charAt(x) == '#';
                    if (grid[y][x]) {
                        n++;
                    }
                }
            }
            tiles = n;
        }
    }

    /** One fossil in one orientation at one place: {@code cells[y][x]} over the whole 9x6 grid. */
    public record Placement(Fossil fossil, boolean[][] cells) {
        public boolean has(int x, int y) {
            return cells[y][x];
        }
    }

    /** Every placement of every fossil in every orientation Hypixel uses. */
    public static final List<Placement> ALL = build();

    private FossilShapes() {
    }

    private static List<Placement> build() {
        List<Placement> out = new ArrayList<>();
        for (Fossil f : Fossil.values()) {
            for (Turn t : f.turns) {
                boolean[][] g = transform(f.grid, t);
                int h = g.length;
                int w = g[0].length;
                for (int ox = 0; ox + w <= WIDTH; ox++) {
                    for (int oy = 0; oy + h <= HEIGHT; oy++) {
                        boolean[][] cells = new boolean[HEIGHT][WIDTH];
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                cells[oy + y][ox + x] = g[y][x];
                            }
                        }
                        out.add(new Placement(f, cells));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    static boolean[][] transform(boolean[][] g, Turn t) {
        return switch (t) {
            case R0 -> g;
            case R90 -> rotate(g, 90);
            case R180 -> rotate(g, 180);
            case R270 -> rotate(g, 270);
            case F0 -> flip(g);
            case F90 -> rotate(flip(g), 90);
            case F180 -> rotate(flip(g), 180);
            case F270 -> rotate(flip(g), 270);
        };
    }

    /** Rows reversed. */
    static boolean[][] flip(boolean[][] g) {
        int h = g.length;
        boolean[][] out = new boolean[h][];
        for (int y = 0; y < h; y++) {
            out[y] = g[h - 1 - y].clone();
        }
        return out;
    }

    /** A clockwise turn: 90 sends (x, y) to (h-1-y, x); 270 sends it to (y, w-1-x). */
    static boolean[][] rotate(boolean[][] g, int deg) {
        int h = g.length;
        int w = g[0].length;
        if (deg == 180) {
            boolean[][] out = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[h - 1 - y][w - 1 - x] = g[y][x];
                }
            }
            return out;
        }
        boolean[][] out = new boolean[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (deg == 90) {
                    out[x][h - 1 - y] = g[y][x];
                } else {
                    out[w - 1 - x][y] = g[y][x];
                }
            }
        }
        return out;
    }
}
