package com.killer560.hub.livemap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.BloodRush;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secretwaypoints.SecretWaypointsFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private static final int MAP_UNITS = 116;
    private static final int LEGEND_W = 104;
    private static final int ORANGE = 0xFFCC6600;
    private static final int LIGHT_ORANGE = 0xFFFFA040;
    private static final int PANEL = 0xD00D0D0D;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;

    // QUOI DungeonMap default room colours.
    private static final int C_NORMAL = 0xFF6B3A11;
    private static final int C_ENTRANCE = 0xFF51FF00;
    private static final int C_PUZZLE = 0xFF750085;
    private static final int C_TRAP = 0xFFD87F33;
    private static final int C_MINIBOSS = 0xFFFEDF00;
    private static final int C_BLOOD = 0xFFFF0000;
    private static final int C_FAIRY = 0xFFE000FF;
    private static final int C_RARE = 0xFFFFFF55;
    private static final int C_UNKNOWN = 0xFF414141;
    private static final int C_WITHER_DOOR = 0xFF101010;
    private static final float DARKEN = 0.4f;

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
        int size = (int) Math.min(Math.min(height - 24, width - legend - 24), MAP_UNITS * basePpu() + 12);
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
        float fit = (p[2] - p[0] - 12) / (float) MAP_UNITS;
        return Math.min(basePpu(), fit) * zoom;
    }

    private float originX() {
        int[] p = panel();
        return (p[0] + p[2]) / 2f - MAP_UNITS * ppu() / 2f + panX;
    }

    private float originY() {
        int[] p = panel();
        return (p[1] + p[3]) / 2f - MAP_UNITS * ppu() / 2f + panY;
    }

    private static int cellPos(int g) {
        return (g / 2) * 20 + (g % 2 == 1 ? 16 : 0);
    }

    private static int cellSize(int g) {
        return g % 2 == 0 ? 16 : 4;
    }

    /** Map-unit x of a world coordinate (tile centres land on tile centres). */
    private static double worldToUnits(double world) {
        return (world - LiveMapFeature.START_X) * 0.625 + 8;
    }

    private int sx(double units) {
        return Math.round(originX() + (float) units * ppu());
    }

    private int sy(double units) {
        return Math.round(originY() + (float) units * ppu());
    }

    /** @return grid index of the cell under a screen point, or -1. */
    private int cellAt(double mouseX, double mouseY) {
        int gx = unitToGrid((mouseX - originX()) / ppu());
        int gz = unitToGrid((mouseY - originY()) / ppu());
        return gx < 0 || gz < 0 ? -1 : gx + gz * LiveMapFeature.GRID;
    }

    private static int unitToGrid(double u) {
        if (u < 0) {
            return -1;
        }
        int k = (int) Math.floor(u / 20);
        double r = u - k * 20;
        int g = r < 16 ? 2 * k : 2 * k + 1;
        return g > 10 ? -1 : g;
    }

    // ------------------------------------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int[] p = panel();
        graphics.fill(p[0], p[1], p[2], p[3], PANEL);
        graphics.outline(p[0] - 1, p[1] - 1, p[2] - p[0] + 2, p[3] - p[1] + 2, ORANGE);

        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        int hoveredCell = inside(p, mouseX, mouseY) ? cellAt(mouseX, mouseY) : -1;
        int hoveredGroup = hoveredCell >= 0 ? LiveMapFeature.groupIdAt(hoveredCell) : -1;
        int hoveredDoor = hoveredCell >= 0 && hoveredGroup < 0 && isDoorCell(hoveredCell) ? hoveredCell : -1;
        int currentIdx = LiveMapFeature.currentRoomIndex();
        int currentGroup = currentIdx >= 0 ? LiveMapFeature.groupIdAt(currentIdx) : -1;
        DungeonLayout layout = DungeonLayout.capture();

        graphics.enableScissor(p[0], p[1], p[2], p[3]);
        try {
            drawDoors(graphics, layout, hoveredDoor);
            for (int gid = 0; gid < groups.size(); gid++) {
                drawRoom(graphics, groups.get(gid), gid, gid == hoveredGroup, gid == currentGroup, cfg);
            }
            if (cfg.getMapRoomLabels() != 0) {
                for (LiveMapFeature.RoomGroup group : groups) {
                    drawLabel(graphics, group, cfg);
                }
            }
            List<InteractiveMapFeature.MapPlayer> players = InteractiveMapFeature.players(client);
            InteractiveMapFeature.MapPlayer hoveredPlayer = null;
            for (int i = players.size() - 1; i >= 0; i--) {
                InteractiveMapFeature.MapPlayer mp = players.get(i);
                if (drawPlayer(graphics, mp, cfg, client, mouseX, mouseY)) {
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

    private static boolean isDoorCell(int idx) {
        LiveMapFeature.Tile t = LiveMapFeature.effectiveTile(idx);
        return t == LiveMapFeature.Tile.DOOR_NORMAL || t == LiveMapFeature.Tile.DOOR_WITHER
                || t == LiveMapFeature.Tile.DOOR_BLOOD || t == LiveMapFeature.Tile.DOOR_ENTRANCE;
    }

    private void drawDoors(GuiGraphicsExtractor graphics, DungeonLayout layout, int hoveredDoor) {
        for (int idx = 0; idx < LiveMapFeature.GRID * LiveMapFeature.GRID; idx++) {
            int type = layout.doorType(idx);
            if (type == DungeonLayout.DOOR_NONE) {
                continue;
            }
            int gx = idx % LiveMapFeature.GRID;
            int gz = idx / LiveMapFeature.GRID;
            boolean locked = layout.isLocked(idx);
            int color = switch (type) {
                case DungeonLayout.DOOR_WITHER -> locked ? C_WITHER_DOOR : C_NORMAL;
                case DungeonLayout.DOOR_BLOOD -> locked ? C_BLOOD : C_NORMAL;
                case DungeonLayout.DOOR_ENTRANCE -> C_ENTRANCE;
                default -> C_NORMAL;
            };
            if (!LiveMapFeature.isWorldScanned(idx) && DungeonMapScanner.isCalibrated()
                    && DungeonMapScanner.stateAt(idx) == DungeonMapScanner.STATE_UNOPENED) {
                color = multiply(color, 1f - DARKEN);
            }
            // Door block across the gap: 4 units along the gap, 6 across (centred on the room side).
            float ux = gx % 2 == 1 ? cellPos(gx) : cellPos(gx) + 5;
            float uz = gz % 2 == 1 ? cellPos(gz) : cellPos(gz) + 5;
            float uw = gx % 2 == 1 ? 4 : 6;
            float uh = gz % 2 == 1 ? 4 : 6;
            if (idx == hoveredDoor) {
                color = multiply(color, 1.15f);
            }
            int x0 = sx(ux);
            int y0 = sy(uz);
            int x1 = sx(ux + uw);
            int y1 = sy(uz + uh);
            graphics.fill(x0, y0, x1, y1, color);
            if (type == DungeonLayout.DOOR_WITHER && locked) {
                graphics.outline(x0, y0, x1 - x0, y1 - y0, 0xFF5A5A5A);
            }
            if (idx == hoveredDoor) {
                graphics.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xB4FFFFFF);
            }
        }
    }

    static String roomType(LiveMapFeature.RoomGroup group) {
        if (group.entry != null && group.entry.type != null) {
            return group.entry.type.toUpperCase(Locale.ROOT);
        }
        return switch (DungeonMapScanner.roomColorAt(group.mainIdx)) {
            case 18 -> "BLOOD";
            case 82 -> "FAIRY";
            case 34 -> "RARE";
            case 74 -> "CHAMPION";
            case 66 -> "PUZZLE";
            case 62 -> "TRAP";
            case 30 -> "ENTRANCE";
            case 63, 85 -> "NORMAL";
            default -> group.entry == null && !LiveMapFeature.isWorldScanned(group.mainIdx) ? "UNKNOWN" : "NORMAL";
        };
    }

    private static int typeColor(String type) {
        return switch (type) {
            case "ENTRANCE" -> C_ENTRANCE;
            case "PUZZLE" -> C_PUZZLE;
            case "TRAP" -> C_TRAP;
            case "CHAMPION", "MINIBOSS", "YELLOW" -> C_MINIBOSS;
            case "BLOOD" -> C_BLOOD;
            case "FAIRY" -> C_FAIRY;
            case "RARE" -> C_RARE;
            case "UNKNOWN" -> C_UNKNOWN;
            default -> C_NORMAL;
        };
    }

    private static String typeName(String type) {
        return switch (type) {
            case "CHAMPION", "MINIBOSS", "YELLOW" -> "Miniboss";
            case "UNKNOWN" -> "Unknown";
            default -> type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private void drawRoom(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, int gid, boolean hovered,
                          boolean current, LiveMapConfig cfg) {
        int state = LiveMapFeature.roomState(group);
        int color = typeColor(roomType(group));
        if (state == DungeonMapScanner.STATE_UNDISCOVERED || state == DungeonMapScanner.STATE_UNOPENED) {
            color = multiply(color, 1f - DARKEN);
        }
        if (current) {
            color = mix(color, cfg.getHighlightColor());
        }
        if (hovered) {
            color = multiply(color, 1.15f);
        }
        for (int c : group.cells) {
            int gx = c % LiveMapFeature.GRID;
            int gz = c / LiveMapFeature.GRID;
            if (gx % 2 == 1 && gz % 2 == 1 && !centreOfSquare(group, gid, gx, gz)) {
                continue; // an L-room's inner corner is not part of the room (QUOI lCorners)
            }
            graphics.fill(sx(cellPos(gx)), sy(cellPos(gz)), sx(cellPos(gx) + cellSize(gx)), sy(cellPos(gz) + cellSize(gz)), color);
        }
        String key = group.entry != null ? group.entry.name : null;
        // Orange outline = waypoints switched on for just this room (with Secret Waypoints itself off).
        boolean waypoints = key != null && SecretWaypointsFeature.isRoomShown(key)
                && !com.killer560.hub.secretwaypoints.SecretWaypointsConfig.getInstance().isEnabled();
        if (hovered || waypoints) {
            outlineGroup(graphics, group, gid, hovered ? 0xB4FFFFFF : LIGHT_ORANGE);
        }
    }

    private static boolean centreOfSquare(LiveMapFeature.RoomGroup group, int gid, int gx, int gz) {
        int g = LiveMapFeature.GRID;
        return gx > 0 && gz > 0 && gx < g - 1 && gz < g - 1
                && LiveMapFeature.groupIdAt(gx - 1 + gz * g) == gid && LiveMapFeature.groupIdAt(gx + 1 + gz * g) == gid
                && LiveMapFeature.groupIdAt(gx + (gz - 1) * g) == gid && LiveMapFeature.groupIdAt(gx + (gz + 1) * g) == gid;
    }

    private void outlineGroup(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, int gid, int color) {
        int g = LiveMapFeature.GRID;
        for (int c : group.cells) {
            int gx = c % g;
            int gz = c / g;
            if (gx % 2 == 1 && gz % 2 == 1 && !centreOfSquare(group, gid, gx, gz)) {
                continue;
            }
            int x0 = sx(cellPos(gx));
            int y0 = sy(cellPos(gz));
            int x1 = sx(cellPos(gx) + cellSize(gx));
            int y1 = sy(cellPos(gz) + cellSize(gz));
            if (!sameRoom(gid, gx - 1, gz)) graphics.fill(x0 - 1, y0 - 1, x0, y1 + 1, color);
            if (!sameRoom(gid, gx + 1, gz)) graphics.fill(x1, y0 - 1, x1 + 1, y1 + 1, color);
            if (!sameRoom(gid, gx, gz - 1)) graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, color);
            if (!sameRoom(gid, gx, gz + 1)) graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, color);
        }
    }

    private static boolean sameRoom(int gid, int gx, int gz) {
        int g = LiveMapFeature.GRID;
        if (gx < 0 || gz < 0 || gx >= g || gz >= g || LiveMapFeature.groupIdAt(gx + gz * g) != gid) {
            return false;
        }
        if (gx % 2 == 1 && gz % 2 == 1) {
            return LiveMapFeature.groupIdAt(gx - 1 + gz * g) == gid && LiveMapFeature.groupIdAt(gx + 1 + gz * g) == gid
                    && LiveMapFeature.groupIdAt(gx + (gz - 1) * g) == gid && LiveMapFeature.groupIdAt(gx + (gz + 1) * g) == gid;
        }
        return true;
    }

    private void drawLabel(GuiGraphicsExtractor graphics, LiveMapFeature.RoomGroup group, LiveMapConfig cfg) {
        int style = cfg.getMapRoomLabels();
        int state = LiveMapFeature.roomState(group);
        RoomEntry entry = group.entry;
        String type = roomType(group);
        int color = switch (state) {
            case DungeonMapScanner.STATE_CLEARED -> 0xFFFFFFFF;
            case DungeonMapScanner.STATE_GREEN -> 0xFF55FF55;
            case DungeonMapScanner.STATE_FAILED -> 0xFFFF5555;
            default -> 0xFFAAAAAA;
        };
        List<String> lines = new ArrayList<>();
        float boxW = (group.lShape ? 36 : cellPos(group.maxGX) + 16 - cellPos(group.minGX));
        float boxH = (group.lShape ? 16 : cellPos(group.maxGZ) + 16 - cellPos(group.minGZ));
        if (style == 1 || entry == null) {
            String mark = switch (state) {
                case DungeonMapScanner.STATE_CLEARED, DungeonMapScanner.STATE_GREEN -> "✔";
                case DungeonMapScanner.STATE_FAILED -> "✖";
                case DungeonMapScanner.STATE_UNOPENED -> "?";
                default -> null;
            };
            if (mark == null) {
                return;
            }
            lines.add(mark);
            boxW = 16;
            boxH = 16;
        } else {
            String secrets = secretsText(group);
            if (style == 2) {
                lines.add(secrets);
            } else {
                // QUOI renderName: no names on Entrance, Fairy or Blood.
                if (type.equals("ENTRANCE") || type.equals("FAIRY") || type.equals("BLOOD")) {
                    return;
                }
                java.util.Collections.addAll(lines, group.nameLines);
                if (style == 4 && entry.secrets > 0) {
                    lines.add(secrets);
                }
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        int maxW = 0;
        for (String s : lines) {
            maxW = Math.max(maxW, font.width(s));
        }
        float ppu = ppu();
        float want = 0.4f * cfg.getFontScale() * ppu;
        float fit = Math.min(boxW * ppu / Math.max(1, maxW), boxH * ppu / (lines.size() * font.lineHeight));
        float scale = Math.max(0.3f, Math.min(want, fit));
        float cx = originX() + (group.labelGX * 10 + 8) * ppu;
        float cy = originY() + (group.labelGZ * 10 + 8) * ppu;
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(scale, scale);
        int top = Math.round(-lines.size() * font.lineHeight / 2f);
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            graphics.text(font, s, -font.width(s) / 2, top + i * font.lineHeight, color, cfg.isTextShadow());
        }
        graphics.pose().popMatrix();
    }

    private static String secretsText(LiveMapFeature.RoomGroup group) {
        RoomEntry entry = group.entry;
        if (entry == null) {
            return "?";
        }
        if (entry.secrets == 0) {
            return "0";
        }
        int found = LiveMapFeature.foundSecrets(entry.name);
        if (LiveMapFeature.roomState(group) == DungeonMapScanner.STATE_GREEN) {
            found = entry.secrets;
        }
        return (found < 0 ? 0 : found) + "/" + entry.secrets;
    }

    /** @return true when the mouse is over this marker. */
    private boolean drawPlayer(GuiGraphicsExtractor graphics, InteractiveMapFeature.MapPlayer mp, LiveMapConfig cfg,
                               Minecraft client, int mouseX, int mouseY) {
        float x = originX() + (float) worldToUnits(mp.worldX()) * ppu();
        float y = originY() + (float) worldToUnits(mp.worldZ()) * ppu();
        float iconScale = cfg.getIconScale();
        int size = Math.max(6, Math.round(8 * iconScale));
        boolean head = cfg.isPlayerHeads() && mp.skin() != null;
        DungeonClass cls = mp.dungeonClass();
        int border = cfg.isClassBorderColour() && cls != null ? cls.color() : 0xFF000000;

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().rotate((float) Math.toRadians(180.0 + mp.yaw()));
        if (head) {
            graphics.fill(-size / 2 - 1, -size / 2 - 1, size / 2 + 1 + size % 2, size / 2 + 1 + size % 2, border);
            PlayerFaceExtractor.extractRenderState(graphics, mp.skin(), -size / 2, -size / 2, size);
        } else {
            int fillColor = mp.self() ? 0xFF55FF55 : (cfg.isClassBorderColour() && cls != null ? cls.color() : 0xFFFFFFFF);
            drawArrow(graphics, Math.round(size * 0.9f), fillColor);
        }
        graphics.pose().popMatrix();

        boolean showName = !mp.self() && (cfg.getPlayerNames() == 2 || (cfg.getPlayerNames() == 1 && holdingLeap(client)));
        if (showName) {
            float nameScale = 0.6f * iconScale;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y + size / 2f + 2);
            graphics.pose().scale(nameScale, nameScale);
            int nameColor = cfg.isClassBorderColour() && cls != null ? cls.color() : 0xFFFFFFFF;
            graphics.text(font, mp.name(), -font.width(mp.name()) / 2, 0, nameColor, true);
            graphics.pose().popMatrix();
        }
        return Math.abs(mouseX - x) <= size / 2f + 1 && Math.abs(mouseY - y) <= size / 2f + 1;
    }

    /** Map marker pointing towards local -y (rotated to the player's heading by the caller). */
    private static void drawArrow(GuiGraphicsExtractor graphics, int size, int color) {
        int half = Math.max(3, size / 2);
        for (int row = 0; row < half * 2; row++) {
            int w = Math.min(half, (row + 2) / 2);
            if (row > half * 2 - 3) {
                w = Math.max(1, w - 2); // notch at the tail
            }
            graphics.fill(-w, -half + row, w, -half + row + 1, 0xFF000000);
            if (w > 1) {
                graphics.fill(-w + 1, -half + row, w - 1, -half + row + 1, color);
            }
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
        String type = roomType(group);
        int typeColor = typeColor(type) & 0xFFFFFF;
        lines.add(Component.literal(entry != null ? entry.name : "Unknown Room").withColor(typeColor == 0x6B3A11 ? 0xF0E6DC : typeColor));
        lines.add(row("Type", typeName(type)));
        lines.add(row("State", stateName(LiveMapFeature.roomState(group))));
        if (entry != null) {
            if ("PUZZLE".equals(type)) {
                lines.add(row("Puzzle", entry.name));
            }
            lines.add(row("Secrets", secretsText(group)));
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
        ty = swatch(graphics, x, ty, C_NORMAL, "Normal");
        ty = swatch(graphics, x, ty, C_PUZZLE, "Puzzle");
        ty = swatch(graphics, x, ty, C_TRAP, "Trap");
        ty = swatch(graphics, x, ty, C_MINIBOSS, "Miniboss");
        ty = swatch(graphics, x, ty, C_RARE, "Rare");
        ty = swatch(graphics, x, ty, C_FAIRY, "Fairy");
        ty = swatch(graphics, x, ty, C_BLOOD, "Blood");
        ty = swatch(graphics, x, ty, C_ENTRANCE, "Entrance");
        ty = legendHeader(graphics, "Doors", x, ty + 2);
        ty = swatch(graphics, x, ty, C_WITHER_DOOR, "Wither");
        ty = swatch(graphics, x, ty, C_BLOOD, "Blood");
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
        } else if (canTeleport && isDoorCell(cell)) {
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

    // ------------------------------------------------------------------------------------------- colours

    private static int multiply(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** QUOI {@code base.mix(roomInCol.withAlpha(255), roomInCol.alpha)}. */
    private static int mix(int base, int overlay) {
        float t = ((overlay >>> 24) & 0xFF) / 255f;
        int r = Math.round(((base >> 16) & 0xFF) * (1 - t) + ((overlay >> 16) & 0xFF) * t);
        int g = Math.round(((base >> 8) & 0xFF) * (1 - t) + ((overlay >> 8) & 0xFF) * t);
        int b = Math.round((base & 0xFF) * (1 - t) + (overlay & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
