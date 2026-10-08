package com.killer560.hub.social;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ModChat;
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
public class BestFriendsScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
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

        searchBox = new EditBox(this.font, panelX + 6, panelY + 34, boxW, 18, Component.literal("Search"));
        searchBox.setMaxLength(32);
        searchBox.setHint(Component.literal("Search name..."));
        searchBox.setValue(current);
        searchBox.setResponder(text -> refilter());
        addRenderableWidget(searchBox);

        addRenderableWidget(SettingsButtonWidget.builder(fit(sortText(cfg), sortBoxW), btn -> {
            cfg.setSortMode(cfg.getSortMode().next());
            cfg.save();
            btn.setMessage(fit(sortText(cfg), sortBoxW));
            refilter();
        }).secondaryPress(btn -> {
            cfg.setSortMode(cfg.getSortMode().previous());
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

    private int maxScroll() {
        if (selected != null) {
            return Math.max(0, detailLines(selected).size() * 10 - listH);
        }
        return Math.max(0, visible.size() * ROW_H - listH);
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
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.outline(panelX, panelY, panelW, panelH, BORDER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 28, 0xFF000000);
        graphics.outline(panelX, panelY, panelW, 28, BORDER);
        graphics.fill(panelX, panelY + 27, panelX + panelW, panelY + 28, ACCENT);
        graphics.text(this.font, "Best Friends", panelX + 10, panelY + 10, ACCENT, false);
        if (selected != null) {
            // The "< Back" button sits where the count would be.
        } else if (!BestFriendsConfig.getInstance().getEnabledRaw()) {
            String warn = this.font.plainSubstrByWidth("Party Time Tracker is OFF - turn it on in the Best Friends tab",
                    Math.max(0, panelW - 30 - this.font.width("Best Friends")));
            graphics.text(this.font, warn, panelX + panelW - 10 - this.font.width(warn), panelY + 10,
                    0xFF000000 | ModChat.BAD, false);
        } else {
            String count = visible.size() + " tracked";
            graphics.text(this.font, count, panelX + panelW - 10 - this.font.width(count), panelY + 10,
                    0xFF000000 | ModChat.DIM, false);
        }

        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFF080808);
        graphics.outline(listX - 1, listY - 1, listW + 2, listH + 2, BORDER);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            if (selected != null) {
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
            int contentH = selected == null ? Math.max(1, visible.size() * ROW_H) : Math.max(1, detailLines(selected).size() * 10);
            int thumbH = Math.max(10, listH * listH / contentH);
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll();
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (visible.isEmpty()) {
            String message = BestFriendsStore.records().isEmpty()
                    ? "Nobody tracked yet - party up with Party Time Tracker on and it'll fill in."
                    : "No players match this search/filter.";
            graphics.text(this.font, message, listX + 6, listY + 8, 0xFF000000 | ModChat.DIM, false);
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
            if (record == hovered) {
                graphics.fill(listX, rowY, listX + listW - 4, rowY + ROW_H, 0xFF262626);
            } else if (i % 2 == 0) {
                graphics.fill(listX, rowY, listX + listW - 4, rowY + ROW_H, 0xFF121212);
            }
            int textY = rowY + (ROW_H - 8) / 2;
            PlayerHeadRenderer.draw(graphics, record.uuid, record.lastKnownName, listX + 3, rowY + (ROW_H - HEAD_SIZE) / 2, HEAD_SIZE);
            String name = record.lastKnownName == null || record.lastKnownName.isBlank() ? "?" : record.lastKnownName;
            if (PlayerLookup.isOnlineNow(record.uuid)) {
                name = "§a" + name;
            }
            int nameX = listX + 3 + HEAD_SIZE + 6;
            String right = formatCompact(BestFriendsTracker.liveTotalMs(record)) + "  §8|§r  " + record.totalDungeonRuns() + " runs";
            int rightW = this.font.width(this.font.plainSubstrByWidth(right, listW - 12));
            graphics.text(this.font, right, listX + listW - 6 - rightW, textY, 0xFF000000 | ModChat.LIGHT_ORANGE, false);
            int nameSpace = listW - (nameX - listX) - rightW - 10;
            graphics.text(this.font, this.font.plainSubstrByWidth(name, Math.max(10, nameSpace)), nameX, textY, 0xFFFFFFFF, false);
        }
    }

    private void drawDetail(GuiGraphicsExtractor graphics, BestFriendsStore.Record record) {
        PlayerHeadRenderer.draw(graphics, record.uuid, record.lastKnownName, listX + 6, listY + 6 - scroll, 32);
        int textX = listX + 6 + 32 + 10;
        String name = record.lastKnownName == null || record.lastKnownName.isBlank() ? "?" : record.lastKnownName;
        graphics.text(this.font, "§l" + name, textX, listY + 6 - scroll, 0xFFFFFFFF, false);
        graphics.text(this.font, PlayerLookup.isOnlineNow(record.uuid) ? "§aOn your tab list right now" : "§8Not on your tab list right now",
                textX, listY + 18 - scroll, 0xFFFFFFFF, false);

        int y = listY + 6 - scroll + 38;
        for (String line : detailLines(record)) {
            if (y > listY + listH) {
                break;
            }
            if (!line.isEmpty()) {
                graphics.text(this.font, this.font.plainSubstrByWidth(line, listW - 16), listX + 6, y, 0xFFFFFFFF, false);
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

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
