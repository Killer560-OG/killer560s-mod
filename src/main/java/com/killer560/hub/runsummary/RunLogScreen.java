package com.killer560.hub.runsummary;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The run log, opened with {@code /log} (killer560, 2026-09-20: "can you add a run log section. This should
 * log the map that was shown, who was in the party, each players stats and whatnot/class, and the splits of
 * the run. Adapt this from the run summary essentially and make it its own custom menu from doing /log. Make
 * a selection button for kuudra or dungeons, then in dungeons have it split between all the floors and make
 * the kuudra one in development.")
 * <p>
 * Adapted from the run summary rather than duplicating it: every run shown here is a {@link RunRecord} out of
 * {@link RunHistoryStore}, the same records the Run Summary tab lists, and the text lines come from
 * {@link RunSummaryFeature#summaryLines} so the two can never describe a run differently. The map and the
 * per-player party rows are the two things this view adds, and they are stored on the record itself.
 * <p>
 * Party rows are keyed on UUID and show the CURRENT IGN (see {@link RunSummaryFeature#currentIgn}), so a name
 * change never orphans a logged run.
 */
public class RunLogScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
    private static final int ROW_H = 13;
    private static final int MAP_CELL = 9;

    /** Room fill colours by the room database's own type string. */
    private static final int ROOM_NORMAL = 0xFF8E7A5E;
    private static final int ROOM_ENTRANCE = 0xFF2E9B3E;
    private static final int ROOM_BLOOD = 0xFFB33A3A;
    private static final int ROOM_PUZZLE = 0xFF9B4FD1;
    private static final int ROOM_TRAP = 0xFFCC7722;
    private static final int ROOM_MINIBOSS = 0xFFD8C13A;
    private static final int ROOM_FAIRY = 0xFFE07FB8;
    private static final int ROOM_UNKNOWN = 0xFF4A4A4A;
    private static final int DOOR_COLOR = 0xFF5A4630;
    private static final int DOOR_WITHER_COLOR = 0xFF2B2B2B;
    private static final int DOOR_BLOOD_COLOR = 0xFFB33A3A;
    private static final int DOOR_ENTRANCE_COLOR = 0xFF2E9B3E;

    private enum Mode { DUNGEONS, KUUDRA }

    private final Screen parent;
    private Mode mode = Mode.DUNGEONS;
    private String floorFilter = "All";
    private List<String> floors = List.of("All");
    private List<RunRecord> visible = List.of();
    private RunRecord selected = null;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int scroll = 0;

    public RunLogScreen(Screen parent) {
        super(Component.literal("Run Log"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(380, Math.min((int) (this.width * 0.8), 620)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.85), 440)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listX = panelX + 6;
        listY = panelY + 58;
        listW = panelW - 12;
        listH = panelH - 58 - 8;

        refilter();

        int bw = Math.min(100, (panelW - 24) / 4);
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal((mode == Mode.DUNGEONS ? "§6" : "§f") + "Dungeons"), btn -> {
                    mode = Mode.DUNGEONS;
                    selected = null;
                    scroll = 0;
                    rebuildWidgets();
                }).bounds(panelX + 6, panelY + 34, bw, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal((mode == Mode.KUUDRA ? "§6" : "§f") + "Kuudra"), btn -> {
                    mode = Mode.KUUDRA;
                    selected = null;
                    scroll = 0;
                    rebuildWidgets();
                }).bounds(panelX + 6 + bw + 4, panelY + 34, bw, 18).build());

        if (mode == Mode.DUNGEONS) {
            if (selected == null) {
                addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Floor: " + floorFilter), btn -> {
                    int i = floors.indexOf(floorFilter);
                    floorFilter = floors.get((i + 1 + floors.size()) % floors.size());
                    scroll = 0;
                    rebuildWidgets();
                }).bounds(panelX + panelW - bw - 6, panelY + 34, bw, 18).build());
            } else {
                addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
                    selected = null;
                    scroll = 0;
                    rebuildWidgets();
                }).bounds(panelX + panelW - bw - 6, panelY + 34, bw, 18).build());
            }
        }
    }

    private void refilter() {
        List<RunRecord> all = RunHistoryStore.runs();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("All");
        for (RunRecord r : all) {
            keys.add(RunHistoryStore.floorKey(r));
        }
        floors = new ArrayList<>(keys);
        if (!floors.contains(floorFilter)) {
            floorFilter = "All";
        }
        List<RunRecord> out = new ArrayList<>();
        for (RunRecord r : all) {
            if (floorFilter.equals("All") || RunHistoryStore.floorKey(r).equals(floorFilter)) {
                out.add(r);
            }
        }
        visible = out;
    }

    // ---- interaction -----------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (mode == Mode.DUNGEONS && selected == null && event.button() == 0) {
            RunRecord hit = runAt(event.x(), event.y());
            if (hit != null) {
                selected = hit;
                scroll = 0;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private RunRecord runAt(double mx, double my) {
        if (mx < listX || mx > listX + listW || my < listY || my >= listY + listH) {
            return null;
        }
        int i = (int) ((my - listY + scroll) / ROW_H);
        return i >= 0 && i < visible.size() ? visible.get(i) : null;
    }

    private int contentHeight() {
        if (mode == Mode.KUUDRA) {
            return 0;
        }
        return selected == null ? visible.size() * ROW_H : detailLines(selected).size() * 10 + mapHeight(selected);
    }

    private int mapHeight(RunRecord run) {
        return run.map() == null || run.map().isEmpty() ? 0 : RunRecord.MapSnapshot.GRID * MAP_CELL + 14;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- rendering -------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.outline(panelX, panelY, panelW, panelH, BORDER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        graphics.outline(panelX, panelY, panelW, 30, BORDER);
        graphics.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ACCENT);
        graphics.text(this.font, "Run Log", panelX + 10, panelY + 11, ACCENT, false);
        String right = mode == Mode.KUUDRA ? "Kuudra" : visible.size() + " run" + (visible.size() == 1 ? "" : "s");
        graphics.text(this.font, right, panelX + panelW - 10 - this.font.width(right), panelY + 11,
                0xFF000000 | ModChat.DIM, false);

        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFF080808);
        graphics.outline(listX - 1, listY - 1, listW + 2, listH + 2, BORDER);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            if (mode == Mode.KUUDRA) {
                graphics.text(this.font, "Kuudra run logging is in development.", listX + 6, listY + 8,
                        0xFF000000 | ModChat.LIGHT_ORANGE, false);
                graphics.text(this.font, "Dungeon runs are logged now; Kuudra will use the same menu.",
                        listX + 6, listY + 20, 0xFF000000 | ModChat.DIM, false);
            } else if (selected != null) {
                drawDetail(graphics, selected);
            } else {
                drawList(graphics, mouseX, mouseY);
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            int trackX = listX + listW - 3;
            graphics.fill(trackX, listY, trackX + 3, listY + listH, 0xFF1A1A1A);
            int contentH = Math.max(1, contentHeight());
            int thumbH = Math.max(10, listH * listH / contentH);
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll();
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (visible.isEmpty()) {
            String message = RunSummaryConfig.getInstance().isEnabledRaw()
                    ? "No runs logged yet - finish a dungeon with Run Summary on."
                    : "Run Summary is off - turn it on in the mod menu and runs will appear here.";
            graphics.text(this.font, message, listX + 6, listY + 8, 0xFF000000 | ModChat.DIM, false);
            return;
        }
        RunRecord hovered = runAt(mouseX, mouseY);
        int first = Math.max(0, scroll / ROW_H);
        for (int i = first; i < visible.size(); i++) {
            int rowY = listY + i * ROW_H - scroll;
            if (rowY > listY + listH) {
                break;
            }
            RunRecord run = visible.get(i);
            if (run == hovered) {
                graphics.fill(listX, rowY, listX + listW - 4, rowY + ROW_H, 0xFF262626);
            } else if (i % 2 == 0) {
                graphics.fill(listX, rowY, listX + listW - 4, rowY + ROW_H, 0xFF121212);
            }
            graphics.text(this.font, this.font.plainSubstrByWidth(RunSummaryFeature.listLine(run), listW - 12),
                    listX + 4, rowY + 3, 0xFFFFFFFF, false);
        }
    }

    private void drawDetail(GuiGraphicsExtractor graphics, RunRecord run) {
        int y = listY + 4 - scroll;
        int textX = listX + 6;
        int textWidth = listW - 16;
        boolean hasMap = run.map() != null && !run.map().isEmpty();
        int mapBottom = Integer.MIN_VALUE;
        if (hasMap) {
            int mapSize = RunRecord.MapSnapshot.GRID * MAP_CELL;
            int mapX = listX + listW - mapSize - 10;
            drawMap(graphics, run.map(), mapX, y);
            mapBottom = y + mapSize + 14;
            textWidth = Math.max(90, mapX - textX - 8);
        }
        for (String line : detailLines(run)) {
            if (y > listY + listH) {
                break;
            }
            // Once the text has scrolled past the map it gets the full width back.
            int width = y >= mapBottom ? listW - 16 : textWidth;
            if (!line.isEmpty()) {
                graphics.text(this.font, Component.literal(this.font.plainSubstrByWidth(line, width)),
                        textX, y, 0xFFFFFFFF, false);
            }
            y += 10;
        }
    }

    /** The run summary's own lines, plus the party block the run log adds. */
    private List<String> detailLines(RunRecord run) {
        List<String> out = new ArrayList<>(RunSummaryFeature.summaryLines(run));
        if (!run.party().isEmpty()) {
            out.add("");
            out.add("§6§lParty");
            for (RunRecord.PartyMember member : run.party()) {
                DungeonClass cls = member.dungeonClass() == null ? null : DungeonClass.byName(member.dungeonClass());
                String classText = member.dungeonClass() == null ? "-" : member.dungeonClass()
                        + (member.classLevel() >= 0 ? " " + member.classLevel() : "");
                out.add("§7" + RunSummaryFeature.currentIgn(member) + " §f" + colorFor(cls) + classText
                        + " §8| §7rooms §f" + member.roomsLabel()
                        + " §8| §7secrets §f" + orDash(member.secrets())
                        + " §8| §7deaths §f" + orDash(member.deaths()));
            }
        }
        if (run.map() == null || run.map().isEmpty()) {
            out.add("");
            out.add("§7No map recorded (nothing was scanning the dungeon that run).");
        }
        return out;
    }

    private static String colorFor(DungeonClass cls) {
        if (cls == null) {
            return "§f";
        }
        return switch (cls) {
            case MAGE -> "§9";
            case TANK -> "§a";
            case HEALER -> "§d";
            case ARCHER -> "§c";
            case BERSERKER -> "§6";
        };
    }

    private static String orDash(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }

    /** The 11x11 grid the run finished on - rooms coloured by type, doors as their own darker cells. */
    private void drawMap(GuiGraphicsExtractor graphics, RunRecord.MapSnapshot map, int x, int y) {
        int grid = RunRecord.MapSnapshot.GRID;
        graphics.fill(x - 2, y - 2, x + grid * MAP_CELL + 2, y + grid * MAP_CELL + 2, 0xFF050505);
        graphics.outline(x - 2, y - 2, grid * MAP_CELL + 4, grid * MAP_CELL + 4, BORDER);
        for (int cell = 0; cell < grid * grid; cell++) {
            int cx = x + (cell % grid) * MAP_CELL;
            int cy = y + (cell / grid) * MAP_CELL;
            int room = map.roomAt(cell);
            if (room >= 0) {
                RunRecord.MapRoom entry = map.room(room);
                graphics.fill(cx, cy, cx + MAP_CELL - 1, cy + MAP_CELL - 1,
                        entry == null ? ROOM_UNKNOWN : colorForRoom(entry.type()));
                continue;
            }
            int door = map.doorAt(cell);
            if (door == 0) {
                continue;
            }
            int inset = 2;
            int color = switch (door) {
                case 2 -> DOOR_WITHER_COLOR;
                case 3 -> DOOR_BLOOD_COLOR;
                case 4 -> DOOR_ENTRANCE_COLOR;
                default -> DOOR_COLOR;
            };
            graphics.fill(cx + inset, cy + inset, cx + MAP_CELL - 1 - inset, cy + MAP_CELL - 1 - inset, color);
            if (map.lockedAt(cell)) {
                graphics.outline(cx + inset - 1, cy + inset - 1, MAP_CELL - 1 - inset, MAP_CELL - 1 - inset, 0xFFFFAA00);
            }
        }
        String caption = map.rooms().size() + " rooms";
        graphics.text(this.font, caption, x, y + grid * MAP_CELL + 4, 0xFF000000 | ModChat.DIM, false);
    }

    private static int colorForRoom(String type) {
        if (type == null) {
            return ROOM_NORMAL;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "entrance" -> ROOM_ENTRANCE;
            case "blood" -> ROOM_BLOOD;
            case "puzzle" -> ROOM_PUZZLE;
            case "trap" -> ROOM_TRAP;
            case "miniboss", "mini_boss" -> ROOM_MINIBOSS;
            case "fairy" -> ROOM_FAIRY;
            case "normal" -> ROOM_NORMAL;
            default -> ROOM_UNKNOWN;
        };
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
