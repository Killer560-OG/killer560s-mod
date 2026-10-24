package com.killer560.hub.livemap;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Custom map scheme editor (killer560, 2026-10-15: "make custom its own menu where you are shown a dummy map to edit
 * it. Make creating a custom color scheme its own button as well instead of toggling through the arrows to get there and
 * make sure you can save them").
 * <p>
 * Left: a sample floor in the scheme's colors, drawn with the Dungeon Map's own geometry (16-unit rooms, 4-unit doors) -
 * every room type, an unexplored room, a locked wither door and the blood door, with checkmarks. Right: the scheme's name,
 * its ten colors (each opens the color picker), Reset to Real Map, Delete (a second click confirms) and Done. Every
 * change is saved at once to {@code killer560smod-livemap.json}.
 */
public class MapSchemeEditorScreen extends Screen implements MenuThemedScreen {

    private final Screen parent;
    private int scheme;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    /** The sample map's box {x, y, side}. */
    private int[] sample = {0, 0, 0};
    private long deleteArmedAt = 0L;
    private static final long ARM_MS = 4000;

    public MapSchemeEditorScreen(Screen parent, int scheme) {
        super(Component.literal("Map Scheme"));
        this.parent = parent;
        this.scheme = scheme;
    }

    /** The scheme being edited, or null when it no longer exists. */
    private MapThemes.Scheme current() {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        return scheme >= 0 && scheme < cfg.getSchemes().size() ? cfg.getSchemes().get(scheme) : null;
    }

    /** Test hook: the scheme index this editor edits. */
    public int schemeIndex() {
        return scheme;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, 470);
        panelH = Math.min(this.height - 20, 250);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int top = panelY + 30;
        int side = Math.max(60, Math.min(panelH - 30 - 34, panelW / 2 - 20));
        sample = new int[]{panelX + 10, top, side};
        int rx = panelX + 10 + side + 14;
        int rw = panelX + panelW - 10 - rx;
        MapThemes.Scheme s = current();
        if (s == null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                    .bounds(panelX + panelW - 70, panelY + panelH - 26, 60, 18).build());
            return;
        }
        ThemedEditBox name = new ThemedEditBox(this.font, rx, top, rw, 18, Component.literal("Scheme name"));
        name.setMaxLength(MapThemes.MAX_SCHEME_NAME);
        name.setValue(s.name);
        name.setResponder(text -> {
            LiveMapConfig cfg = LiveMapConfig.getInstance();
            cfg.renameScheme(scheme, text);
            cfg.save();
        });
        addRenderableWidget(name);
        int y = top + 24;
        int colW = (rw - 6) / 2;
        for (int i = 0; i < MapThemes.COUNT; i++) {
            int slot = i;
            int x = i % 2 == 0 ? rx : rx + colW + 6;
            int argb = s.colors[i];
            addRenderableWidget(SettingsButtonWidget.builder(ColorSwatch.label(MapThemes.SLOT_NAMES[i], argb), btn ->
                    McCompat.setScreen(this.minecraft, new ColorPickerScreen(this, MapThemes.SLOT_NAMES[slot], argb,
                            MapThemes.REAL[slot], picked -> {
                                LiveMapConfig cfg = LiveMapConfig.getInstance();
                                cfg.setSchemeColor(scheme, slot, picked);
                                cfg.save();
                            }))).bounds(x, y, colW, 18).build());
            if (i % 2 == 1) {
                y += 20;
            }
        }
        y += 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset to Real Map"), btn -> {
                    LiveMapConfig cfg = LiveMapConfig.getInstance();
                    cfg.resetScheme(scheme);
                    cfg.save();
                    rebuildWidgets();
                }).bounds(rx, y, colW, 18).build());
        boolean armed = System.currentTimeMillis() - deleteArmedAt <= ARM_MS;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(armed ? "Confirm Delete" : "Delete Scheme"), btn -> {
                    if (System.currentTimeMillis() - deleteArmedAt > ARM_MS) {
                        deleteArmedAt = System.currentTimeMillis();
                        rebuildWidgets();
                        return;
                    }
                    LiveMapConfig cfg = LiveMapConfig.getInstance();
                    cfg.deleteScheme(scheme);
                    cfg.save();
                    onClose();
                }).bounds(rx + colW + 6, y, colW, 18).danger(armed).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(panelX + panelW - 70, panelY + panelH - 26, 60, 18).primary().build());
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        MapThemes.Scheme s = current();
        ScreenChrome.header(graphics, panelX, panelY, panelW, "Map Scheme" + (s == null ? "" : ": " + s.name));
        ScreenChrome.headerRight(graphics, panelX, panelY, panelW, "Custom Map Theme", MenuStyle.DIM);
        if (s == null) {
            ScreenChrome.text(graphics, "This scheme was deleted.", panelX + 14, panelY + 40, MenuStyle.DIM);
        } else {
            drawSample(graphics, s.colors);
            ScreenChrome.text(graphics, "Saved as you change it.", panelX + 14, panelY + panelH - 21, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------------------------------- the sample floor

    /** {col, row, slot} per sample room tile; a slot below 0 is an unexplored room. Two rooms span two tiles. */
    private static final int[][] TILES = {
            {0, 0, MapThemes.ENTRANCE}, {1, 0, MapThemes.NORMAL}, {2, 0, MapThemes.NORMAL}, {3, 0, MapThemes.PUZZLE},
            {0, 1, MapThemes.TRAP}, {1, 1, MapThemes.NORMAL}, {2, 1, MapThemes.MINIBOSS}, {3, 1, MapThemes.FAIRY},
            {0, 2, MapThemes.RARE}, {1, 2, MapThemes.NORMAL}, {2, 2, MapThemes.UNOPENED}, {3, 2, MapThemes.BLOOD},
            {0, 3, MapThemes.NORMAL}, {1, 3, MapThemes.NORMAL}, {2, 3, MapThemes.NORMAL}, {3, 3, MapThemes.UNOPENED},
    };
    /** Connectors that join two tiles of one room: {col, row} of the first tile and 0 east / 1 south. */
    private static final int[][] JOINS = {{1, 0, 0}, {1, 2, 1}};
    /** Doors: {col, row, 0 east / 1 south, kind}: kind 0 plain, 1 wither (locked), 2 blood (locked). */
    private static final int[][] DOORS = {
            {0, 0, 0, 0}, {2, 0, 0, 0}, {0, 0, 1, 0}, {1, 0, 1, 0}, {3, 0, 1, 0},
            {0, 1, 0, 0}, {1, 1, 0, 0}, {2, 1, 1, 1}, {3, 1, 1, 2}, {0, 1, 1, 0},
            {0, 2, 1, 0}, {1, 3, 0, 0}, {2, 3, 0, 1},
    };
    /** Checkmarks on the sample: {col, row, DungeonMapScanner state}. */
    private static final int[][] MARKS = {
            {1, 1, DungeonMapScanner.STATE_CLEARED}, {3, 0, DungeonMapScanner.STATE_FAILED},
            {0, 1, DungeonMapScanner.STATE_GREEN}, {2, 2, DungeonMapScanner.STATE_UNOPENED},
            {3, 3, DungeonMapScanner.STATE_UNOPENED}, {2, 1, DungeonMapScanner.STATE_CLEARED},
    };

    private void drawSample(GuiGraphicsExtractor graphics, int[] colors) {
        int x = sample[0];
        int y = sample[1];
        int side = sample[2];
        ScreenChrome.well(graphics, x, y, side, side);
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        float units = 4 * MapPainter.ROOM_UNITS + 3 * MapPainter.GAP_UNITS;
        float ppu = (side - 12) / units;
        float ox = x + 6;
        float oy = y + 6;
        GuiRects r = GuiRects.begin(graphics);
        r.fill(x + 3, y + 3, x + side - 3, y + side - 3, cfg.getMapBackground());
        for (int[] d : DOORS) {
            int gx = d[0] * 2 + (d[2] == 0 ? 1 : 0);
            int gz = d[1] * 2 + (d[2] == 1 ? 1 : 0);
            float[] box = MapPainter.doorUnits(gx, gz);
            int color = d[3] == 1 ? colors[MapThemes.WITHER_DOOR] : d[3] == 2 ? colors[MapThemes.BLOOD]
                    : colors[MapThemes.NORMAL];
            int x0 = Math.round(ox + box[0] * ppu);
            int y0 = Math.round(oy + box[1] * ppu);
            int x1 = Math.round(ox + (box[0] + box[2]) * ppu);
            int y1 = Math.round(oy + (box[1] + box[3]) * ppu);
            r.fill(x0, y0, x1, y1, color);
            if (d[3] == 1) {
                r.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xFFFFAA00);
            }
        }
        for (int[] t : TILES) {
            int color = colors[t[2]];
            if (t[2] == MapThemes.UNOPENED) {
                color = MapPainter.multiply(color, 1f - cfg.getDarkenUnopened());
            }
            int x0 = Math.round(ox + MapPainter.cellPos(t[0] * 2) * ppu);
            int y0 = Math.round(oy + MapPainter.cellPos(t[1] * 2) * ppu);
            int x1 = Math.round(ox + (MapPainter.cellPos(t[0] * 2) + MapPainter.ROOM_UNITS) * ppu);
            int y1 = Math.round(oy + (MapPainter.cellPos(t[1] * 2) + MapPainter.ROOM_UNITS) * ppu);
            r.fill(x0, y0, x1, y1, color);
        }
        for (int[] j : JOINS) {
            int gx = j[0] * 2 + (j[2] == 0 ? 1 : 0);
            int gz = j[1] * 2 + (j[2] == 1 ? 1 : 0);
            int x0 = Math.round(ox + MapPainter.cellPos(gx) * ppu);
            int y0 = Math.round(oy + MapPainter.cellPos(gz) * ppu);
            int x1 = Math.round(ox + (MapPainter.cellPos(gx) + MapPainter.cellSize(gx)) * ppu);
            int y1 = Math.round(oy + (MapPainter.cellPos(gz) + MapPainter.cellSize(gz)) * ppu);
            r.fill(x0, y0, x1, y1, colors[MapThemes.NORMAL]);
        }
        r.submit();
        for (int[] m : MARKS) {
            float cx = ox + (MapPainter.cellPos(m[0] * 2) + MapPainter.ROOM_UNITS / 2f) * ppu;
            float cy = oy + (MapPainter.cellPos(m[1] * 2) + MapPainter.ROOM_UNITS / 2f) * ppu;
            MapPainter.drawSampleMark(graphics, m[2], cfg, cx, cy, ppu);
        }
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }
}
