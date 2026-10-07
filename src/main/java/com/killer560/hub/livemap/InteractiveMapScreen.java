package com.killer560.hub.livemap;

import com.killer560.hub.livemap.autoclear.BloodRush;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.scorecalc.ScoreCalculatorFeature;
import com.killer560.hub.util.KeyUtil;
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
 * puzzle, clear state, who cleared), scroll zoom, right/middle-drag pan (never the left button) and a legend.
 * No world dim/blur, like QUOI's {@code open(background = false)}.
 */
public class InteractiveMapScreen extends Screen {

    /** Wide enough that the longest swatch label ("Entrance") fits its half-width column whole. */
    private static final int LEGEND_W = 120;
    private static final int ORANGE = 0xFFCC6600;
    private static final int LIGHT_ORANGE = 0xFFFFA040;
    private static final int PANEL = 0xD00D0D0D;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;
    private static final int GOOD = 0xFF55FF55;
    private static final int BAD = 0xFFFF5555;

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
    /** Last cursor position this screen was rendered with. killer560, 2026-09-29: "Remember this isn't a left or
     *  right click but based off of my key binds" - a bound KEY press carries no coordinates, so the thing it
     *  acts on is whatever the cursor is over. No mouse hook is needed to know that: a Screen is handed the
     *  cursor every frame, so caching it here is the whole mechanism. -1 until the first frame. */
    private int cursorX = -1;
    private int cursorY = -1;

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

    /**
     * Pixels per map unit. The panel is sized for {@link MapPainter#MAP_UNITS} (F7's 6x6) and the floor being
     * played is blown up to fill it - the same {@link MapPainter#floorFit} the HUD map uses, so both maps show
     * the same fixed grid for the floor and never re-fit to the rooms revealed so far.
     *
     * <p>Every screen-to-cell conversion ({@link #cellAt}, the scroll zoom) goes through this and the two origins
     * below, so clicks keep landing on the room drawn under the cursor.
     */
    private float ppu() {
        int[] p = panel();
        float fit = (p[2] - p[0] - 12) / (float) MapPainter.MAP_UNITS;
        return Math.min(basePpu(), fit) * MapPainter.floorFit(LiveMapFeature.groupsView())[0] * zoom;
    }

    /** The shown part of the grid ({@link MapPainter#gridExtent}) is centred in the panel, so a 4x5 or 6x5 floor
     *  sits in the middle of the square, and a sim floor that does not start at slot 0 is shifted back into it. This
     *  is the origin of grid unit 0, which is what {@link #cellAt} and every drawing call measure from. */
    private float originX() {
        int[] p = panel();
        int[] e = MapPainter.gridExtent(LiveMapFeature.groupsView());
        return (p[0] + p[2]) / 2f - (e[0] + e[2] / 2f) * ppu() + panX;
    }

    private float originY() {
        int[] p = panel();
        int[] e = MapPainter.gridExtent(LiveMapFeature.groupsView());
        return (p[1] + p[3]) / 2f - (e[1] + e[3] / 2f) * ppu() + panY;
    }

    /** @return grid index of the cell under a screen point, or -1. */
    private int cellAt(double mouseX, double mouseY) {
        int gx = MapPainter.unitToGrid((mouseX - originX()) / ppu());
        int gz = MapPainter.unitToGrid((mouseY - originY()) / ppu());
        return gx < 0 || gz < 0 ? -1 : gx + gz * LiveMapFeature.GRID;
    }

    /** The grid cell the cursor is over right now, or -1 when it is off the map panel or nothing has rendered
     *  yet. This is what a bound KEY press acts on - see {@link #cursorX}. */
    int cellUnderCursor() {
        if (cursorX < 0 || !inside(panel(), cursorX, cursorY)) {
            return -1;
        }
        return cellAt(cursorX, cursorY);
    }

    // ------------------------------------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        cursorX = mouseX;
        cursorY = mouseY;
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
                drawRoom(graphics, groups.get(gid), gid, false, cfg, ox, oy, ppu);
            }
            if (hoveredGroup >= 0) {
                drawHoveredTile(graphics, groups.get(hoveredGroup), hoveredCell, cfg, ox, oy, ppu);
            }
            // killer560s-mod-relay task (2026-09-21): teammate-reported rooms this client has not scanned
            // itself yet. Display only - not part of the click/teleport/hover-route targets below, since a
            // reported cell is not verified by this client's own scan.
            for (PartyMapIntel.ReportedRoom rr : PartyMapIntel.reportedRoomsView()) {
                MapPainter.drawReportedRoom(graphics, rr, cfg, ox, oy, ppu);
            }
            MapPainter.drawEtherwarpPath(graphics, cfg, ox, oy, ppu);
            MapPainter.drawLabels(graphics, font, cfg.getRoomLabels(), cfg, ox, oy, ppu);
            for (PartyMapIntel.ReportedRoom rr : PartyMapIntel.reportedRoomsView()) {
                MapPainter.drawReportedLabel(graphics, font, cfg.getRoomLabels(), cfg, rr, ox, oy, ppu);
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
        // The map says nothing about secret waypoints any more - killer560 (2026-09-27): "Do not have the map
        // show the waypoints loaded or anything just the pathing waypoints I asked you to build that the
        // interactive map will follow." The orange per-room outline meant "waypoints are on for just this room",
        // which was the readout for a toggle that no longer exists. Only the hover outline is left.
        if (hovered) {
            MapPainter.outlineGroup(graphics, group, gid, 0xB4FFFFFF, ox, oy, ppu);
        }
    }

    /**
     * Lights up only the QUARTER of the room under the cursor - the one tile a click there will path into.
     *
     * <p>killer560 (2026-10-01): "instead of kind of lighting up the whole room i am hovering have it light up the
     * quadrent of the room", and then "if the room is a 2x2 and i am hovering the top left it would highlight the
     * top left quarter of that room". The tile is chosen exactly as {@code InteractiveMapFeature}'s click chooses
     * it - the room tile under the cursor, or the room's main tile when the cursor is on a connector strip - so
     * what lights up is where the press goes. A 1x1 room is its own only tile, so it lights up whole as before.
     */
    private void drawHoveredTile(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, int cell,
                                 LiveMapConfig cfg, float ox, float oy, float ppu) {
        int g = LiveMapFeature.GRID;
        int gx = cell % g;
        int gz = cell / g;
        int tile = gx % 2 == 0 && gz % 2 == 0 ? cell : group.mainIdx;
        int tx = tile % g;
        int tz = tile / g;
        int x0 = Math.round(ox + MapPainter.cellPos(tx) * ppu);
        int y0 = Math.round(oy + MapPainter.cellPos(tz) * ppu);
        int x1 = Math.round(ox + (MapPainter.cellPos(tx) + MapPainter.cellSize(tx)) * ppu);
        int y1 = Math.round(oy + (MapPainter.cellPos(tz) + MapPainter.cellSize(tz)) * ppu);
        graphics.fill(x0, y0, x1, y1, MapPainter.multiply(MapPainter.roomColor(group, cfg), 1.15f));
        int edge = 0xB4FFFFFF;
        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, edge);
        graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, edge);
        graphics.fill(x0 - 1, y0, x0, y1, edge);
        graphics.fill(x1, y0, x1 + 1, y1, edge);
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
        if (com.killer560.hub.roomsim.SimWitherDoors.isTheoretical(idx)) {
            // Sim only - see SimWitherDoors. On Hypixel this is never true.
            lines.add(Component.literal("A wither door on a real floor").withColor(DIM & 0xFFFFFF));
        }
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

    // ------------------------------------------------------------------------------------------- legend
    //
    // killer560 (2026-10-05, screenshot): "See how cluttered that right side is, clean it up a bit." The panel was
    // a single column of seventeen swatch/control rows, its height a hand-written constant, and the S+ row drew its
    // label and its value over each other once the value outgrew the column ("S+ Secrets" under "52 more (3/55)").
    // Now: the room swatches sit two to a row, Controls collapses to two rows behind a [+]/[-] header (the choice
    // is saved), the height is MEASURED by laying the panel out once without drawing, a row whose label and value
    // do not both fit puts the value on its own row, and anything still too tall is clipped to the panel rather
    // than drawn over the world. Every text and swatch box drawn is recorded (legendTextBoxes) so the testkit can
    // check that no two overlap at any window size or Auto Scale factor.

    private static final int ROW = 10;
    private static final int PAD = 4;
    private static final int SECTION_GAP = 3;

    /** One drawn thing on the legend: what it says and the box it occupies, in this screen's coordinates. */
    private record LegendBox(String text, int x0, int y0, int x1, int y1) {
    }

    private final List<LegendBox> legendBoxes = new ArrayList<>();
    /** {x0, y0, x1, y1} of the legend panel, the Controls header and the Reset view row as last drawn; null when
     *  not drawn. A left click inside the legend acts on these and never reaches the map. */
    private int[] legendRect;
    private int[] controlsToggleRect;
    private int[] resetViewRect;

    private void drawLegend(GuiGraphicsExtractor graphics, int[] p, LiveMapConfig cfg) {
        legendBoxes.clear();
        legendRect = null;
        controlsToggleRect = null;
        resetViewRect = null;
        if (!legendBeside()) {
            return; // too narrow: the map gets the space
        }
        int x = p[2] + 8;
        int y = p[1];
        int maxBottom = height - 4;
        boolean expanded = cfg.isMapControlsExpanded();
        int bottom = layoutLegend(null, x, y, cfg, expanded);
        if (expanded && bottom > maxBottom) {
            // Too short a window for the full list: show the two-row hint rather than clip the score off.
            expanded = false;
            bottom = layoutLegend(null, x, y, cfg, false);
        }
        bottom = Math.min(maxBottom, bottom);
        graphics.fill(x, y, x + LEGEND_W, bottom, PANEL);
        graphics.outline(x - 1, y - 1, LEGEND_W + 2, bottom - y + 2, ORANGE);
        legendRect = new int[]{x, y, x + LEGEND_W, bottom};
        graphics.enableScissor(x, y, x + LEGEND_W, bottom);
        try {
            layoutLegend(graphics, x, y, cfg, expanded);
        } finally {
            graphics.disableScissor();
        }
    }

    /** Lays the legend out from {@code y}; draws only when {@code g} is non-null. @return the bottom edge. */
    private int layoutLegend(GuiGraphicsExtractor g, int x, int y, LiveMapConfig cfg, boolean expanded) {
        int ty = y + PAD;
        ty = legendHeader(g, "Rooms", null, x, ty);
        ty = swatchPair(g, x, ty, cfg.getColorNormal(), "Normal", cfg.getColorPuzzle(), "Puzzle");
        ty = swatchPair(g, x, ty, cfg.getColorTrap(), "Trap", cfg.getColorMiniboss(), "Miniboss");
        ty = swatchPair(g, x, ty, cfg.getColorRare(), "Rare", cfg.getColorFairy(), "Fairy");
        ty = swatchPair(g, x, ty, cfg.getColorBlood(), "Blood", cfg.getColorEntrance(), "Entrance");
        ty = legendHeader(g, "Doors", null, x, ty + SECTION_GAP);
        ty = swatchPair(g, x, ty, cfg.getColorWitherDoor(), "Wither", cfg.getColorBlood(), "Blood");
        ty = swatchPair(g, x, ty, 0xFF55FF55, "You", 0, null);

        int headerY = ty + SECTION_GAP;
        ty = legendHeader(g, "Controls", expanded ? "[-]" : "[+]", x, headerY);
        if (g != null) {
            controlsToggleRect = new int[]{x, headerY - 1, x + LEGEND_W, ty - 1};
        }
        // killer560: "the entire portion of interactive map is the teleport pathing" - this screen only ever
        // exists while Interactive Map (and therefore pathing) is on, so the Start bind always teleports here.
        // The bind's own name is printed rather than "LMB", since it is his to change (2026-09-29: "Remember
        // this isn't a left or right click but based off of my key binds").
        String startName = cfg.getStartKeyCode() == KeyUtil.NONE ? "LMB" : KeyUtil.bindShortName(cfg.getStartKeyCode());
        String pan = "Drag " + panButtonsName();
        if (expanded) {
            ty = control(g, x, ty, startName, "Go / retarget");
            if (cfg.isMapDoublePressStartNode()) {
                ty = control(g, x, ty, "x2", "Start node");
            }
            if (cfg.getGoSecretKeyCode() != KeyUtil.NONE) {
                ty = control(g, x, ty, KeyUtil.bindShortName(cfg.getGoSecretKeyCode()), "Go + secret");
            }
            if (cfg.getLockedDoorKeyCode() != KeyUtil.NONE) {
                ty = control(g, x, ty, KeyUtil.bindShortName(cfg.getLockedDoorKeyCode()), "Locked door");
            }
            ty = control(g, x, ty, "Scroll", "Zoom");
            ty = control(g, x, ty, pan, "Pan");
            if (!isAnyBind(cfg, 2)) {
                ty = control(g, x, ty, "MMB", "Reset view");
            }
        } else {
            ty = control(g, x, ty, startName, "Go");
            ty = control(g, x, ty, pan, "Pan");
        }
        if (viewMoved()) {
            int rowY = ty;
            ty = text(g, "Reset view", x + PAD, ty, LIGHT_ORANGE);
            if (g != null) {
                resetViewRect = new int[]{x, rowY - 1, x + LEGEND_W, ty - 1};
            }
        }
        if (BloodRush.isRunning()) {
            ty = text(g, "Blood Rush", x + PAD, ty + 2, BAD);
        }
        // killer560, 2026-09-27: "the extra info ... s+ secrets". Since 2026-10-07 the estimate is tracked whenever
        // this toggle is on (ScoreCalculatorFeature.estimateWanted), Score Calculator or not - it used to draw only
        // with Score Calculator on, so the toggle alone showed nothing. Before the run's first estimate the section
        // says so in one dim line instead of vanishing.
        if (cfg.isShowExtraInfo() && ScoreCalculatorFeature.currentResult() == null) {
            ty = legendHeader(g, "Extra Info", null, x, ty + SECTION_GAP);
            ty = text(g, fit("Waiting for the run's tab list", LEGEND_W - 2 * PAD), x + PAD, ty, DIM);
        } else if (cfg.isShowExtraInfo()) {
            ty = legendHeader(g, "Extra Info", null, x, ty + SECTION_GAP);
            ty = infoRow(g, x, ty, "Crypts", ScoreCalculatorFeature.getCrypts() + "/5", TEXT);
            ty = flagsRow(g, x, ty, new String[]{"Bat", "Mimic", "Prince"}, new boolean[]{
                    ScoreCalculatorFeature.isBatKilled(), ScoreCalculatorFeature.isMimicKilled(),
                    ScoreCalculatorFeature.isPrinceKilled()});
            // killer560, 2026-09-27: "s+ secrets assuming the current amount of crypts/status of the other
            // things, not that they are done but as is. Then it needs to assume all rooms are cleared." -
            // exactly what ScoreCalculator.calculate()'s own secretsNeeded already computes (see its doc);
            // this reuses that one live number instead of a second copy of the formula.
            ty = infoRow(g, x, ty, "S+ Secrets", ScoreCalculatorFeature.secretsNeededSummary(), LIGHT_ORANGE);
        }
        return ty + PAD - 2;
    }

    /** The mouse buttons that pan when dragged: every button except the left one and the Start bind's. */
    private static boolean pans(int button) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        return button != 0 && !isBind(cfg.getStartKeyCode(), button);
    }

    private static String panButtonsName() {
        boolean right = pans(1);
        boolean middle = pans(2);
        return right && middle ? "RMB/MMB" : right ? "RMB" : middle ? "MMB" : "mouse";
    }

    private static boolean isAnyBind(LiveMapConfig cfg, int button) {
        return isBind(cfg.getStartKeyCode(), button) || isBind(cfg.getGoSecretKeyCode(), button)
                || isBind(cfg.getLockedDoorKeyCode(), button);
    }

    private boolean viewMoved() {
        return zoom != 1f || panX != 0f || panY != 0f;
    }

    private void resetView() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
    }

    /** One line of text at {@code (x, y)}, recorded. @return the next row's y. */
    private int text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        if (g != null) {
            g.text(font, s, x, y, color, false);
            legendBoxes.add(new LegendBox(s, x, y, x + font.width(s), y + font.lineHeight - 1));
        }
        return y + ROW;
    }

    private int legendHeader(GuiGraphicsExtractor g, String label, String right, int x, int y) {
        text(g, label, x + PAD, y, ORANGE);
        if (right != null) {
            text(g, right, x + LEGEND_W - PAD - font.width(right), y, DIM);
        }
        return y + ROW + 1;
    }

    /** Two room swatches side by side; {@code label2} null for a single one. */
    private int swatchPair(GuiGraphicsExtractor g, int x, int y, int c1, String label1, int c2, String label2) {
        swatchAt(g, x + PAD, y, c1, label1, LEGEND_W / 2 - PAD - 2);
        if (label2 != null) {
            swatchAt(g, x + LEGEND_W / 2 + 1, y, c2, label2, LEGEND_W / 2 - PAD - 1);
        }
        return y + ROW;
    }

    private void swatchAt(GuiGraphicsExtractor g, int x, int y, int color, String label, int width) {
        if (g == null) {
            return;
        }
        g.fill(x, y, x + 7, y + 7, color);
        g.outline(x, y, 7, 7, 0xFF303030);
        legendBoxes.add(new LegendBox("swatch " + label, x, y, x + 7, y + 7));
        text(g, fit(label, width - 9), x + 9, y, TEXT);
    }

    private int control(GuiGraphicsExtractor g, int x, int y, String key, String action) {
        // Key left, action right-aligned; a long bind name ("Left Shift") is shortened rather than run into the
        // action, so the pair always stays inside the panel and never overlaps.
        int inner = LEGEND_W - 2 * PAD;
        int actionW = font.width(action);
        String k = fit(key, inner - actionW - 4);
        text(g, k, x + PAD, y, LIGHT_ORANGE);
        text(g, action, x + LEGEND_W - PAD - actionW, y, TEXT);
        return y + ROW;
    }

    /** One "label ... value" row, value right-aligned; a value that cannot share the row gets its own. */
    private int infoRow(GuiGraphicsExtractor g, int x, int y, String label, String value, int valueColor) {
        int inner = LEGEND_W - 2 * PAD;
        text(g, label, x + PAD, y, DIM);
        if (font.width(label) + 6 + font.width(value) > inner) {
            y += ROW;
            value = fit(value, inner);
        }
        text(g, value, x + LEGEND_W - PAD - font.width(value), y, valueColor);
        return y + ROW;
    }

    /** "Bat ✔  Mimic ✘  Prince ✘" on one row when it fits, one row each when it does not. */
    private int flagsRow(GuiGraphicsExtractor g, int x, int y, String[] labels, boolean[] done) {
        int inner = LEGEND_W - 2 * PAD;
        int total = 0;
        for (int i = 0; i < labels.length; i++) {
            total += font.width(labels[i]) + 2 + font.width(done[i] ? "✔" : "✘") + (i > 0 ? 6 : 0);
        }
        if (total > inner) {
            for (int i = 0; i < labels.length; i++) {
                y = infoRow(g, x, y, labels[i], done[i] ? "✔" : "✘", done[i] ? GOOD : BAD);
            }
            return y;
        }
        int cx = x + PAD;
        for (int i = 0; i < labels.length; i++) {
            String mark = done[i] ? "✔" : "✘";
            text(g, labels[i], cx, y, DIM);
            cx += font.width(labels[i]) + 2;
            text(g, mark, cx, y, done[i] ? GOOD : BAD);
            cx += font.width(mark) + 6;
        }
        return y + ROW;
    }

    /** {@code s}, cut down with ".." until it is at most {@code max} wide. */
    private String fit(String s, int max) {
        if (font.width(s) <= max) {
            return s;
        }
        String t = s;
        while (!t.isEmpty() && font.width(t + "..") > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "..";
    }

    /** Test hook (testkit, no-overlap check): every legend text/swatch box from the last frame, one
     *  {@code "text|x0|y0|x1|y1"} string each, in this screen's own (Auto Scaled) coordinates. */
    public List<String> legendTextBoxes() {
        List<String> out = new ArrayList<>(legendBoxes.size() + 1);
        for (LegendBox b : legendBoxes) {
            out.add(b.text() + "|" + b.x0() + "|" + b.y0() + "|" + b.x1() + "|" + b.y1());
        }
        return out;
    }

    /** Test hook: the legend panel's {x0, y0, x1, y1} as last drawn, or null. */
    public int[] legendPanelRect() {
        return legendRect == null ? null : legendRect.clone();
    }

    /** Test hook: {zoom, panX, panY}. */
    public float[] viewState() {
        return new float[]{zoom, panX, panY};
    }

    // ------------------------------------------------------------------------------------------- input
    //
    // killer560 (2026-10-05): "in interactive map if i hold left click and drag around it moves the map and it
    // shouldnt." The left button is Go / retarget and nothing else: a left press always acts on release, however
    // far the cursor moved, and never pans. Panning is a drag with any OTHER button (right or middle, minus
    // whichever one is his Start bind). A press with one of those that moves less than DRAG_THRESHOLD is still a
    // click and keeps its action (Locked door, Go + secret, Reset view); one that moves further only pans.

    private static final double DRAG_THRESHOLD = 4.0;

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
        if (pressButton >= 0 && event.button() == pressButton) {
            if (pans(pressButton)) {
                if (!dragMoved && Math.hypot(event.x() - pressX, event.y() - pressY) > DRAG_THRESHOLD) {
                    dragMoved = true;
                }
                if (dragMoved) {
                    panX += (float) dragX;
                    panY += (float) dragY;
                }
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == pressButton) {
            boolean panned = dragMoved;
            pressButton = -1;
            dragMoved = false;
            if (!panned) {
                if (event.button() == 0 && legendClick(event.x(), event.y())) {
                    return true;
                }
                onClick(event.button(), event.x(), event.y());
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    /** A left click on the legend: the Controls header toggles the list, Reset view resets. Any other spot on the
     *  legend does nothing - it is not the map, so it must not start the current room's route either. */
    private boolean legendClick(double mx, double my) {
        if (legendRect == null || !inside(legendRect, mx, my)) {
            return false;
        }
        if (controlsToggleRect != null && inside(controlsToggleRect, mx, my)) {
            LiveMapConfig cfg = LiveMapConfig.getInstance();
            cfg.setMapControlsExpanded(!cfg.isMapControlsExpanded());
            cfg.save();
        } else if (resetViewRect != null && inside(resetViewRect, mx, my)) {
            resetView();
        }
        return true;
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
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        // killer560, 2026-09-29: "Remember this isn't a left or right click but based off of my key binds."
        // A mouse button acts here when it IS one of his binds (KeyUtil stores a mouse button in the same int
        // as a key), or when it is the left button, which is what this screen has always answered to and what
        // the legend still documents. While the map is open InteractiveMapFeature deliberately stops polling a
        // mouse-bound key, so a bound button arrives exactly once, through here.
        //
        // Tested BEFORE the middle-button reset below: a bind set to the middle button would otherwise only
        // ever reset the view, and silently, since the reset owns that button unconditionally.
        if (isBind(cfg.getLockedDoorKeyCode(), button)) {
            InteractiveMapFeature.pathToLockedDoor();
            return;
        }
        if (isBind(cfg.getGoSecretKeyCode(), button)) {
            InteractiveMapFeature.onMapSecretPress(inside(panel(), mouseX, mouseY) ? cellAt(mouseX, mouseY) : -1);
            return;
        }
        boolean startButton = isBind(cfg.getStartKeyCode(), button)
                || (button == 0 && !KeyUtil.isMouseCode(cfg.getStartKeyCode()));
        if (startButton) {
            // One dispatcher for both inputs, so a click and a key press cannot drift apart - including the
            // double-press rule, which is counted per ROOM and not per input device. Off the panel it gets -1,
            // exactly what the key poll passes when the cursor is off the map.
            InteractiveMapFeature.onMapPress(inside(panel(), mouseX, mouseY) ? cellAt(mouseX, mouseY) : -1);
            return;
        }
        if (button == 2) {
            resetView();
        }
        // Anything else does nothing. The right-click "toggle this room's waypoints" is gone - killer560
        // (2026-09-27): "Remove the hardcoded. The toggle waypoint shouldn't exist." Secret waypoints are their
        // own feature with their own settings; having the map silently flip them per room was a second, hidden
        // way to control something that already has an obvious one.
    }

    private static boolean isBind(int code, int button) {
        return KeyUtil.isMouseCode(code) && KeyUtil.mouseButton(code) == button;
    }

}
