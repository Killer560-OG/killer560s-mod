package com.killer560.hub.storagesearch;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/** The search screen for {@link StorageSearchFeature}, drawn in the mod menu's active theme ({@link ScreenChrome}: bevelled
 *  frame, header bar, recessed search field and results well, themed rows). Every control keeps its place. */
public class StorageSearchScreen extends Screen implements MenuThemedScreen {

    private static final int ROW_H = 22;

    private final Screen parent;
    private final String initialQuery;

    private StorageSearchIndex index;
    private List<StorageSearchIndex.Entry> results = new ArrayList<>();
    private ThemedEditBox searchBox;
    private SettingsButtonWidget loreButton;
    private SettingsButtonWidget invButton;
    private SettingsButtonWidget sortButton;
    private SettingsButtonWidget sourceButton;

    private int panelX, panelY, panelW, panelH;
    /** The search field's outer box (a {@link ThemedEditBox}: the well it draws and takes clicks on). */
    private int boxX, boxY, boxW, boxH;
    private int listX, listY, listW, listH;
    private int scroll = 0;

    public StorageSearchScreen(Screen parent, String initialQuery) {
        super(Component.literal("Storage Search"));
        this.parent = parent;
        this.initialQuery = initialQuery == null ? "" : initialQuery;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(320, Math.min((int) (this.width * 0.7), 520)));
        panelH = Math.min(this.height - 20, Math.max(200, Math.min((int) (this.height * 0.82), 420)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        listX = panelX + 6;
        listY = panelY + 72;
        listW = panelW - 12;
        listH = panelH - 72 - 18;

        if (index == null) {
            index = StorageSearchIndex.build(true);
        }
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();

        String current = searchBox != null ? searchBox.getValue() : initialQuery;
        // Sort/source sit on the search row rather than the header strip - four buttons crammed next to the
        // title stopped fitting once killer560 asked for sorting (2026-09-21).
        // Wide enough for the longest "Sort: ..." label (killer560, 2026-09-21: "make the sort button a little bit
        // bigger to fully encompass the text").
        int sortW = 16;
        for (StorageSearchConfig.SortMode m : StorageSearchConfig.SortMode.values()) {
            sortW = Math.max(sortW, this.font.width("Sort: " + m.label()) + 12);
        }
        int sourceW = 84;
        int boxW = Math.max(80, panelW - 12 - sortW - sourceW - 8);
        boxX = panelX + 6;
        boxY = panelY + 36;
        this.boxW = boxW;
        boxH = 18;
        int boxAlpha = (int) Math.round(StorageSearchConfig.getInstance().getSearchBoxOpacity() * 255 / 100.0);
        searchBox = new ThemedEditBox(this.font, boxX, boxY, boxW, boxH, Component.literal("Search")).hint(hint())
                .wellAlpha(boxAlpha);
        searchBox.setMaxLength(100);
        searchBox.setValue(current);
        searchBox.setResponder(text -> refilter());
        addRenderableWidget(searchBox);

        sortButton = SettingsButtonWidget.builder(sortText(cfg), btn -> {
            cfg.setSortMode(cfg.getSortMode().next());
            cfg.save();
            btn.setMessage(sortText(cfg));
            refilter();
        }).secondaryPress(btn -> {
            cfg.setSortMode(cfg.getSortMode().previous());
            cfg.save();
            btn.setMessage(sortText(cfg));
            refilter();
        }).bounds(panelX + 6 + boxW + 4, panelY + 36, sortW, 18).build();
        sourceButton = SettingsButtonWidget.builder(sourceText(cfg), btn -> {
            cfg.setSourceFilter(cfg.getSourceFilter().next());
            cfg.save();
            btn.setMessage(sourceText(cfg));
            refilter();
        }).secondaryPress(btn -> {
            cfg.setSourceFilter(cfg.getSourceFilter().previous());
            cfg.save();
            btn.setMessage(sourceText(cfg));
            refilter();
        }).bounds(panelX + 6 + boxW + 8 + sortW, panelY + 36, sourceW, 18).build();
        addRenderableWidget(sortButton);
        addRenderableWidget(sourceButton);

        int bw = 62;
        invButton = SettingsButtonWidget.builder(onOff("Inv", cfg.isIncludeInventory()), btn -> {
            cfg.setIncludeInventory(!cfg.isIncludeInventory());
            cfg.save();
            btn.setMessage(onOff("Inv", cfg.isIncludeInventory()));
            refilter();
        }).bounds(panelX + panelW - bw - 6, panelY + 6, bw, 18).build();
        loreButton = SettingsButtonWidget.builder(onOff("Lore", cfg.isSearchLore()), btn -> {
            cfg.setSearchLore(!cfg.isSearchLore());
            cfg.save();
            btn.setMessage(onOff("Lore", cfg.isSearchLore()));
            searchBox.hint(hint());
            refilter();
        }).bounds(panelX + panelW - bw * 2 - 10, panelY + 6, bw, 18).build();
        addRenderableWidget(loreButton);
        addRenderableWidget(invButton);
        int scanW = this.font.width("Scan All") + 16;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Scan All"), btn -> {
            onClose();
            StorageScanAll.start();
        }).bounds(panelX + panelW - bw * 2 - 14 - scanW, panelY + 6, scanW, 18).build());

        setInitialFocus(searchBox);
        refilter();
    }

    /** The search field's hint. */
    private static String hint() {
        return "Name, Skyblock id" + (StorageSearchConfig.getInstance().isSearchLore() ? " or lore" : "") + "...";
    }

    private void refilter() {
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        results = index.filter(searchBox.getValue(), cfg.isSearchLore(), cfg.isIncludeInventory());
        scroll = 0;
    }

    private int maxScroll() {
        return Math.max(0, results.size() * ROW_H - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * ROW_H)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        StorageSearchIndex.Entry hit = entryAt(event.x(), event.y());
        if (hit != null && event.button() == 0) {
            StorageSearchFeature.onResultClicked(hit, results);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && !results.isEmpty()) {
            StorageSearchFeature.onResultClicked(results.get(0), results);
            return true;
        }
        return super.keyPressed(event);
    }

    private StorageSearchIndex.Entry entryAt(double mx, double my) {
        if (mx < listX || mx > listX + listW || my < listY || my >= listY + listH) {
            return null;
        }
        int i = (int) ((my - listY + scroll) / ROW_H);
        return i >= 0 && i < results.size() ? results.get(i) : null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, "Storage Search");


        // Summary line.
        int cached = 0;
        int unopened = 0;
        StorageSearchIndex.StorageInfo oldest = null;
        for (StorageSearchIndex.StorageInfo s : index.storages()) {
            if (!s.hasContents()) {
                unopened++;
                continue;
            }
            cached++;
            if (s.updatedMs() >= 0 && (oldest == null || s.updatedMs() < oldest.updatedMs())) {
                oldest = s;
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append(results.size()).append(" result").append(results.size() == 1 ? "" : "s")
                .append("  ·  ").append(cached).append(" storage").append(cached == 1 ? "" : "s").append(" cached");
        if (StorageSearchConfig.getInstance().isSearchChests()) {
            summary.append("  ·  ").append(index.knownChests()).append(" chest")
                    .append(index.knownChests() == 1 ? "" : "s");
        }
        MenuStyle.text(graphics, summary.toString(), panelX + 8, panelY + 59, MenuStyle.OR);
        int neverOpened = unopened + index.unopenedChests();
        if (neverOpened > 0) {
            String warn = neverOpened + " never opened";
            MenuStyle.text(graphics, warn, panelX + panelW - 8 - this.font.width(warn), panelY + 59, ScreenChrome.bad());
        }

        // Results list.
        ScreenChrome.well(graphics, listX - 1, listY - 1, listW + 2, listH + 2);
        StorageSearchIndex.Entry hovered = entryAt(mouseX, mouseY);
        if (minecraft.level == null) {
            MenuStyle.text(graphics, "Join a world to search your storages.", listX + 6, listY + 6, MenuStyle.DIM);
        } else if (results.isEmpty()) {
            String msg = index.entries().isEmpty()
                    ? "Nothing cached yet - open your Ender Chest pages / Backpacks once."
                    : "No matches.";
            MenuStyle.text(graphics, msg, listX + 6, listY + 6, MenuStyle.DIM);
        }
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        try {
            int first = Math.max(0, scroll / ROW_H);
            for (int i = first; i < results.size(); i++) {
                int rowY = listY + i * ROW_H - scroll;
                if (rowY > listY + listH) {
                    break;
                }
                drawRow(graphics, results.get(i), i, rowY, results.get(i) == hovered);
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            ScreenChrome.scrollbar(graphics, listX + listW - 4, listY, listH, scroll, maxScroll(), listH);
        }

        // Footer: the chunk-cache warning first (it changes what island-chest search can even see), else the
        // stalest cached storage.
        if (StorageSearchConfig.getInstance().isSearchChests() && !index.chunkCacheActive()) {
            String footer = "Chunk Cache is off - chests only count while their chunk is loaded.";
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(footer, panelW - 16), panelX + 8, panelY + panelH - 13,
                    ScreenChrome.bad());
        } else if (oldest != null) {
            String footer = "Oldest cache: " + oldest.label() + " · " + ageText(oldest.updatedMs(), oldest.updatedIsUpperBound());
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(footer, panelW - 16), panelX + 8, panelY + panelH - 13,
                    ageColor(oldest.updatedMs()));
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (hovered != null) {
            graphics.setTooltipForNextFrame(this.font, hovered.stack(), mouseX, mouseY);
        }
    }

    private void drawRow(GuiGraphicsExtractor graphics, StorageSearchIndex.Entry e, int i, int y, boolean hovered) {
        int x = listX;
        int w = listW - 4;
        ScreenChrome.row(graphics, x, y, w, ROW_H, hovered, false, (i) % 2 == 1);
        graphics.item(e.stack(), x + 3, y + 3);

        String count = "x" + e.stack().getCount();
        int countW = this.font.width(count);
        MenuStyle.text(graphics, count, x + w - 6 - countW, y + 3, MenuStyle.OR);

        int textX = x + 23;
        int nameMax = w - 23 - countW - 12;
        if (e.rarityRgb() >= 0) {
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(e.name(), nameMax), textX, y + 3,
                    ScreenChrome.readable(0xFF000000 | e.rarityRgb()));
        } else if (!e.name().equals(e.stack().getHoverName().getString())) {
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(e.name(), nameMax), textX, y + 3, MenuStyle.TXT);
        } else if (this.font.width(e.stack().getHoverName()) <= nameMax) {
            MenuStyle.text(graphics, ScreenChrome.readable(e.stack().getHoverName()), textX, y + 3, MenuStyle.TXT);
        } else {
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(e.name(), nameMax), textX, y + 3, MenuStyle.TXT);
        }

        String age;
        int ageColor;
        if (e.type() == StorageSearchIndex.SourceType.INVENTORY) {
            age = "live";
            ageColor = ScreenChrome.good();
        } else {
            age = ageText(e.updatedMs(), e.updatedIsUpperBound());
            ageColor = ageColor(e.updatedMs());
        }
        int ageW = this.font.width(age);
        MenuStyle.text(graphics, age, x + w - 6 - ageW, y + 12, ageColor);
        String loc = this.font.plainSubstrByWidth(e.location(), w - 23 - ageW - 12);
        MenuStyle.text(graphics, ScreenChrome.readable(loc), textX, y + 12, MenuStyle.DIM);
    }

    private static String ageText(long updatedMs, boolean upperBound) {
        if (updatedMs < 0) {
            return "updated ?";
        }
        return upperBound ? "updated before " + StorageSearchTimestamps.ago(updatedMs) : "updated " + StorageSearchTimestamps.ago(updatedMs);
    }

    /** The accent under an hour, dim under a day, red beyond - stale data should look stale. */
    private static int ageColor(long updatedMs) {
        if (updatedMs < 0) {
            return ScreenChrome.bad();
        }
        long age = System.currentTimeMillis() - updatedMs;
        if (age < 3_600_000L) {
            return MenuStyle.OR;
        }
        if (age < 86_400_000L) {
            return MenuStyle.DIM;
        }
        return ScreenChrome.bad();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component sortText(StorageSearchConfig cfg) {
        return Component.literal("Sort: §6" + cfg.getSortMode().label());
    }

    private static Component sourceText(StorageSearchConfig cfg) {
        return Component.literal("Show: §6" + cfg.getSourceFilter().label());
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public String themeId() {
        return "storage_search";
    }
}
