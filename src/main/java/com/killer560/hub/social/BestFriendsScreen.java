package com.killer560.hub.social;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.killer560.hub.compat.McCompat;

/**
 * {@code /bestfriends} - Party Time Tracker menu (killer560's 8.6). Same black + amber chrome as every other
 * custom menu in this mod ({@code CroesusTrackerScreen}, {@code RunLogScreen}, {@code StorageSearchScreen}).
 * Reads {@link BestFriendsStore} (accumulated data) and {@link BestFriendsConfig} (this menu's own sort/
 * filter prefs, persisted the same way {@code StorageSearchConfig} persists its sort/source toggles).
 */
public class BestFriendsScreen extends Screen implements MenuThemedScreen {
    private static final int ROW_H = 20;
    private static final int HEAD_SIZE = 16;
    /** Room either side of a button's label. */
    private static final int LABEL_PAD = 12;
    /** The search box is never squeezed narrower than this; below it the Sort and filter buttons take a row of
     *  their own under the search box. */
    private static final int MIN_SEARCH_W = 60;

    private final Screen parent;
    private EditBox searchBox;
    private List<BestFriendsStore.Record> visible = List.of();
    private BestFriendsStore.Record selected;

    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int scroll = 0;

    public BestFriendsScreen(Screen parent) {
        super(Component.literal("Best Friends"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(360, Math.min((int) (this.width * 0.75), 560)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.82), 440)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        BestFriendsConfig cfg = BestFriendsConfig.getInstance();

        // killer560 (2026-10-08): "the sort goes outside of its box". It was a fixed 100 wide and "Sort: Time
        // Together" is wider than that. Both buttons are now sized from their widest label, and when the row
        // cannot hold them beside a usable search box they drop to a row of their own.
        String current = searchBox != null ? searchBox.getValue() : "";
        int rowW = panelW - 12;
        int sortW = widestSortLabel() + LABEL_PAD;
        int filterW = Math.max(this.font.width(filterText(true)), this.font.width(filterText(false))) + LABEL_PAD;
        boolean oneRow = MIN_SEARCH_W + 4 + sortW + 4 + filterW <= rowW;
        int boxW;
        int buttonsX;
        int buttonsY;
        if (oneRow) {
            boxW = rowW - sortW - filterW - 8;
            buttonsX = panelX + 6 + boxW + 4;
            buttonsY = panelY + 34;
        } else {
            boxW = rowW;
            buttonsY = panelY + 34 + 22;
            if (sortW + 4 + filterW > rowW) {
                // Narrower than both labels: share the row; the labels are clipped by sortText/filterText below.
                sortW = (rowW - 4) / 2;
                filterW = rowW - 4 - sortW;
            }
            buttonsX = panelX + 6;
        }
        listX = panelX + 6;
        listY = buttonsY + 18 + 8;
        listW = panelW - 12;
        listH = Math.max(20, panelY + panelH - 8 - listY);
        int sortBoxW = sortW;
        int filterBoxW = filterW;

        searchBox = new ThemedEditBox(this.font, panelX + 6, panelY + 34, boxW, 18, Component.literal("Search"))
                .hint("Search name...");
        searchBox.setMaxLength(32);
        searchBox.setValue(current);
        searchBox.setResponder(text -> refilter());
        addRenderableWidget(searchBox);

        addRenderableWidget(SettingsButtonWidget.cycling(fit(sortText(cfg), sortBoxW), (btn, dir) -> {
            cfg.setSortMode(dir > 0 ? cfg.getSortMode().next() : cfg.getSortMode().previous());
            cfg.save();
            btn.setMessage(fit(sortText(cfg), sortBoxW));
            refilter();
        }).bounds(buttonsX, buttonsY, sortBoxW, 18).build());

        addRenderableWidget(SettingsButtonWidget.builder(fit(filterText(cfg.isDungeonOnlyFilter()), filterBoxW), btn -> {
            cfg.setDungeonOnlyFilter(!cfg.isDungeonOnlyFilter());
            cfg.save();
            btn.setMessage(fit(filterText(cfg.isDungeonOnlyFilter()), filterBoxW));
            refilter();
        }).bounds(buttonsX + sortBoxW + 4, buttonsY, filterBoxW, 18).build());

        if (selected != null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
                selected = null;
                scroll = 0;
                rebuildWidgets();
            }).bounds(panelX + panelW - 70, panelY + 6, 64, 18).build());
        }

        refilter();
    }

    private static String sortText(BestFriendsConfig cfg) {
        return sortText(cfg.getSortMode());
    }

    private static String sortText(BestFriendsConfig.SortMode mode) {
        return "Sort: §b" + mode.label;
    }

    private static String filterText(boolean dungeonOnly) {
        return dungeonOnly ? "§6Dungeon Only" : "§fAny Party Time";
    }

    private int widestSortLabel() {
        int w = 0;
        for (BestFriendsConfig.SortMode mode : BestFriendsConfig.SortMode.values()) {
            w = Math.max(w, this.font.width(sortText(mode)));
        }
        return w;
    }

    /** The label, cut to what fits inside a button {@code boxW} wide. */
    private Component fit(String label, int boxW) {
        int room = Math.max(0, boxW - LABEL_PAD);
        if (this.font.width(label) <= room) {
            return Component.literal(label);
        }
        return Component.literal(this.font.plainSubstrByWidth(label, Math.max(0, room - this.font.width(".."))) + "..");
    }

    private void refilter() {
        BestFriendsConfig cfg = BestFriendsConfig.getInstance();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.US);
        List<BestFriendsStore.Record> out = new ArrayList<>();
        for (BestFriendsStore.Record record : BestFriendsStore.records()) {
            if (cfg.isDungeonOnlyFilter() && record.totalDungeonRuns() == 0) {
                continue;
            }
            String name = record.lastKnownName == null ? "" : record.lastKnownName;
            if (!query.isEmpty() && !name.toLowerCase(Locale.US).contains(query)) {
                continue;
            }
            out.add(record);
        }
        Comparator<BestFriendsStore.Record> comparator = switch (cfg.getSortMode()) {
            case TIME -> Comparator.comparingLong(BestFriendsTracker::liveTotalMs).reversed();
            case RUNS -> Comparator.comparingInt((BestFriendsStore.Record r) -> r.totalDungeonRuns()).reversed();
            case NAME -> Comparator.comparing(r -> r.lastKnownName == null ? "" : r.lastKnownName.toLowerCase(Locale.US));
        };
        out.sort(comparator);
        visible = out;
    }

    // ---- interaction -----------------------------------------------------------------------------

    /** Typing with no box focused goes into the search box (as on the Friends List), so typing a name narrows the list. */
    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (searchBox != null && !(getFocused() instanceof EditBox)) {
            setFocused(searchBox);
            searchBox.setFocused(true);
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (selected == null && event.button() == 0) {
            BestFriendsStore.Record hit = rowAt(event.x(), event.y());
            if (hit != null) {
                selected = hit;
                scroll = 0;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private BestFriendsStore.Record rowAt(double mx, double my) {
        if (mx < listX || mx > listX + listW || my < listY || my >= listY + listH) {
            return null;
        }
        int i = (int) ((my - listY + scroll) / ROW_H);
        return i >= 0 && i < visible.size() ? visible.get(i) : null;
    }

    private int contentHeight(List<String> lines) {
        return lines != null ? lines.size() * 10 : visible.size() * ROW_H;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight(selected != null ? detailLines(selected) : null) - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- rendering -------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, "Best Friends");
        if (selected != null) {
            // The "< Back" button sits where the count would be.
        } else if (!BestFriendsConfig.getInstance().getEnabledRaw()) {
            String warn = this.font.plainSubstrByWidth("Track Party Time is OFF - turn it on in the Best Friends tab",
                    Math.max(0, panelW - 30 - this.font.width("Best Friends")));
            ScreenChrome.headerRight(graphics, panelX, panelY, panelW, warn, MenuStyle.RED);
        } else {
            ScreenChrome.headerRight(graphics, panelX, panelY, panelW, visible.size() + " tracked", MenuStyle.DIM);
        }

        ScreenChrome.well(graphics, listX - 1, listY - 1, listW + 2, listH + 2);
        // The detail text is built once a frame (it reads the live clock) and shared by the draw, the scroll range
        // and the scrollbar, which each rebuilt it before.
        List<String> lines = selected != null ? detailLines(selected) : null;
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            if (selected != null) {
                drawDetail(graphics, selected, lines);
            } else {
                drawList(graphics, mouseX, mouseY);
            }
        } finally {
            graphics.disableScissor();
        }
        int maxScroll = Math.max(0, contentHeight(lines) - listH);
        if (maxScroll > 0) {
            int trackX = listX + listW - 4;
            int contentH = Math.max(1, contentHeight(lines));
            int thumbH = Math.max(10, listH * listH / contentH);
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll;
            ScreenChrome.scrollbar(graphics, trackX, listY, 4, listH, thumbY, thumbH, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (visible.isEmpty()) {
            String message = BestFriendsStore.records().isEmpty()
                    ? "Nobody tracked yet - party up with Track Party Time on and it'll fill in."
                    : "No players match this search/filter.";
            ScreenChrome.text(graphics, message, listX + 6, listY + 8, MenuStyle.DIM);
            return;
        }
        BestFriendsStore.Record hovered = rowAt(mouseX, mouseY);
        int first = Math.max(0, scroll / ROW_H);
        for (int i = first; i < visible.size(); i++) {
            int rowY = listY + i * ROW_H - scroll;
            if (rowY > listY + listH) {
                break;
            }
            BestFriendsStore.Record record = visible.get(i);
            GuiRects rr = GuiRects.begin(graphics);
            ScreenChrome.row(rr, listX, rowY, listW - 5, ROW_H, record == hovered, false, i % 2 == 0);
            rr.submit();
            int textY = rowY + (ROW_H - 8) / 2;
            PlayerHeadRenderer.draw(graphics, record.uuid, record.lastKnownName, listX + 3, rowY + (ROW_H - HEAD_SIZE) / 2, HEAD_SIZE);
            String name = record.lastKnownName == null || record.lastKnownName.isBlank() ? "?" : record.lastKnownName;
            if (PlayerLookup.isOnlineNow(record.uuid)) {
                name = "§a" + name;
            }
            int nameX = listX + 3 + HEAD_SIZE + 6;
            String right = formatCompact(BestFriendsTracker.liveTotalMs(record)) + "  §8|§r  " + record.totalDungeonRuns() + " runs";
            int rightW = this.font.width(this.font.plainSubstrByWidth(right, listW - 12));
            ScreenChrome.text(graphics, right, listX + listW - 6 - rightW, textY, MenuStyle.OR_LIGHT);
            int nameSpace = listW - (nameX - listX) - rightW - 10;
            ScreenChrome.text(graphics, this.font.plainSubstrByWidth(name, Math.max(10, nameSpace)), nameX, textY, MenuStyle.TXT);
        }
    }

    private void drawDetail(GuiGraphicsExtractor graphics, BestFriendsStore.Record record, List<String> lines) {
        PlayerHeadRenderer.draw(graphics, record.uuid, record.lastKnownName, listX + 6, listY + 6 - scroll, 32);
        int textX = listX + 6 + 32 + 10;
        String name = record.lastKnownName == null || record.lastKnownName.isBlank() ? "?" : record.lastKnownName;
        ScreenChrome.text(graphics, "§l" + name, textX, listY + 6 - scroll, MenuStyle.TXT);
        ScreenChrome.text(graphics, PlayerLookup.isOnlineNow(record.uuid) ? "§aOn your tab list right now" : "§8Not on your tab list right now",
                textX, listY + 18 - scroll, MenuStyle.TXT);

        int y = listY + 6 - scroll + 38;
        for (String line : lines) {
            if (y > listY + listH) {
                break;
            }
            if (!line.isEmpty()) {
                ScreenChrome.text(graphics, this.font.plainSubstrByWidth(line, listW - 16), listX + 6, y, MenuStyle.TXT);
            }
            y += 10;
        }
    }

    private List<String> detailLines(BestFriendsStore.Record record) {
        List<String> out = new ArrayList<>();
        out.add("");
        // Down to the second, read live every frame so it ticks while you are partied (killer560, 2026-10-08).
        out.add("§6§lTime together§r  §f" + formatDetailed(BestFriendsTracker.liveTotalMs(record))
                + (BestFriendsTracker.isAccruing(record.uuid) ? "  §a(partied now)" : ""));
        out.add("§7First partied  §f" + formatDate(record.firstPartiedAtMs));
        out.add("§7Last partied  §f" + formatDate(record.lastPartiedAtMs));
        out.add("");
        out.add("§6§lDungeon runs together§r  §f" + record.totalDungeonRuns());
        if (record.dungeonRunsByFloor.isEmpty()) {
            out.add("§7  None yet.");
        } else {
            for (Map.Entry<String, Integer> e : record.dungeonRunsByFloor.entrySet()) {
                out.add("§7  " + e.getKey() + " §fx" + e.getValue());
            }
        }
        out.add("");
        out.add("§6§lKuudra runs together§r  §f" + record.kuudraRuns);
        out.add("§7  Kuudra run detection is a work in progress - see the mod's notes.");
        return out;
    }

    /** The list's form: "10d 3h 10m", "1h 32m", "45m", or "38s" under a minute. Hours roll into days. */
    public static String formatCompact(long totalMs) {
        long total = Math.max(0L, totalMs) / 1000L;
        long d = total / 86_400L;
        long h = (total % 86_400L) / 3600L;
        long m = (total % 3600L) / 60L;
        if (d > 0) {
            return d + "d " + h + "h " + m + "m";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m";
        }
        return total + "s";
    }

    /** A clicked player's form, to the second: "10d 3h 10m 5s", "1h 32m 5s", "45m 0s", "38s". */
    public static String formatDetailed(long totalMs) {
        long total = Math.max(0L, totalMs) / 1000L;
        long d = total / 86_400L;
        long h = (total % 86_400L) / 3600L;
        long m = (total % 3600L) / 60L;
        long sec = total % 60L;
        if (d > 0) {
            return d + "d " + h + "h " + m + "m " + sec + "s";
        }
        if (h > 0) {
            return h + "h " + m + "m " + sec + "s";
        }
        if (m > 0) {
            return m + "m " + sec + "s";
        }
        return sec + "s";
    }

    private static String formatDate(long epochMs) {
        if (epochMs <= 0) {
            return "-";
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMs));
    }

    /** This screen's own Theme setting (Theme Default follows the menu). */
    public static final String THEME_ID = "bestfriends";

    @Override
    public String themeId() {
        return THEME_ID;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
