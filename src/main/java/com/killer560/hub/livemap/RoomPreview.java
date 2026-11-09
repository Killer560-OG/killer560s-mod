package com.killer560.hub.livemap;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Room Preview (featG-1109; Skyblocker RoomPreview): with chat open (the map HUD hides behind other screens), hovering a room on the Dungeon Map HUD
 * draws a small panel by the cursor: the room's shape as colored tiles, its name and type and what is in it (secrets by kind,
 * crypts, revive stones, trapped chests). A room the map still shows as unopened stays unnamed, as on the map itself. Nothing is
 * done with no screen open or the switch off; the text is built once a tick for the hovered room, never per frame.
 */
final class RoomPreview {

    private static final int PAD = 4;
    /** Map units per preview pixel: a 16-unit room is 8 px, a 4-unit gap 2 px. */
    private static final int UNITS_PER_PX = 2;

    private static LiveMapFeature.RoomGroup cachedGroup;
    private static int cachedTick = -1;
    private static List<String> cachedLines = List.of();
    private static int cachedColor;
    /** Testkit: the map's origin (screen pixels) and pixels per map unit as of the last frame drawn with a screen open and the switch on, and the panels drawn. */
    private static final float[] LAST_MAP = new float[3];
    private static int panelsDrawn;

    private RoomPreview() {
    }

    /** Called from the Dungeon Map HUD's draw with the map's origin and scale (HUD-local pixels). */
    static void draw(GuiGraphicsExtractor g, HudElement hud, float ox, float oy, float ppu) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = McCompat.screen(mc);
        if (screen == null || screen instanceof HudEditorScreen || screen instanceof InteractiveMapScreen
                || !RoomPreviewConfig.getInstance().isEnabled()) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(hud);
        float scale = HudElementRegistry.resolveScale(hud);
        LAST_MAP[0] = pos[0] + ox * scale;
        LAST_MAP[1] = pos[1] + oy * scale;
        LAST_MAP[2] = ppu * scale;
        float mx = (float) mc.mouseHandler.getScaledXPos(mc.getWindow());
        float my = (float) mc.mouseHandler.getScaledYPos(mc.getWindow());
        int cell = LiveMapFeature.mapCellAt(((mx - pos[0]) / scale - ox) / ppu, ((my - pos[1]) / scale - oy) / ppu);
        int gid = cell < 0 ? -1 : LiveMapFeature.groupIdAt(cell);
        if (gid < 0) {
            return;
        }
        LiveMapFeature.RoomGroup group = LiveMapFeature.groupsView().get(gid);
        if (group.entry == null || !MapPainter.identityRevealed(group)) {
            return;
        }
        int tick = LiveMapFeature.tickCount();
        if (group != cachedGroup || tick != cachedTick) {
            cachedGroup = group;
            cachedTick = tick;
            String type = MapPainter.roomType(group);
            cachedColor = 0xFF000000 | MapPainter.typeColor(type, LiveMapConfig.getInstance());
            cachedLines = lines(group.entry, type);
        }
        panelsDrawn++;
        var font = mc.font;
        int[] dim = diagramSize(group);
        int textW = 0;
        for (String l : cachedLines) {
            textW = Math.max(textW, font.width(l));
        }
        int h = Math.max(dim[1], cachedLines.size() * font.lineHeight) + PAD * 2;
        int w = dim[0] + PAD * 3 + textW;
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        // In screen pixels from the cursor: right and below it, flipped to the other side at a screen edge.
        float px = mx + 12 + w > sw ? mx - 6 - w : mx + 12;
        float py = Math.max(2, Math.min(my + 12, sh - h - 2));
        g.pose().pushMatrix();
        g.pose().translate((px - pos[0]) / scale, (py - pos[1]) / scale);
        g.pose().scale(1f / scale, 1f / scale);
        GuiRects r = GuiRects.begin(g);
        r.fill(0, 0, w, h, 0xE0101010);
        r.outline(0, 0, w, h, ThemeAccent.accent(0xFFCC6600));
        int dx = PAD;
        int dy = PAD + (Math.max(dim[1], cachedLines.size() * font.lineHeight) - dim[1]) / 2;
        for (int c : group.cells) {
            int gx = c % LiveMapFeature.GRID;
            int gz = c / LiveMapFeature.GRID;
            int x0 = dx + (MapPainter.cellPos(gx) - MapPainter.cellPos(group.minGX)) / UNITS_PER_PX;
            int y0 = dy + (MapPainter.cellPos(gz) - MapPainter.cellPos(group.minGZ)) / UNITS_PER_PX;
            r.fill(x0, y0, x0 + MapPainter.cellSize(gx) / UNITS_PER_PX, y0 + MapPainter.cellSize(gz) / UNITS_PER_PX, cachedColor);
        }
        r.submit();
        int tx = PAD * 2 + dim[0];
        for (int i = 0; i < cachedLines.size(); i++) {
            g.text(font, cachedLines.get(i), tx, PAD + i * font.lineHeight, i == 0 ? cachedColor : 0xFFE0E0E0, false);
        }
        g.pose().popMatrix();
    }

    private static int[] diagramSize(LiveMapFeature.RoomGroup group) {
        return new int[]{
                (MapPainter.cellPos(group.maxGX) + MapPainter.cellSize(group.maxGX) - MapPainter.cellPos(group.minGX)) / UNITS_PER_PX,
                (MapPainter.cellPos(group.maxGZ) + MapPainter.cellSize(group.maxGZ) - MapPainter.cellPos(group.minGZ)) / UNITS_PER_PX};
    }

    /** The panel's text: name, type and shape, then what is in the room (only the non-zero parts). */
    static List<String> lines(RoomEntry e, String type) {
        List<String> out = new ArrayList<>();
        out.add(e.name);
        out.add(MapPainter.typeName(type) + (e.shape == null ? "" : ", " + e.shape));
        if (e.secrets > 0) {
            out.add("Secrets: " + e.secrets);
            RoomEntry.SecretDetails d = e.secretDetails;
            if (d != null) {
                StringBuilder kinds = new StringBuilder();
                kind(kinds, "Chest", d.chest);
                kind(kinds, "Item", d.item);
                kind(kinds, "Bat", d.bat);
                kind(kinds, "Wither", d.wither);
                kind(kinds, "Key", d.redstoneKey);
                if (kinds.length() > 0) {
                    out.add(kinds.toString());
                }
            }
        }
        if (e.crypts > 0) {
            out.add("Crypts: " + e.crypts);
        }
        if (e.reviveStones > 0) {
            out.add("Revive Stones: " + e.reviveStones);
        }
        if (e.trappedChests > 0) {
            out.add("Trapped Chests: " + e.trappedChests);
        }
        return out;
    }

    private static void kind(StringBuilder sb, String label, int n) {
        if (n > 0) {
            sb.append(sb.length() > 0 ? ", " : "").append(label).append(' ').append(n);
        }
    }

    // ---- testkit hooks ----

    static float[] lastMapForTest() { return LAST_MAP.clone(); }

    static int panelsDrawnForTest() { return panelsDrawn; }

    static List<String> cachedLinesForTest() { return cachedLines; }

    /** The panel's text for a room with these contents. */
    static List<String> linesForTest(String name, String shape, String type, int chest, int item, int bat, int crypts) {
        RoomEntry e = new RoomEntry();
        e.name = name;
        e.shape = shape;
        e.type = type;
        e.secretDetails = new RoomEntry.SecretDetails();
        e.secretDetails.chest = chest;
        e.secretDetails.item = item;
        e.secretDetails.bat = bat;
        e.secrets = chest + item + bat;
        e.crypts = crypts;
        return lines(e, type);
    }
}
