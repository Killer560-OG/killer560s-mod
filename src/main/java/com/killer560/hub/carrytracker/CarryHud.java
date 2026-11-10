package com.killer560.hub.carrytracker;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudText;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * The Carry Tracker HUD: one row per client, "name  done/total runs", in the menu theme's accent and green once done. It shows
 * in dungeons and the Dungeon Hub (Party Finder is there), hides with no clients and shows sample rows in the HUD editor. The
 * rows are rebuilt only when the list, the switch or a count changed.
 */
public final class CarryHud extends BasicHud {

    public static final String ID = "carry_tracker";
    private static final int DONE_GREEN = 0xFF55FF55;
    private static final List<Row> SAMPLE = List.of(new Row("Steve  3/5 runs", false), new Row("Alex  5/5 runs", true));

    private record Row(String text, boolean done) {
    }

    private List<Row> rows = List.of();
    private long key = -1;
    private static volatile List<String> lastLines = List.of();

    public CarryHud() {
        super(ID, "Carry Tracker", 10, 200);
    }

    /** The rows to draw now (empty for nothing). */
    private List<Row> rows() {
        Minecraft client = Minecraft.getInstance();
        CarryTrackerConfig cfg = CarryTrackerConfig.getInstance();
        if (!cfg.getEnabledRaw()) {
            return List.of();
        }
        if (McCompat.screen(client) instanceof HudEditorScreen) {
            return SAMPLE;
        }
        if (!cfg.isEnabled() || client.player == null || HudVisibility.hidesHud()
                || !(DungeonState.isInDungeon() || "Dungeon Hub".equalsIgnoreCase(IslandDetector.islandName()))) {
            return List.of();
        }
        long k = ((long) cfg.rev() << 32) | (CarryTracker.rev() & 0xFFFFFFFFL);
        if (k != key) {
            key = k;
            CarryTracker.warm(null);
            List<Row> out = new ArrayList<>();
            for (CarryTrackerConfig.Client c : cfg.getTracked()) {
                long done = CarryTracker.count(c.name());
                out.add(new Row(c.name() + "  " + done + "/" + c.runs() + " runs", done >= c.runs()));
            }
            rows = out;
        }
        return rows;
    }

    @Override
    public int width() {
        List<Row> r = rows();
        return HudText.width((r.isEmpty() ? SAMPLE : r).stream().map(Row::text).toList());
    }

    @Override
    public int height() {
        return HudText.ROW * Math.max(1, rows().size());
    }

    @Override
    public boolean isEnabledInSettings() {
        return CarryTrackerConfig.getInstance().getEnabledRaw();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        List<Row> r = rows();
        if (r.isEmpty()) {
            return;
        }
        HudSeen.markDrawn(id());
        int accent = ThemeAccent.onDark(ThemeAccent.accent(0xFF000000 | ModChat.ORANGE));
        var font = Minecraft.getInstance().font;
        List<String> drawn = new ArrayList<>(r.size());
        for (Row row : r) {
            graphics.text(font, row.text(), x, y, row.done() ? DONE_GREEN : accent, true);
            y += HudText.ROW;
            drawn.add(row.text());
        }
        lastLines = drawn;
    }

    /** For the testkit: the rows drawn last. */
    public static List<String> lastLinesForTest() {
        return lastLines;
    }
}
