package com.killer560.hub.livemap;

import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.BloodRush;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secretwaypoints.SecretWaypointsFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * The Interactive Map screen - QUOI {@code InteractiveMap.map()} / {@code MapRenderer.renderMap(autoClear = true)}:
 * QUOI's room layout (16-unit rooms, 4-unit gaps, merged multi-tile rooms, door blocks, names centred on
 * {@code textPlacement}, name colour by clear state, no names on Entrance/Fairy/Blood), current-room highlight colour,
 * hover brighten + white outline, and clicks that teleport-path (cheat builds). On top of QUOI: player heads/arrows
 * with names and class colours (QUOI's commented-out icon config / NoammAddons), hover tooltips (type, secrets, crypts,
 * puzzle, clear state, who cleared), right-click per-room secret waypoints, scroll zoom, drag pan and a legend.
 * No world dim/blur, like QUOI's {@code open(background = false)}.
 */
public class InteractiveMapScreen extends Screen {

    private static final int LEGEND_W = 104;
    private static final int ORANGE = 0xFFCC6600;
    private static final int LIGHT_ORANGE = 0xFFFFA040;
    private static final int PANEL = 0xD00D0D0D;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;

    // Room/door colours, the 16/4 layout, checkmarks and player markers all live in MapPainter now - the HUD map
    // shares exactly the same painter (killer560, 2026-09-17: "the live map hud does not look like the real
    // dungeon map"). The palette itself is configurable, defaulting to the real map's own colours.

    private final boolean openedByKey;
    private float zoom = 1f;
    private float panX = 0f;
    private float panY = 0f;
    private int pressButton = -1;
    private double pressX;
    private double pressY;
    private boolean dragMoved = false;

    public InteractiveMapScreen(boolean openedByKey) {
        super(Component.literal("Interactive Map"));
        this.openedByKey = openedByKey;
    }

    boolean openedByKey() {
        return openedByKey;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // QUOI opens the map with background = false: keep the world visible, no blur or dim.
    }

    // ------------------------------------------------------------------------------------------- geometry

    private boolean legendBeside() {
        return width >= 320;
    }

    /** Fixed panel the map lives in: {x0, y0, x1, y1}. */
    private int[] panel() {
        int legend = legendBeside() ? LEGEND_W + 8 : 0;
        int size = (int) Math.min(Math.min(height - 24, width - legend - 24), MapPainter.MAP_UNITS * basePpu() + 12);
        size = Math.max(60, size);
        int x0 = (width - legend - size) / 2;
        int y0 = (height - size) / 2;
        return new int[]{x0, y0, x0 + size, y0 + size};
    }

    private float basePpu() {
        return LiveMapConfig.getInstance().getMapScale() * 0.5f;
    }

    private float ppu() {
        int[] p = panel();
        float fit = (p[2] - p[0] - 12) / (float) MapPainter.MAP_UNITS;
        return Math.min(basePpu(), fit) * zoom;
    }

    private float originX() {
        int[] p = panel();
        return (p[0] + p[2]) / 2f - MapPainter.MAP_UNITS * ppu() / 2f + panX;
    }

    private float originY() {
        int[] p = panel();
        return (p[1] + p[3]) / 2f - MapPainter.MAP_UNITS * ppu() / 2f + panY;
    }

    /** @return grid index of the cell under a screen point, or -1. */
    private int cellAt(double mouseX, double mouseY) {
        int gx = MapPainter.unitToGrid((mouseX - originX()) / ppu());
        int gz = MapPainter.unitToGrid((mouseY - originY()) / ppu());
        return gx < 0 || gz < 0 ? -1 : gx + gz * LiveMapFeature.GRID;
    }

    // ------------------------------------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int[] p = panel();
        graphics.fill(p[0], p[1], p[2], p[3], PANEL);
        graphics.outline(p[0] - 1, p[1] - 1, p[2] - p[0] + 2, p[3] - p[1] + 2, ORANGE);
        float ox = originX();
        float oy = originY();
        float ppu = ppu();

        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        int hoveredCell = inside(p, mouseX, mouseY) ? cellAt(mouseX, mouseY) : -1;
        int hoveredGroup = hoveredCell >= 0 ? LiveMapFeature.groupIdAt(hoveredCell) : -1;
        int hoveredDoor = hoveredCell >= 0 && hoveredGroup < 0 && MapPainter.isDoorCell(hoveredCell) ? hoveredCell : -1;
        // killer560s-mod-relay task (2026-09-21): a reported room only ever occupies a cell local scanning
        // has no claim on yet, so it can never disagree with hoveredGroup/hoveredDoor above.
        PartyMapIntel.ReportedRoom hoveredReported = hoveredCell >= 0 && hoveredGroup < 0 && hoveredDoor < 0
                ? PartyMapIntel.reportedRoomAt(hoveredCell) : null;
        DungeonLayout layout = DungeonLayout.current();

        graphics.enableScissor(p[0], p[1], p[2], p[3]);
        try {
            MapPainter.drawDoors(graphics, layout, cfg, ox, oy, ppu, hoveredDoor);
            MapPainter.drawReportedDoors(graphics, cfg, ox, oy, ppu);
            for (int gid = 0; gid < groups.size(); gid++) {
                drawRoom(graphics, groups.get(gid), gid, gid == hoveredGroup, cfg, ox, oy, ppu);
            }
            // killer560s-mod-relay task (2026-09-21): teammate-reported rooms this client has not scanned
            // itself yet. Display only - not part of the click/teleport/hover-route targets below, since a
            // reported cell is not verified by this client's own scan.
            for (PartyMapIntel.ReportedRoom rr : PartyMapIntel.reportedRoomsView()) {
                MapPainter.drawReportedRoom(graphics, rr, cfg, ox, oy, ppu);
            }
            MapPainter.drawLabels(graphics, font, cfg.getMapRoomLabels(), cfg, ox, oy, ppu);
            for (PartyMapIntel.ReportedRoom rr : PartyMapIntel.reportedRoomsView()) {
                MapPainter.drawReportedLabel(graphics, font, cfg.getMapRoomLabels(), cfg, rr, ox, oy, ppu);
            }
            List<InteractiveMapFeature.MapPlayer> players = InteractiveMapFeature.playersCached(client);
            InteractiveMapFeature.MapPlayer hoveredPlayer = null;
            boolean names = cfg.getPlayerNames() == 2 || (cfg.getPlayerNames() == 1 && holdingLeap(client));
            for (int i = players.size() - 1; i >= 0; i--) {
                InteractiveMapFeature.MapPlayer mp = players.get(i);
                if (MapPainter.drawMarker(graphics, font, mp, cfg, ox, oy, ppu, 1f, cfg.isClassBorderColour(),
                        names && !mp.self(), mouseX, mouseY)) {
                    hoveredPlayer = mp;
                }
            }
            if (hoveredPlayer != null) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(hoveredPlayer.name()).withColor(hoveredPlayer.dungeonClass() != null
                        ? hoveredPlayer.dungeonClass().color() & 0xFFFFFF : 0xFFFFFF));
                if (hoveredPlayer.dungeonClass() != null) {
                    lines.add(Component.literal(hoveredPlayer.dungeonClass().displayName()).withColor(DIM & 0xFFFFFF));
                }
                graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            } else if (hoveredGroup >= 0) {
                graphics.setComponentTooltipForNextFrame(font, roomTooltip(groups.get(hoveredGroup)), mouseX, mouseY);
            } else if (hoveredDoor >= 0) {
                graphics.setComponentTooltipForNextFrame(font, doorTooltip(layout, hoveredDoor), mouseX, mouseY);
            } else if (hoveredReported != null) {
                graphics.setComponentTooltipForNextFrame(font, reportedRoomTooltip(hoveredReported), mouseX, mouseY);
            }
        } finally {
            graphics.disableScissor();
        }
        drawLegend(graphics, p, cfg);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static boolean inside(int[] p, double x, double y) {
        return x >= p[0] && y >= p[1] && x < p[2] && y < p[3];
    }

    // killer560, 2026-09-20: "remove the current-room colour changer" - the room you're standing in no longer gets
    // a separate tinted highlight, so this no longer takes a "current" flag at all.
    private void drawRoom(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, int gid, boolean hovered,
                          LiveMapConfig cfg, float ox, float oy, float ppu) {
        int color = MapPainter.roomColor(group, cfg);
        if (hovered) {
            color = MapPainter.multiply(color, 1.15f);
        }
        MapPainter.drawRoom(graphics, group, gid, color, ox, oy, ppu);
        String key = group.entry != null ? group.entry.name : null;
        // Orange outline = waypoints switched on for just this room (with Secret Waypoints itself off).
        boolean waypoints = key != null && SecretWaypointsFeature.isRoomShown(key)
                && !com.killer560.hub.secretwaypoints.SecretWaypointsConfig.getInstance().isEnabled();
        if (hovered || waypoints) {
            MapPainter.outlineGroup(graphics, group, gid, hovered ? 0xB4FFFFFF : LIGHT_ORANGE, ox, oy, ppu);
        }
    }

    private static boolean holdingLeap(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        CustomData data = client.player.getMainHandItem().get(DataComponents.CUSTOM_DATA);
        String id = data == null ? null : data.copyTag().getStringOr("id", null);
        return "SPIRIT_LEAP".equals(id) || "INFINITE_SPIRIT_LEAP".equals(id) || "HAUNT_ABILITY".equals(id);
    }

    private List<Component> roomTooltip(LiveMapFeature.RoomGroup group) {
        List<Component> lines = new ArrayList<>();
        RoomEntry entry = group.entry;
        String type = MapPainter.roomType(group);
        int typeColor = MapPainter.typeColor(type, LiveMapConfig.getInstance()) & 0xFFFFFF;
        lines.add(Component.literal(entry != null ? entry.name : "Unknown Room")
                .withColor("NORMAL".equals(type) ? 0xF0E6DC : typeColor));
        lines.add(row("Type", MapPainter.typeName(type)));
        lines.add(row("State", stateName(MapPainter.visibleState(group))));
        if (entry != null) {
            if ("PUZZLE".equals(type)) {
                lines.add(row("Puzzle", entry.name));
            }
            lines.add(row("Secrets", MapPainter.secretsText(group)));
            lines.add(row("Crypts", String.valueOf(entry.crypts)));
            if (entry.secrets > 0 && entry.secretCoords != null) {
                lines.add(row("Waypoints", SecretWaypointsFeature.isRoomShown(entry.name) ? "Shown" : "Hidden"));
            }
        }
        List<String> by = InteractiveMapFeature.clearedBy(group);
        if (by != null && !by.isEmpty()) {
            lines.add(row(by.size() == 1 ? "Cleared by" : "Stacked by", String.join(", ", by)));
        }
        return lines;
    }

    /** killer560s-mod-relay task (2026-09-21): hover text for a cell nobody on this client has scanned yet,
     *  but a teammate has - deliberately thin (name, type if resolved, who reported it), since none of the
     *  local room's richer facts (state, crypts, waypoints, cleared-by) exist for a cell this client has not
     *  actually seen. */
    private List<Component> reportedRoomTooltip(PartyMapIntel.ReportedRoom room) {
        List<Component> lines = new ArrayList<>();
        RoomEntry entry = room.entry();
        String name = entry != null && entry.name != null ? entry.name : room.reportedName();
        lines.add(Component.literal(name != null && !name.isBlank() ? name : "Unknown Room").withColor(0xF0E6DC));
        if (entry != null && entry.type != null) {
            lines.add(row("Type", MapPainter.typeName(entry.type.toUpperCase(java.util.Locale.ROOT))));
        }
        lines.add(Component.literal("Reported by " + (room.reporter() == null || room.reporter().isBlank()
                ? "a teammate" : room.reporter())).withColor(DIM & 0xFFFFFF));
        return lines;
    }

    private static List<Component> doorTooltip(DungeonLayout layout, int idx) {
        List<Component> lines = new ArrayList<>();
        String name = switch (layout.doorType(idx)) {
            case DungeonLayout.DOOR_WITHER -> "Wither Door";
            case DungeonLayout.DOOR_BLOOD -> "Blood Door";
            case DungeonLayout.DOOR_ENTRANCE -> "Entrance Door";
            default -> "Door";
        };
        lines.add(Component.literal(name).withColor(0xF0E6DC));
        int t = layout.doorType(idx);
        if (t == DungeonLayout.DOOR_WITHER || t == DungeonLayout.DOOR_BLOOD) {
            lines.add(layout.isLocked(idx) ? Component.literal("Locked").withColor(0xFF5555)
                    : Component.literal("Opened").withColor(0x55FF55));
        }
        return lines;
    }

    private static Component row(String label, String value) {
        return Component.literal(label + ": ").withColor(DIM & 0xFFFFFF)
                .append(Component.literal(value).withColor(LIGHT_ORANGE & 0xFFFFFF));
    }

    private static String stateName(int state) {
        return switch (state) {
            case DungeonMapScanner.STATE_GREEN -> "Green";
            case DungeonMapScanner.STATE_CLEARED -> "Cleared";
            case DungeonMapScanner.STATE_FAILED -> "Failed";
            case DungeonMapScanner.STATE_UNOPENED -> "Unopened";
            case DungeonMapScanner.STATE_UNDISCOVERED -> "Undiscovered";
            default -> "Discovered";
        };
    }

    private void drawLegend(GuiGraphicsExtractor graphics, int[] p, LiveMapConfig cfg) {
        if (!legendBeside()) {
            return; // too narrow: the map gets the space
        }
        int x = p[2] + 8;
        int y = p[1];
        int h = 12 * 18 + 8;
        graphics.fill(x, y, x + LEGEND_W, Math.min(height - 4, y + h), PANEL);
        graphics.outline(x - 1, y - 1, LEGEND_W + 2, Math.min(height - 4, y + h) - y + 2, ORANGE);
        int ty = y + 4;
        ty = legendHeader(graphics, "Rooms", x, ty);
        ty = swatch(graphics, x, ty, cfg.getColorNormal(), "Normal");
        ty = swatch(graphics, x, ty, cfg.getColorPuzzle(), "Puzzle");
        ty = swatch(graphics, x, ty, cfg.getColorTrap(), "Trap");
        ty = swatch(graphics, x, ty, cfg.getColorMiniboss(), "Miniboss");
        ty = swatch(graphics, x, ty, cfg.getColorRare(), "Rare");
        ty = swatch(graphics, x, ty, cfg.getColorFairy(), "Fairy");
        ty = swatch(graphics, x, ty, cfg.getColorBlood(), "Blood");
        ty = swatch(graphics, x, ty, cfg.getColorEntrance(), "Entrance");
        ty = legendHeader(graphics, "Doors", x, ty + 2);
        ty = swatch(graphics, x, ty, cfg.getColorWitherDoor(), "Wither");
        ty = swatch(graphics, x, ty, cfg.getColorBlood(), "Blood");
        ty = swatch(graphics, x, ty, 0xFF55FF55, "You");
        ty = legendHeader(graphics, "Controls", x, ty + 2);
        if (cfg.isPathingEnabled()) {
            ty = control(graphics, x, ty, "LMB", "Teleport");
        }
        ty = control(graphics, x, ty, "RMB", "Waypoints");
        ty = control(graphics, x, ty, "Scroll", "Zoom");
        ty = control(graphics, x, ty, "Drag", "Pan");
        ty = control(graphics, x, ty, "MMB", "Reset view");
        if (BloodRush.isRunning()) {
            graphics.text(font, "Blood Rush", x + 4, ty + 2, 0xFFFF5555, false);
        }
    }

    private int legendHeader(GuiGraphicsExtractor graphics, String text, int x, int y) {
        graphics.text(font, text, x + 4, y, ORANGE, false);
        return y + 11;
    }

    private int swatch(GuiGraphicsExtractor graphics, int x, int y, int color, String label) {
        graphics.fill(x + 4, y, x + 12, y + 8, color);
        graphics.outline(x + 4, y, 8, 8, 0xFF303030);
        graphics.text(font, label, x + 16, y, TEXT, false);
        return y + 10;
    }

    private int control(GuiGraphicsExtractor graphics, int x, int y, String key, String action) {
        graphics.text(font, key, x + 4, y, LIGHT_ORANGE, false);
        graphics.text(font, action, x + 42, y, TEXT, false);
        return y + 10;
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
        float oldPpu = ppu();
        double unitX = (mouseX - originX()) / oldPpu;
        double unitY = (mouseY - originY()) / oldPpu;
        zoom = Math.max(0.5f, Math.min(4f, zoom * (scrollY > 0 ? 1.15f : 1f / 1.15f)));
        float newPpu = ppu();
        panX += (float) (mouseX - (originX() + unitX * newPpu));
        panY += (float) (mouseY - (originY() + unitY * newPpu));
        return true;
    }

    private void onClick(int button, double mouseX, double mouseY) {
        if (button == 2) {
            zoom = 1f;
            panX = 0f;
            panY = 0f;
            return;
        }
        if (!inside(panel(), mouseX, mouseY)) {
            return;
        }
        int cell = cellAt(mouseX, mouseY);
        if (cell < 0) {
            return;
        }
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int gid = LiveMapFeature.groupIdAt(cell);
        if (button == 1) {
            if (gid >= 0) {
                toggleWaypoints(LiveMapFeature.groupsView().get(gid));
            }
            return;
        }
        if (button != 0) {
            return;
        }
        boolean canTeleport = cfg.isPathingEnabled() && com.killer560.hub.secrets.DungeonState.isInDungeon()
                && !LiveMapFeature.isInBoss();
        if (gid >= 0) {
            if (canTeleport) {
                DungeonLayout layout = DungeonLayout.capture();
                int room = layout.roomOfCell(cell);
                int gx = cell % LiveMapFeature.GRID;
                int gz = cell / LiveMapFeature.GRID;
                int tile = gx % 2 == 0 && gz % 2 == 0 ? cell : LiveMapFeature.groupsView().get(gid).mainIdx;
                // Clicking the room you are already standing in means "take me back to the start of my
                // route here", not "path me to this room's own spot" (killer560, 2026-09-16). Auto Routes
                // only claims the click for that exact case; every other room falls straight through.
                if (com.killer560.hub.autoroutes.AutoRoutesFeature.onMapRoomClicked(layout, room)) {
                    return;
                }
                AutoClearUtils.pathToRoom(layout, room, tile, 0);
            } else {
                toggleWaypoints(LiveMapFeature.groupsView().get(gid));
            }
        } else if (canTeleport && MapPainter.isDoorCell(cell)) {
            DungeonLayout layout = DungeonLayout.capture();
            AutoClearUtils.pathToDoor(layout, cell, cfg.isFaceDoorOnArrival());
        }
    }

    private static void toggleWaypoints(LiveMapFeature.RoomGroup group) {
        if (group.entry == null || group.entry.name == null) {
            return;
        }
        if (group.entry.secretCoords == null) {
            ModChat.send(InteractiveMapFeature.CHAT, ModChat.dim("No waypoints for "), ModChat.value(group.entry.name));
            return;
        }
        boolean shown = SecretWaypointsFeature.toggleRoom(group.entry.name);
        ModChat.send(InteractiveMapFeature.CHAT, ModChat.value(group.entry.name), ModChat.dim(" waypoints "),
                shown ? ModChat.good("shown") : ModChat.bad("hidden"));
    }

}
