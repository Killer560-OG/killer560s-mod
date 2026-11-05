package com.killer560.hub.mining.chmap;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.livemap.MapPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

import java.util.Arrays;

/**
 * Paints the Crystal Hollows map for both the full screen ({@link CrystalHollowsMapScreen}) and the HUD element
 * ({@link ChMapHud}), so the two can never drift apart.
 *
 * <p>Everything but the player is static between changes, so it is laid out once into a flat list of rectangles and
 * labels and replayed each frame with no allocation. A new layout is made only when the view moves (pan, zoom, the HUD
 * box), the finds change ({@link ChDiscovery#version}), a setting or waypoint changes
 * ({@link CrystalHollowsMapConfig#version}) or the travel target does.
 *
 * <p>Zones (Show Zones): the four named zones are the four quadrants of the cave split at x/z 513, Jungle north-west,
 * Mithril Deposits north-east, Goblin Holdout south-west, Precursor Remnants south-east, around the Nucleus box
 * x 463..560, z 460..563 (SkyHanni's Crystal Hollows Area Walls draw the same split in the world); Magma Fields is
 * everything below y 65, so a top-down map cannot show it. The cave itself is x/z 202..823 (Skyblocker's Crystals HUD
 * maps exactly that square, which is what the HUD shows too).
 */
public final class ChMapRenderer {

    public static final double CAVE_MIN = 202.0;
    public static final double CAVE_MAX = 823.0;
    private static final double ZONE_SPLIT = 513.0;

    private static final int ORANGE = 0xFFCC6600;
    private static final int PANEL = 0xD00D0D0D;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;
    private static final int EDGE = 0x80FFA040;
    private static final int NUCLEUS_COLOR = 0xFFB44DFF;
    private static final int PLAYER_COLOR = 0xFF55FF55;
    private static final int WAYPOINT_COLOR = 0xFFCC6600;
    private static final int TARGET_COLOR = 0xFFFFFFFF;
    private static final int[] ZONE_COLORS = {0x30C050FF, 0x3040D040, 0x30E0A020, 0x304070FF};
    private static final String[] ZONE_NAMES = {"Jungle", "Mithril Deposits", "Goblin Holdout", "Precursor Remnants"};

    private final boolean labels;

    private int x0;
    private int y0;
    private int x1;
    private int y1;
    private double ox;
    private double oy;
    private double ppu;
    private double targetX = Double.NaN;
    private double targetZ = Double.NaN;
    private String targetLabel;
    private boolean dirty = true;
    private int builtFinds = -1;
    private int builtConfig = -1;
    /** The menu theme the cached layout was drawn in (its island edge takes the theme's color). */
    private int builtTheme = -1;

    /** Five ints per rectangle: x0, y0, x1, y1, color. */
    private int[] rects = new int[5 * 32];
    private int rectCount;
    private String[] labelText = new String[16];
    /** Three ints per label: x, y, color. */
    private int[] labelAt = new int[3 * 16];
    private int labelCount;

    /** @param labels draw names beside the markers (the full screen); the HUD is too small for them, as Skyblocker's is. */
    public ChMapRenderer(boolean labels) {
        this.labels = labels;
    }

    /** The panel {@code x0..x1, y0..y1}; world {@code (wx, wz)} is drawn at {@code ox + (wx - Nucleus x) * ppu}. */
    public void setView(int x0, int y0, int x1, int y1, double ox, double oy, double ppu) {
        if (x0 != this.x0 || y0 != this.y0 || x1 != this.x1 || y1 != this.y1
                || ox != this.ox || oy != this.oy || ppu != this.ppu) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.ox = ox;
            this.oy = oy;
            this.ppu = ppu;
            dirty = true;
        }
    }

    /** The whole cave fitted into a {@code size} square at {@code (x, y)}: the HUD's view. */
    public void setWholeCave(int x, int y, int size) {
        double scale = size / (CAVE_MAX - CAVE_MIN);
        setView(x, y, x + size, y + size, x + (CrystalHollowsMapScreen.NUCLEUS_X - CAVE_MIN) * scale,
                y + (CrystalHollowsMapScreen.NUCLEUS_Z - CAVE_MIN) * scale, scale);
    }

    /** The travel target, or null x/z for none. */
    public void setTarget(Double x, Double z, String label) {
        double tx = x == null || z == null ? Double.NaN : x;
        double tz = x == null || z == null ? Double.NaN : z;
        if (Double.compare(tx, targetX) != 0 || Double.compare(tz, targetZ) != 0 || !java.util.Objects.equals(label, targetLabel)) {
            targetX = tx;
            targetZ = tz;
            targetLabel = label;
            dirty = true;
        }
    }

    public int sx(double worldX) {
        return (int) Math.round(ox + (worldX - CrystalHollowsMapScreen.NUCLEUS_X) * ppu);
    }

    public int sy(double worldZ) {
        return (int) Math.round(oy + (worldZ - CrystalHollowsMapScreen.NUCLEUS_Z) * ppu);
    }

    private boolean inside(int x, int y) {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }

    /** Draws the panel, its cached layout, and the player (live position and facing) when {@code player} is given. */
    public void draw(GuiGraphicsExtractor graphics, Font font, Player player) {
        int finds = ChDiscovery.version();
        int config = CrystalHollowsMapConfig.version();
        int theme = com.killer560.hub.gui.MenuConfig.version();
        if (dirty || finds != builtFinds || config != builtConfig || theme != builtTheme) {
            builtFinds = finds;
            builtConfig = config;
            builtTheme = theme;
            dirty = false;
            layout();
        }
        graphics.fill(x0, y0, x1, y1, PANEL);
        graphics.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, ThemeAccent.accent(ORANGE));
        for (int i = 0; i < rectCount * 5; i += 5) {
            graphics.fill(rects[i], rects[i + 1], rects[i + 2], rects[i + 3], rects[i + 4]);
        }
        for (int i = 0; i < labelCount; i++) {
            graphics.text(font, labelText[i], labelAt[i * 3], labelAt[i * 3 + 1], labelAt[i * 3 + 2], true);
        }
        if (player != null) {
            int px = sx(player.getX());
            int py = sy(player.getZ());
            if (inside(px, py)) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(px, py);
                graphics.pose().rotate((float) Math.toRadians(MapPainter.headRotationDegrees(player.getYRot())));
                MapPainter.drawArrow(graphics, labels ? 8 : 6, PLAYER_COLOR);
                graphics.pose().popMatrix();
            }
        }
    }

    private void layout() {
        rectCount = 0;
        labelCount = 0;
        CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
        int c0 = sx(CAVE_MIN);
        int c1 = sx(CAVE_MAX);
        int r0 = sy(CAVE_MIN);
        int r1 = sy(CAVE_MAX);
        if (cfg.isShowZones()) {
            int mx = sx(ZONE_SPLIT);
            int mz = sy(ZONE_SPLIT);
            int[][] q = {{c0, r0, mx, mz}, {mx, r0, c1, mz}, {c0, mz, mx, r1}, {mx, mz, c1, r1}};
            for (int i = 0; i < 4; i++) {
                rect(q[i][0], q[i][1], q[i][2], q[i][3], ZONE_COLORS[i]);
                label(ZONE_NAMES[i], (q[i][0] + q[i][2]) / 2, (q[i][1] + q[i][3]) / 2, DIM, true);
            }
            rect(sx(463), sy(460), sx(560), sy(563), 0x30FFFFFF);
        }
        border(c0, r0, c1 - 1, r1 - 1, ThemeAccent.light(EDGE), true);
        marker(sx(CrystalHollowsMapScreen.NUCLEUS_X), sy(CrystalHollowsMapScreen.NUCLEUS_Z), NUCLEUS_COLOR, "Nucleus");
        if (cfg.isShowBorders() || cfg.isShowStructureWaypoints()) {
            for (ChFind f : ChDiscovery.all()) {
                // A find not established first-hand (scanned or shared) is dashed, so he can tell which he walked into.
                if (cfg.isShowBorders() && f.hasExtent()) {
                    int ax = sx(f.minX);
                    int bx = sx(f.maxX);
                    int ay = sy(f.minZ);
                    int by = sy(f.maxZ);
                    border(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by),
                            f.structure.colour, f.source == ChFind.Source.VISITED);
                }
                if (cfg.isShowStructureWaypoints()) {
                    marker(sx(f.centreX()), sy(f.centreZ()), f.structure.colour, f.structure.displayName);
                }
            }
        }
        for (CrystalHollowsWaypoint w : cfg.getWaypoints()) {
            marker(sx(w.x), sy(w.z), ThemeAccent.accent(WAYPOINT_COLOR), w.name);
        }
        if (!Double.isNaN(targetX)) {
            marker(sx(targetX), sy(targetZ), TARGET_COLOR, targetLabel != null ? targetLabel : "Target");
        }
    }

    /** A rectangle outline, solid or dashed (3 on, 3 off), clipped to the panel. */
    private void border(int ax, int ay, int bx, int by, int color, boolean solid) {
        int dash = solid ? 1 << 28 : 3;
        for (int x = ax; x <= bx; x += dash * 2) {
            int end = Math.min(bx + 1, x + dash);
            rect(x, ay, end, ay + 1, color);
            rect(x, by, end, by + 1, color);
        }
        for (int y = ay; y <= by; y += dash * 2) {
            int end = Math.min(by + 1, y + dash);
            rect(ax, y, ax + 1, end, color);
            rect(bx, y, bx + 1, end, color);
        }
    }

    private void marker(int x, int y, int color, String name) {
        if (!inside(x, y)) {
            return;
        }
        rect(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
        rect(x - 2, y - 2, x + 2, y + 2, color);
        label(name, x + 5, y - 4, TEXT, false);
    }

    private void label(String text, int x, int y, int color, boolean centred) {
        if (!labels || text == null || text.isBlank() || !inside(x, y)) {
            return;
        }
        if (labelCount == labelText.length) {
            labelText = Arrays.copyOf(labelText, labelCount * 2);
            labelAt = Arrays.copyOf(labelAt, labelCount * 6);
        }
        int w = centred ? net.minecraft.client.Minecraft.getInstance().font.width(text) / 2 : 0;
        labelText[labelCount] = text;
        labelAt[labelCount * 3] = x - w;
        labelAt[labelCount * 3 + 1] = centred ? y - 4 : y;
        labelAt[labelCount * 3 + 2] = color;
        labelCount++;
    }

    private void rect(int ax, int ay, int bx, int by, int color) {
        ax = Math.max(ax, x0);
        ay = Math.max(ay, y0);
        bx = Math.min(bx, x1);
        by = Math.min(by, y1);
        if (ax >= bx || ay >= by) {
            return;
        }
        if (rectCount * 5 == rects.length) {
            rects = Arrays.copyOf(rects, rects.length * 2);
        }
        int i = rectCount++ * 5;
        rects[i] = ax;
        rects[i + 1] = ay;
        rects[i + 2] = bx;
        rects[i + 3] = by;
        rects[i + 4] = color;
    }

    /** Rectangles in the cached layout, for the testkit. */
    public int rectCount() {
        return rectCount;
    }
}
