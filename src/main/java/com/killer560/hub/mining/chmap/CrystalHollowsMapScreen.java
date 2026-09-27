package com.killer560.hub.mining.chmap;

import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Crystal Hollows Map + Interactive Map (killer560, verbatim: "interactive map for ch auto crystal
 * crystal hollows map"). Built on the SAME real signal the CH Nucleus profit tracker and the mining island
 * gate already use ({@link IslandDetector}), and structured like the dungeon's own
 * {@code livemap.InteractiveMapScreen} (fixed square panel, pan-with-drag, scroll-to-zoom, click routes to
 * an action) so the two feel consistent, per the brief.
 * <p>
 * <b>What is real on this map</b>, and what is NOT:
 * <ul>
 *   <li>The Crystal Nucleus is drawn at its real, fixed world coordinate - {@code (495.5, 106, 556.5)},
 *   confirmed from the public Hypixel Skyblock wiki (hypixelskyblock.minecraft.wiki/wiki/Crystal_Hollows,
 *   which also gives the cave's overall bounding size as 621x157x621). Every other position on this map is
 *   plotted relative to that one real, fixed point.</li>
 *   <li>The player marker is your REAL live position (world x/z minus the Nucleus's), updated every frame.</li>
 *   <li>Waypoints are exactly what you added, at the real coordinates you were standing at.</li>
 *   <li>The current zone name (top-left) is read live from the sidebar via
 *   {@link IslandDetector#scoreboardArea()} - the same "Area:"/sidebar read {@code IslandDetector} already
 *   uses to tell Dwarven Mines from Glacite Tunnels.</li>
 *   <li><b>What this does NOT draw</b>: shaped outlines for the five named zones (Jungle, Mithril Deposits,
 *   Goblin Holdout, Precursor Remnants, Magma Fields). Their approximate compass position is common
 *   Skyblock community knowledge, but this pass could not confirm a per-lobby-fixed boundary polygon for
 *   any of them from a citable source - the wiki itself only says structure placement "will always be
 *   different across different servers" - so drawing invented boxes would be showing something not
 *   actually known to be true. The live zone-name label is the honest substitute.</li>
 * </ul>
 * <p>
 * <b>"Interactive"</b>: clicking anywhere on the map (or right on a saved waypoint) sets that spot as the
 * active travel TARGET - drawn as a marker with a live distance and compass bearing from your position.
 * Unlike the dungeon's Interactive Map, clicking here never teleports or moves you: Crystal Hollows is open
 * 3D cave terrain, not the dungeon's fixed 11x11 room grid {@code livemap.autoclear} pathfinds over, so
 * there is no safe way to auto-walk there without real terrain pathfinding this mod does not have for CH
 * (see {@code mining.MiningAutomationConfig}'s class doc). A compass/distance readout is something this CAN
 * do correctly today, so that's what it does - not gated behind the cheat build, since nothing here sends
 * an interaction or moves the player.
 */
public class CrystalHollowsMapScreen extends Screen {

    /** Crystal Nucleus's real, fixed world coordinate - see the class doc for the source. */
    public static final double NUCLEUS_X = 495.5;
    public static final double NUCLEUS_Y = 106.0;
    public static final double NUCLEUS_Z = 556.5;

    private static final int ORANGE = 0xFFCC6600;
    private static final int LIGHT_ORANGE = 0xFFFFA040;
    private static final int PANEL = 0xD00D0D0D;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;
    private static final int NUCLEUS_COLOR = 0xFFB44DFF;
    private static final int PLAYER_COLOR = 0xFF55FF55;
    private static final int WAYPOINT_COLOR = 0xFFCC6600;
    private static final int TARGET_COLOR = 0xFFFFFFFF;

    /** How many blocks across the panel shows at zoom = 1 / mapScale = 1. */
    private static final double BASE_VIEW_BLOCKS = 260.0;
    private static final int WAYPOINT_CLICK_RADIUS_PX = 7;

    /** Active travel target - session-only (not a persisted setting; see the class doc's "Interactive"
     *  paragraph for why this is a UI aid, not automation). Shared across re-opens of the screen. */
    private static Double targetX = null;
    private static Double targetZ = null;
    private static String targetLabel = null;

    private float zoom = 1f;
    private float panX = 0f;
    private float panY = 0f;
    private int pressButton = -1;
    private double pressX;
    private double pressY;
    private boolean dragMoved = false;

    public CrystalHollowsMapScreen() {
        super(Component.literal("Crystal Hollows Map"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible behind the map, same as the dungeon Interactive Map.
    }

    private int[] panel() {
        int size = (int) Math.min(Math.min(height - 60, width - 40), 420);
        size = Math.max(140, size);
        int x0 = (width - size) / 2;
        int y0 = (height - size) / 2;
        return new int[]{x0, y0, x0 + size, y0 + size};
    }

    private double ppu() {
        int[] p = panel();
        return (p[2] - p[0]) / BASE_VIEW_BLOCKS * CrystalHollowsMapConfig.getInstance().getMapScale() * zoom;
    }

    private double originX() {
        int[] p = panel();
        return (p[0] + p[2]) / 2.0 + panX;
    }

    private double originY() {
        int[] p = panel();
        return (p[1] + p[3]) / 2.0 + panY;
    }

    private int screenX(double worldX) {
        return (int) Math.round(originX() + (worldX - NUCLEUS_X) * ppu());
    }

    private int screenY(double worldZ) {
        return (int) Math.round(originY() + (worldZ - NUCLEUS_Z) * ppu());
    }

    private double worldXAt(double mouseX) {
        return NUCLEUS_X + (mouseX - originX()) / ppu();
    }

    private double worldZAt(double mouseY) {
        return NUCLEUS_Z + (mouseY - originY()) / ppu();
    }

    private static boolean inside(int[] p, double x, double y) {
        return x >= p[0] && x <= p[2] && y >= p[1] && y <= p[3];
    }

    // ------------------------------------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        int[] p = panel();
        graphics.fill(p[0], p[1], p[2], p[3], PANEL);
        graphics.outline(p[0] - 1, p[1] - 1, p[2] - p[0] + 2, p[3] - p[1] + 2, ORANGE);

        String zone = IslandDetector.scoreboardArea();
        boolean inCrystalHollows = "CRYSTAL_HOLLOWS".equals(IslandDetector.graphIsland());
        String header = "Crystal Hollows Map" + (zone.isEmpty() ? "" : " - " + zone);
        graphics.text(font, header, p[0], p[1] - 12, LIGHT_ORANGE, true);
        if (!inCrystalHollows) {
            graphics.text(font, "Not currently in Crystal Hollows - showing last known layout", p[0], p[3] + 4, DIM, false);
        }

        // Nucleus marker - the one real fixed reference point everything else is drawn relative to.
        drawMarker(graphics, screenX(NUCLEUS_X), screenY(NUCLEUS_Z), NUCLEUS_COLOR, "Nucleus", p);

        for (CrystalHollowsWaypoint w : CrystalHollowsMapConfig.getInstance().getWaypoints()) {
            drawMarker(graphics, screenX(w.x), screenY(w.z), WAYPOINT_COLOR, w.name, p);
        }

        if (targetX != null && targetZ != null) {
            drawMarker(graphics, screenX(targetX), screenY(targetZ), TARGET_COLOR,
                    targetLabel != null ? targetLabel : "Target", p);
        }

        var player = client.player;
        if (player != null && inCrystalHollows) {
            int px = screenX(player.getX());
            int py = screenY(player.getZ());
            if (inside(p, px, py)) {
                graphics.fill(px - 2, py - 2, px + 2, py + 2, PLAYER_COLOR);
                graphics.outline(px - 3, py - 3, 6, 6, 0xFF000000);
            }
            if (targetX != null && targetZ != null) {
                double dx = targetX - player.getX();
                double dz = targetZ - player.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                String bearing = compassBearing(dx, dz);
                graphics.text(font, String.format(Locale.US, "Target: %.0f blocks, %s", distance, bearing),
                        p[0], p[3] + 14, TEXT, false);
            }
        }

        graphics.text(font, "Left-click: set target  |  Right-click: clear target  |  Scroll: zoom  |  Drag: pan",
                p[0], p[3] + (targetX != null ? 26 : 14), DIM, false);
    }

    private void drawMarker(GuiGraphicsExtractor graphics, int x, int y, int color, String label, int[] p) {
        if (!inside(p, x, y)) {
            return;
        }
        graphics.fill(x - 2, y - 2, x + 2, y + 2, color);
        graphics.outline(x - 3, y - 3, 6, 6, 0xFF000000);
        if (label != null && !label.isBlank()) {
            graphics.text(font, label, x + 5, y - 4, TEXT, true);
        }
    }

    /** 8-way compass bearing for a (dx, dz) offset - Minecraft's +Z is south, +X is east. */
    private static String compassBearing(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, dz));
        if (angle < 0) {
            angle += 360;
        }
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE", "S"};
        int index = (int) Math.round(angle / 45.0);
        return names[index];
    }

    // ------------------------------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        pressButton = event.button();
        pressX = event.x();
        pressY = event.y();
        dragMoved = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressButton == 0) {
            if (!dragMoved && Math.hypot(event.x() - pressX, event.y() - pressY) > 3) {
                dragMoved = true;
            }
            if (dragMoved) {
                panX += (float) dragX;
                panY += (float) dragY;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == pressButton) {
            if (!dragMoved) {
                onClick(event.button(), event.x(), event.y());
            }
            pressButton = -1;
            dragMoved = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        double oldPpu = ppu();
        double worldXBefore = worldXAt(mouseX);
        double worldZBefore = worldZAt(mouseY);
        zoom = Math.max(0.5f, Math.min(4f, zoom * (float) (scrollY > 0 ? 1.15 : 1 / 1.15)));
        double newPpu = ppu();
        panX += (float) (mouseX - (originX() + (worldXBefore - NUCLEUS_X) * newPpu));
        panY += (float) (mouseY - (originY() + (worldZBefore - NUCLEUS_Z) * newPpu));
        return true;
    }

    private void onClick(int button, double mouseX, double mouseY) {
        if (button == 2) {
            zoom = 1f;
            panX = 0f;
            panY = 0f;
            return;
        }
        int[] p = panel();
        if (!inside(p, mouseX, mouseY)) {
            return;
        }
        if (button == 1) {
            targetX = null;
            targetZ = null;
            targetLabel = null;
            ModChat.send("Crystal Hollows Map", ModChat.dim("Target cleared"));
            return;
        }
        if (button != 0) {
            return;
        }
        double worldX = worldXAt(mouseX);
        double worldZ = worldZAt(mouseY);

        CrystalHollowsWaypoint nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (CrystalHollowsWaypoint w : CrystalHollowsMapConfig.getInstance().getWaypoints()) {
            double dx = screenX(w.x) - mouseX;
            double dz = screenY(w.z) - mouseY;
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = w;
            }
        }
        if (nearest != null && nearestDistSq <= (double) WAYPOINT_CLICK_RADIUS_PX * WAYPOINT_CLICK_RADIUS_PX) {
            targetX = nearest.x;
            targetZ = nearest.z;
            targetLabel = nearest.name;
            ModChat.send("Crystal Hollows Map", ModChat.text("Target set: "), ModChat.value(nearest.name));
            return;
        }
        targetX = worldX;
        targetZ = worldZ;
        targetLabel = "Target";
        ModChat.send("Crystal Hollows Map", ModChat.text("Target set at "),
                ModChat.value(String.format(Locale.US, "%.0f, %.0f", worldX, worldZ)));
    }
}
