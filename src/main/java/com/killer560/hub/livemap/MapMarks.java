package com.killer560.hub.livemap;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The room marks (checkmarks, the failed cross, the unopened "?") the Dungeon Map and the Interactive Map draw.
 * <p>
 * killer560, 2026-10-15: "Change checkmarks sprites to custom checkmark sprite so by default if it is off it uses
 * hypixels sprites. If it is on then it uses the one you created or they can make their own custom sprite."
 * <ul>
 *   <li><b>Hypixel's</b> (Custom Checkmark Sprite off, the default): the mark's own pixels, copied off the dungeon map
 *       ITEM - every pixel inside the room's squares that is not the room's colour, in that pixel's own map colour, at
 *       the place Hypixel put it. Exact by construction, and it is only ever what the item shows. With no map item to
 *       copy from (the sim, p3sim, a Funny Map room the item has not shown) the built-in copy of the same shapes in
 *       {@link MapPainter} is drawn instead.</li>
 *   <li><b>Mod Sprites</b>: this mod's own outlined marks, below.</li>
 *   <li><b>Your Own</b>: PNGs he draws, {@code cleared.png}, {@code green.png}, {@code failed.png} and
 *       {@code unopened.png} in the {@code killer560smod-livemap-checkmarks} folder (any size; small pixel art reads
 *       best). A missing file falls back to the mod's sprite for that mark. Re-read when a file changes.</li>
 * </ul>
 */
public final class MapMarks {

    private static final Logger LOGGER = ModLog.get("killer560smod-livemap");

    private MapMarks() {
    }

    // ------------------------------------------------------------------------------------------- Hypixel's marks

    /** Per room ({@code mainIdx}): runs {x0, y0, x1, y1} in map units and their ARGB, or an empty array for none. */
    private record Runs(float[] rects, int[] colors) {
    }

    private static final Runs NONE = new Runs(new float[0], new int[0]);
    private static final Map<Long, Runs> CACHE = new HashMap<>();
    private static int cacheVersion = Integer.MIN_VALUE;

    /** The mark pixels the map item shows inside this room, as runs in map units. Empty with no map item. */
    private static Runs hypixelRuns(LiveMapFeature.RoomGroup group) {
        byte[] px = DungeonMapScanner.pixels();
        if (px == null) {
            return NONE;
        }
        int version = DungeonMapScanner.pixelsVersion();
        if (version != cacheVersion) {
            CACHE.clear();
            cacheVersion = version;
        }
        long key = ((long) group.mainIdx << 32) | group.cells.length;
        Runs cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Runs built = build(group, px);
        CACHE.put(key, built);
        return built;
    }

    private static Runs build(LiveMapFeature.RoomGroup group, byte[] px) {
        int roomSize = DungeonMapScanner.mapRoomSize();
        int mgx = group.mainIdx % LiveMapFeature.GRID;
        int mgz = group.mainIdx / LiveMapFeature.GRID;
        int sx = DungeonMapScanner.mapPixelOf(mgx, true);
        int sz = DungeonMapScanner.mapPixelOf(mgz, false);
        if (sx < 0 || sz < 0 || sx >= 128 || sz >= 128) {
            return NONE;
        }
        int roomByte = px[sz * 128 + sx] & 0xFF;   // the room's own colour: its square's top-left pixel
        java.util.List<float[]> rects = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        for (int cell : group.cells) {
            int gx = cell % LiveMapFeature.GRID;
            int gz = cell / LiveMapFeature.GRID;
            int x0 = DungeonMapScanner.mapPixelOf(gx, true);
            int z0 = DungeonMapScanner.mapPixelOf(gz, false);
            int w = gx % 2 == 0 ? roomSize : 4;
            int h = gz % 2 == 0 ? roomSize : 4;
            float ux = MapPainter.cellPos(gx);
            float uz = MapPainter.cellPos(gz);
            float kx = MapPainter.cellSize(gx) / (float) w;
            float kz = MapPainter.cellSize(gz) / (float) h;
            for (int y = 0; y < h; y++) {
                int my = z0 + y;
                if (my < 0 || my >= 128) {
                    continue;
                }
                int x = 0;
                while (x < w) {
                    int mx = x0 + x;
                    int c = mx >= 0 && mx < 128 ? px[my * 128 + mx] & 0xFF : 0;
                    if (c == 0 || c == roomByte) {
                        x++;
                        continue;
                    }
                    int start = x;
                    while (x < w && x0 + x < 128 && (px[my * 128 + x0 + x] & 0xFF) == c) {
                        x++;
                    }
                    rects.add(new float[]{ux + start * kx, uz + y * kz, ux + x * kx, uz + (y + 1) * kz});
                    colors.add(0xFF000000 | net.minecraft.world.level.material.MapColor.getColorFromPackedId(c));
                }
            }
        }
        if (rects.isEmpty()) {
            return NONE;
        }
        float[] r = new float[rects.size() * 4];
        int[] col = new int[colors.size()];
        for (int i = 0; i < rects.size(); i++) {
            System.arraycopy(rects.get(i), 0, r, i * 4, 4);
            col[i] = colors.get(i);
        }
        return new Runs(r, col);
    }

    /** Draws Hypixel's own mark for this room off the map item. @return false when the item shows none (nothing drawn). */
    static boolean drawHypixel(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, float ox, float oy,
                               float ppu) {
        Runs runs = hypixelRuns(group);
        if (runs.colors.length == 0) {
            return false;
        }
        com.killer560.hub.hud.GuiRects r = com.killer560.hub.hud.GuiRects.begin(graphics);
        for (int i = 0; i < runs.colors.length; i++) {
            int x0 = Math.round(ox + runs.rects[i * 4] * ppu);
            int y0 = Math.round(oy + runs.rects[i * 4 + 1] * ppu);
            int x1 = Math.max(x0 + 1, Math.round(ox + runs.rects[i * 4 + 2] * ppu));
            int y1 = Math.max(y0 + 1, Math.round(oy + runs.rects[i * 4 + 3] * ppu));
            r.fill(x0, y0, x1, y1, runs.colors[i]);
        }
        r.submit();
        return true;
    }

    /** Test hook: how many mark runs the map item shows for the room holding cell {@code idx}. */
    public static int hypixelRunCount(int idx) {
        int gid = LiveMapFeature.groupIdAt(idx);
        return gid < 0 ? 0 : hypixelRuns(LiveMapFeature.groupsView().get(gid)).colors.length;
    }

    // ------------------------------------------------------------------------------------------- the mod's sprites

    // '#' the mark's colour, 'o' its outline (black), '.' nothing. Drawn by this mod for its Custom Checkmark Sprite:
    // bolder than Hypixel's, each with a one-pixel dark edge so it reads on any room colour.
    private static final String[] MOD_CHECK = {
            "........ooo",
            ".......o##o",
            "......o##o.",
            "ooo..o##o..",
            "o##oo##o...",
            ".o####o....",
            "..o##o.....",
            "...oo......",
    };
    private static final String[] MOD_CROSS = {
            "oo....oo",
            "o#o..o#o",
            ".o#oo#o.",
            "..o##o..",
            "..o##o..",
            ".o#oo#o.",
            "o#o..o#o",
            "oo....oo",
    };
    private static final String[] MOD_QUESTION = {
            ".oooooo.",
            "o######o",
            "o#oooo#o",
            ".o..o##o",
            "...o##o.",
            "...o##o.",
            "....oo..",
            "...o##o.",
            "....oo..",
    };

    /** A sprite as ARGB rows (0 = nothing). */
    record Sprite(int[][] argb) {
        int width() {
            return argb.length == 0 ? 0 : argb[0].length;
        }
    }

    private static Sprite fromRows(String[] rows, int fill) {
        int[][] out = new int[rows.length][];
        for (int r = 0; r < rows.length; r++) {
            out[r] = new int[rows[r].length()];
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                out[r][c] = ch == '#' ? fill : ch == 'o' ? 0xFF000000 : 0;
            }
        }
        return new Sprite(out);
    }

    /** The mod's own sprite for a state, in the state's colour, or null for a state with no mark. */
    /** {@link #modSprite} by state: they never change, and were rebuilt (rows of int arrays) for every mark drawn. */
    private static final Sprite[] MOD_SPRITES = new Sprite[5];

    static Sprite modSprite(int state) {
        if (state < 0 || state >= MOD_SPRITES.length) {
            return buildModSprite(state);
        }
        Sprite s = MOD_SPRITES[state];
        if (s == null) {
            s = buildModSprite(state);
            MOD_SPRITES[state] = s;
        }
        return s;
    }

    private static Sprite buildModSprite(int state) {
        int color = MapPainter.stateColor(state);
        return switch (state) {
            case DungeonMapScanner.STATE_CLEARED, DungeonMapScanner.STATE_GREEN -> fromRows(MOD_CHECK, color);
            case DungeonMapScanner.STATE_FAILED -> fromRows(MOD_CROSS, color);
            case DungeonMapScanner.STATE_UNOPENED -> fromRows(MOD_QUESTION, 0xFFDDDDDD);
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------- his own sprites

    /** The folder his own sprites live in: {@code config/killer560/dungeons/map/killer560smod-livemap-checkmarks/}. */
    public static Path spriteFolder() {
        return ModPaths.config("killer560smod-livemap-checkmarks");
    }

    private static final String[] FILES = {"green.png", "cleared.png", null, "failed.png", "unopened.png"};
    private static final Sprite[] USER = new Sprite[FILES.length];
    private static final long[] USER_STAMP = new long[FILES.length];
    private static long nextCheckMs = 0L;

    /** His sprite for a state (by {@code DungeonMapScanner.STATE_*}), or null when he has not made one. */
    static Sprite userSprite(int state) {
        if (state < 0 || state >= FILES.length || FILES[state] == null) {
            return null;
        }
        pollUserSprites();
        return USER[state];
    }

    /** Bumped whenever one of his sprites is read, changed or goes (the Dungeon Map's draw cache keys on it). */
    private static int userVersion = 0;

    /** Re-reads his sprite files at most every 2 s, as {@link #userSprite} always has. @return {@link #userVersion} */
    static int pollUserSprites() {
        long now = System.currentTimeMillis();
        if (now >= nextCheckMs) {
            nextCheckMs = now + 2000;
            for (int i = 0; i < FILES.length; i++) {
                if (FILES[i] != null) {
                    Sprite before = USER[i];
                    refresh(i);
                    if (USER[i] != before) {
                        userVersion++;
                    }
                }
            }
        }
        return userVersion;
    }

    /** Forgets the loaded sprites so the next draw reads the folder again (a profile load, a test). */
    public static void reloadUserSprites() {
        userVersion++;
        java.util.Arrays.fill(USER, null);
        java.util.Arrays.fill(USER_STAMP, 0L);
        nextCheckMs = 0L;
    }

    private static void refresh(int i) {
        Path file = spriteFolder().resolve(FILES[i]);
        try {
            if (!Files.isRegularFile(file)) {
                USER[i] = null;
                USER_STAMP[i] = 0L;
                return;
            }
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
            if (stamp == USER_STAMP[i] && USER[i] != null) {
                return;
            }
            USER_STAMP[i] = stamp;
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(file.toFile());
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0 || img.getWidth() > 64 || img.getHeight() > 64) {
                USER[i] = null;
                LOGGER.warn("[LiveMap] checkmark sprite {} is not a PNG of at most 64x64; using the mod's", file);
                return;
            }
            int[][] argb = new int[img.getHeight()][img.getWidth()];
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int c = img.getRGB(x, y);
                    argb[y][x] = (c >>> 24) < 16 ? 0 : c;
                }
            }
            USER[i] = new Sprite(argb);
        } catch (Exception e) {
            USER[i] = null;
            LOGGER.warn("[LiveMap] could not read checkmark sprite {}: {}", file, e.toString());
        }
    }

    /** Opens his sprite folder in the OS file browser, creating it first. */
    public static void openSpriteFolder() {
        try {
            Files.createDirectories(spriteFolder());
            com.killer560.hub.util.ExternalOpen.path(spriteFolder());
        } catch (Exception ignored) {
            // a folder that cannot be made or opened: nothing to show
        }
    }

    /** Draws {@code sprite} centred on (cx, cy), {@code size} wide, one fill per run of equal colour in a row. */
    static void drawSprite(GuiGraphicsExtractor graphics, Sprite sprite, float cx, float cy, float size) {
        int w = sprite.width();
        int h = sprite.argb().length;
        if (w == 0 || h == 0) {
            return;
        }
        float unit = size / Math.max(w, h);
        float x0 = cx - w * unit / 2f;
        float y0 = cy - h * unit / 2f;
        com.killer560.hub.hud.GuiRects r = com.killer560.hub.hud.GuiRects.begin(graphics);
        for (int row = 0; row < h; row++) {
            int[] line = sprite.argb()[row];
            int c = 0;
            while (c < w) {
                int color = line[c];
                if (color == 0) {
                    c++;
                    continue;
                }
                int start = c;
                while (c < w && line[c] == color) {
                    c++;
                }
                int ry0 = Math.round(y0 + row * unit);
                int ry1 = Math.max(ry0 + 1, Math.round(y0 + (row + 1) * unit));
                int rx0 = Math.round(x0 + start * unit);
                int rx1 = Math.max(rx0 + 1, Math.round(x0 + c * unit));
                r.fill(rx0, ry0, rx1, ry1, color);
            }
        }
        r.submit();
    }
}
