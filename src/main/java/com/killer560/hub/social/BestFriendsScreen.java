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
        listX = panelX + 6;
        listY = panelY + 60;
        listW = panelW - 12;
        listH = panelH - 60 - 8;

        BestFriendsConfig cfg = BestFriendsConfig.getInstance();

        String current = searchBox != null ? searchBox.getValue() : "";
        int sortW = 100;
        int filterW = 96;
        int boxW = Math.max(80, panelW - 12 - sortW - filterW - 8);
        searchBox = new EditBox(this.font, panelX + 6, panelY + 34, boxW, 18, Component.literal("Search"));
        searchBox.setMaxLength(32);
        searchBox.setHint(Component.literal("Search name..."));
        searchBox.setValue(current);
        searchBox.setResponder(text -> refilter());
        addRenderableWidget(searchBox);

        addRenderableWidget(SettingsButtonWidget.builder(sortText(cfg), btn -> {
            cfg.setSortMode(cfg.getSortMode().next());
            cfg.save();
            btn.setMessage(sortText(cfg));
            refilter();
        }).bounds(panelX + 6 + boxW + 4, panelY + 34, sortW, 18).build());

        addRenderableWidget(SettingsButtonWidget.builder(filterText(cfg), btn -> {
            cfg.setDungeonOnlyFilter(!cfg.isDungeonOnlyFilter());
            cfg.save();
            btn.setMessage(filterText(cfg));
            refilter();
        }).bounds(panelX + 6 + boxW + 8 + sortW, panelY + 34, filterW, 18).build());

        if (selected != null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
                selected = null;
                scroll = 0;
                rebuildWidgets();
            }).bounds(panelX + panelW - 70, panelY + 6, 64, 18).build());
        }

        refilter();
    }

    private static Component sortText(BestFriendsConfig cfg) {
        return Component.literal("Sort: §b" + cfg.getSortMode().label);
    }

    private static Component filterText(BestFriendsConfig cfg) {
        return Component.literal(cfg.isDungeonOnlyFilter() ? "§6Dungeon Only" : "§fAny Party Time");
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
            case TIME -> Comparator.comparingLong((BestFriendsStore.Record r) -> r.totalPartySeconds).reversed();
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
        if (!BestFriendsConfig.getInstance().getEnabledRaw()) {
            String warn = "Party Time Tracker is OFF - turn it on above to start tracking";
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
            String right = formatDuration(record.totalPartySeconds) + "  §8|§r  " + record.totalDungeonRuns() + " runs";
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
        out.add("§6§lTime together§r  §f" + formatDuration(record.totalPartySeconds));
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

    /** "3h 12m", "45m", or "38s" for anything under a minute. */
    private static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m";
        }
        return s + "s";
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
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
