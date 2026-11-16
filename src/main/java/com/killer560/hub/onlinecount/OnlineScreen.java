package com.killer560.hub.onlinecount;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The /online screen: the same counts the Discord /online shows (players grouped by island, no names), read from the
 *  relay off the render thread, with a Refresh button. */
public class OnlineScreen extends Screen {
    private static final int W = 300;
    private static final int ROW = 12;

    private final Screen parent;
    /** Island -> players, relay order (most first); null while loading or after a failure. */
    private volatile List<Map.Entry<String, Integer>> rows;
    private volatile String status = "Loading...";
    /** Test hook: a fixed relay reply used instead of the network. */
    private static volatile String fixtureForTest;
    private long lastFetch;

    public OnlineScreen(Screen parent) {
        super(Component.literal("Online Now"));
        this.parent = parent;
    }

    public static void setFixtureForTest(String json) { fixtureForTest = json; }

    /** The rows the screen shows for a relay reply ({"total":n,"byLocation":[["Hub",2],...]}), or null if unreadable. */
    public static List<Map.Entry<String, Integer>> parse(String json) {
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("byLocation");
            List<Map.Entry<String, Integer>> out = new ArrayList<>();
            for (var e : arr) {
                JsonArray p = e.getAsJsonArray();
                out.add(Map.entry(p.get(0).getAsString(), p.get(1).getAsInt()));
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Rows the screen would draw, for the testkit. */
    public List<Map.Entry<String, Integer>> rowsForTest() { return rows; }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = this.height / 2 + 100;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh"), b -> refresh())
                .bounds(x, y, 98, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(x + 102, y, 98, 20).build());
        if (rows == null) refresh();
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastFetch < 2000) return;
        lastFetch = now;
        status = "Loading...";
        String fx = fixtureForTest;
        if (fx != null) {
            apply(fx);
            return;
        }
        OnlinePresence.fetchCounts(this::apply);
    }

    private void apply(String json) {
        List<Map.Entry<String, Integer>> r = json == null ? null : parse(json);
        if (r == null) {
            status = "Could not reach the relay.";
            return;
        }
        rows = r;
        status = "";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        List<Map.Entry<String, Integer>> r = rows;
        int panelH = 230;
        int px = this.width / 2 - W / 2;
        int py = this.height / 2 - 120;
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, px, py, W, panelH);
        ScreenChrome.headerBar(g, px, py, W);
        ScreenChrome.titleCentred(g, "killer560's Mod - Online Now", this.width / 2, py + 10, false);
        int y = py + 32;
        if (r == null) {
            ScreenChrome.text(g, status, px + 12, y, MenuStyle.DIM);
        } else if (r.isEmpty()) {
            ScreenChrome.text(g, "Nobody is using killer560's Mod right now.", px + 12, y, MenuStyle.DIM);
        } else {
            int total = r.stream().mapToInt(Map.Entry::getValue).sum();
            ScreenChrome.text(g, total + (total == 1 ? " player" : " players") + " online right now", px + 12, y, MenuStyle.OR);
            y += 16;
            int max = (py + panelH - 12 - y) / ROW;
            for (int i = 0; i < r.size() && i < max; i++) {
                var e = r.get(i);
                ScreenChrome.text(g, e.getKey(), px + 12, y, MenuStyle.TXT);
                String n = String.valueOf(e.getValue());
                ScreenChrome.text(g, n, px + W - 12 - MenuStyle.font().width(n), y, MenuStyle.TXT_SOFT);
                y += ROW;
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() { McCompat.setScreen(this.minecraft, parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
