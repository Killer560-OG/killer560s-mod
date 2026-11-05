package com.killer560.hub.mining.chmap;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.miningqol.MiningState;
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
 *   <li>The Crystal Nucleus is drawn at its real, fixed world coordinate - {@code (512, 106, 512)}, the
 *   centre of the cave, whose bounds are x/z 202..823 (621x157x621, matching the wiki). Every other position
 *   on this map is plotted relative to that one real, fixed point. It was {@code (495.5, 106, 556.5)} until
 *   2026-09-30, which is Emissary Sisko's position rather than the Nucleus - see the constants below.</li>
 *   <li>The player marker is your REAL live position (world x/z minus the Nucleus's), updated every frame.</li>
 *   <li>Waypoints are exactly what you added, at the real coordinates you were standing at.</li>
 *   <li>The current zone name (top-left) is read live from the sidebar via
 *   {@link IslandDetector#scoreboardArea()} - the same "Area:"/sidebar read {@code IslandDetector} already
 *   uses to tell Dwarven Mines from Glacite Tunnels.</li>
 *   <li>Zones (Show Zones): the four quadrants split at x/z 513 around the Nucleus box, as SkyHanni's Area Walls
 *   draw them in the world - see {@link ChMapRenderer}. Magma Fields is a height (below y 65), not a quadrant, so
 *   the live zone-name label is what names it.</li>
 * </ul>
 * <p>
 * <b>"Interactive"</b>: clicking anywhere on the map (or right on a saved waypoint) sets that spot as the
 * active travel TARGET - drawn as a marker with a live distance and compass bearing from your position.
 * Unlike the dungeon's Interactive Map, clicking here never teleports or moves you: Crystal Hollows is open
 * 3D cave terrain, not the dungeon's fixed 11x11 room grid {@code livemap.autoclear} pathfinds over, so
 * there is no safe way to auto-walk there without real terrain pathfinding this mod does not have for CH
 * (the planned-work list in MiningWipTab records why). A compass/distance readout is something this CAN
 * do correctly today, so that's what it does - not gated behind the cheat build, since nothing here sends
 * an interaction or moves the player.
 */
public class CrystalHollowsMapScreen extends Screen {

    /**
     * The Crystal Nucleus's real, fixed world coordinate.
     *
     * <p>Was {@code 495.5, 106, 556.5}, which the class doc cited as the Nucleus and which is in fact
     * <b>Emissary Sisko's</b> position - about 47 blocks away from it. Found on 2026-09-30 while checking
     * the Crystal Hollows facts against hypixelskyblock.minecraft.wiki.
     *
     * <p>Corroborated rather than swapped on one source's say-so: the cave's own bounds are x/z 202..823
     * (Skyblocker and QUOI both hardcode that pair, and it reproduces the wiki's stated 621x157x621 exactly),
     * whose midpoint is 512.5. The Nucleus sits at the centre of the Crystal Hollows, so 512 is where it has
     * to be and 495.5 cannot have been.
     *
     * <p>Safe to change. This constant is only the map's centring reference - {@code ChMapRenderer.sx} plots
     * {@code (worldX - NUCLEUS_X)} - and every waypoint is stored in absolute world coordinates. So moving
     * it moves the VIEW, not the data: saved waypoints keep their real positions and their positions
     * relative to the player, and the Nucleus marker stops being drawn 47 blocks from the Nucleus.
     */
    public static final double NUCLEUS_X = 512.0;
    public static final double NUCLEUS_Y = 106.0;
    public static final double NUCLEUS_Z = 512.0;

    private static final int LIGHT_ORANGE = 0xFFFFA040;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;

    /** How many blocks across the panel shows at zoom = 1 / mapScale = 1. */
    private static final double BASE_VIEW_BLOCKS = 260.0;
    private static final int WAYPOINT_CLICK_RADIUS_PX = 7;

    /** Active travel target - session-only (not a persisted setting; see the class doc's "Interactive"
     *  paragraph for why this is a UI aid, not automation). Shared across re-opens of the screen. */
    private static Double targetX = null;
    private static Double targetZ = null;
    private static String targetLabel = null;

    /** Paints the map; shared with the HUD element so the two never drift. */
    private final ChMapRenderer renderer = new ChMapRenderer(true);
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
        return (p[2] - p[0]) / BASE_VIEW_BLOCKS * 1.5f * zoom;
    }

    private double originX() {
        int[] p = panel();
        return (p[0] + p[2]) / 2.0 + panX;
    }

    private double originY() {
        int[] p = panel();
        return (p[1] + p[3]) / 2.0 + panY;
    }

    /** Points the shared renderer at this frame's panel, pan and zoom. */
    private void syncView() {
        int[] p = panel();
        renderer.setView(p[0], p[1], p[2], p[3], originX(), originY(), ppu());
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
        boolean inCrystalHollows = MiningState.crystalHollows();
        syncView();
        renderer.setTarget(targetX, targetZ, targetLabel);
        renderer.draw(graphics, font, inCrystalHollows ? client.player : null);

        String zone = IslandDetector.scoreboardArea();
        String header = "Crystal Hollows Map" + (zone.isEmpty() ? "" : " - " + zone);
        graphics.text(font, header, p[0], p[1] - 12, ThemeAccent.light(LIGHT_ORANGE), true);
        if (!inCrystalHollows) {
            graphics.text(font, "Not currently in Crystal Hollows - showing last known layout", p[0], p[3] + 4, DIM, false);
        }
        var player = client.player;
        if (player != null && inCrystalHollows && targetX != null && targetZ != null) {
            double dx = targetX - player.getX();
            double dz = targetZ - player.getZ();
            graphics.text(font, String.format(Locale.US, "Target: %.0f blocks, %s", Math.sqrt(dx * dx + dz * dz),
                    compassBearing(dx, dz)), p[0], p[3] + 14, TEXT, false);
        }
        graphics.text(font, "Left-click: set target  |  Right-click: clear target  |  Scroll: zoom  |  Drag: pan",
                p[0], p[3] + (targetX != null ? 26 : 14), DIM, false);
    }

    /** The travel target, for the HUD map (null when none is set). */
    public static Double targetX() {
        return targetX;
    }

    public static Double targetZ() {
        return targetZ;
    }

    public static String targetLabel() {
        return targetLabel;
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
        pressButton = com.killer560.hub.compat.McInput.button(event);
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
        if (com.killer560.hub.compat.McInput.button(event) == pressButton) {
            if (!dragMoved) {
                onClick(com.killer560.hub.compat.McInput.button(event), event.x(), event.y());
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

        syncView();
        CrystalHollowsWaypoint nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (CrystalHollowsWaypoint w : CrystalHollowsMapConfig.getInstance().getWaypoints()) {
            double dx = renderer.sx(w.x) - mouseX;
            double dz = renderer.sy(w.z) - mouseY;
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
