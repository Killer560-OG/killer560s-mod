package com.killer560.hub.auction.screen;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarCatalog;
import com.killer560.hub.auction.BazaarIcons;
import com.killer560.hub.auction.BazaarOrderLevel;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.auction.BazaarOrders;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.pathfinding.ProfileTracker;
import com.killer560.hub.util.ChatColors;
import com.killer560.hub.util.ServerCommands;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Bazaar browser (/killer560bz, or /bz with the override on). Redone 2026-10-07 after killer560: "The bazaar menu
 * needs redone. There is no way to see my listings, that left bar is goofy and I dont like the way this looks at all.
 * Also it is missing a lot of item gui's."
 * <p>
 * Left: Hypixel's own Bazaar categories (Farming, Mining, Combat, Woods &amp; Fishes, Oddities) and, under the selected
 * one, its groups as Hypixel's menu groups them ({@link BazaarCatalog}), with All and My Orders on top. The old rail
 * was built from the item catalog's gear categories, which almost no Bazaar product has, so it sat empty. Right: one
 * scrolling list (paging is gone) with buy, sell, margin and weekly volume in columns that drop out on narrow windows;
 * clicking a product opens its detail (both sides of the book, volumes, your orders on it) with a button that runs
 * Hypixel's own {@code /bz <name>} through {@link ServerCommands#toServer} - never {@code sendCommand}, which our own
 * {@code /bz} registration would catch (see BazaarFeature for the "Incorrect argument ... position 3" history).
 * My Orders shows what {@link BazaarOrders} last read from Hypixel's Manage Orders menu. This screen never trades.
 * <p>
 * Every text and region drawn in a frame is recorded ({@link #layoutReport}) so the testkit can check that nothing
 * overlaps or runs out of its box at any GUI scale.
 */
public final class BazaarScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int ACCENT_BRIGHT = 0xFFFFA040;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xF00D0D0D;
    private static final int SIDEBAR_BG = 0xFF130D07;
    private static final int LIST_BG = 0xFF0A0A0A;
    private static final int ROW_ALT = 0xFF101010;
    private static final int ROW_HOVER = 0xFF2A1A0A;
    private static final int SELECTED_BG = 0xFF3A2208;
    private static final int TEXT = 0xFFE8E0D8;
    private static final int DIM = 0xFF9A8C80;
    private static final int FAINT = 0xFF6A6058;
    private static final int GOLD = 0xFFFFC040;
    private static final int GREEN = 0xFF55DD55;
    private static final int RED = 0xFFFF5555;

    private static final int ROW_H = 20;
    private static final int SIDE_ROW_H = 13;
    private static final String ALL = "";
    private static final String ORDERS = "@orders";

    private final Screen parent;
    private EditBox searchBox;
    private SettingsButtonWidget sortButton;
    private SettingsButtonWidget refreshButton;
    private SettingsButtonWidget backButton;
    private SettingsButtonWidget bzButton;
    private String lastQuery = "";
    private BazaarProduct detail;

    private int listScroll;
    private int sideScroll;
    private String expandedCategory;

    private String cachedQuery;
    private List<BazaarProduct> cachedSource;
    private AuctionConfig.BazaarSortMode cachedSort;
    private String cachedSelection;
    private List<BazaarProduct> filtered = List.of();
    private Map<String, Integer> countBySelection = Map.of();
    private Map<String, BazaarProduct> byId = Map.of();

    private int panelX, panelY, panelW, panelH;
    private int sideX, sideY, sideW, sideH;
    private int mainX, mainY, mainW, mainH;
    private int headerY;

    private record Hotspot(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hotspot> hotspots = new ArrayList<>();
    private final List<String> layout = new ArrayList<>();
    private List<Component> pendingTooltip;

    public BazaarScreen(Screen parent) {
        super(Component.literal("Bazaar"));
        this.parent = parent;
        BazaarApi.ensureAutoStarted();
        if (!BazaarCatalog.isLoaded()) {
            CompletableFuture.runAsync(BazaarCatalog::ensureLoaded);
        }
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        BazaarIcons.revalidatePackModels();
        int margin = this.height < 300 ? 4 : 8;
        panelW = Math.min(this.width - 2 * margin, 700);
        panelH = this.height - 2 * margin;
        panelX = (this.width - panelW) / 2;
        panelY = margin;
        headerY = panelY + 6;

        int toolbarY = headerY + 14;
        sideX = panelX + 6;
        sideW = Math.max(84, Math.min(130, panelW / 5));
        mainX = sideX + sideW + 6;
        mainW = panelX + panelW - 6 - mainX;
        int bodyY = toolbarY + 16 + 6;
        int bodyBottom = panelY + panelH - 6;
        sideY = bodyY;
        sideH = bodyBottom - bodyY;
        mainY = bodyY;
        mainH = bodyBottom - bodyY;

        int refreshW = Math.max(this.font.width("Refresh") + 12, 50);
        int sortW = Math.max(this.font.width("Sort: Sell price") + 14, 90);
        int searchW = mainW - sortW - refreshW - 8;
        // The toolbar sits over the list only, so the sidebar's top edge lines up with the search box.
        searchBox = new EditBox(this.font, mainX, toolbarY, Math.max(40, searchW), 16, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search products..."));
        searchBox.setValue(lastQuery);
        // Set after the value, so a resize (init runs again) does not count as typing and close an open detail.
        searchBox.setResponder(text -> {
            if (text.equals(lastQuery)) {
                return;
            }
            lastQuery = text;
            listScroll = 0;
            detail = null;
            updateDetailWidgets();
        });
        addRenderableWidget(searchBox);

        AuctionConfig cfg = AuctionConfig.getInstance();
        sortButton = SettingsButtonWidget.builder(sortLabel(cfg), btn -> {
                    cfg.setLastBazaarSort(cfg.getLastBazaarSort().next());
                    cfg.save();
                    btn.setMessage(sortLabel(cfg));
                    listScroll = 0;
                }).secondaryPress(btn -> {
                    cfg.setLastBazaarSort(cfg.getLastBazaarSort().previous());
                    cfg.save();
                    btn.setMessage(sortLabel(cfg));
                    listScroll = 0;
                }).bounds(mainX + searchW + 4, toolbarY, sortW, 16).build();
        addRenderableWidget(sortButton);
        refreshButton = SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> BazaarApi.refreshAsync())
                .bounds(mainX + mainW - refreshW, toolbarY, refreshW, 16).build();
        addRenderableWidget(refreshButton);

        int backW = this.font.width("< Back") + 12;
        backButton = SettingsButtonWidget.builder(Component.literal("< Back"), btn -> closeDetail())
                .bounds(mainX + 4, mainY + 4, backW, 14).build();
        addRenderableWidget(backButton);
        int bzW = this.font.width("Open in /bz") + 14;
        bzButton = SettingsButtonWidget.builder(Component.literal("Open in /bz"), btn -> openInHypixel())
                .bounds(mainX + mainW - 4 - bzW, mainY + 4, bzW, 14).primary().build();
        addRenderableWidget(bzButton);
        updateDetailWidgets();

        String sel = cfg.getLastBazaarCategoryFilter();
        if (!isKnownSelection(sel)) {
            cfg.setLastBazaarCategoryFilter(ALL);
        }
        expandedCategory = categoryOf(cfg.getLastBazaarCategoryFilter());
    }

    private static boolean isKnownSelection(String sel) {
        if (sel == null || sel.equals(ALL) || sel.equals(ORDERS)) {
            return true;
        }
        String cat = categoryOf(sel);
        return BazaarCatalog.categories().contains(cat);
    }

    private static String categoryOf(String sel) {
        if (sel == null || sel.isEmpty() || sel.equals(ORDERS)) {
            return null;
        }
        int slash = sel.indexOf('/');
        return slash < 0 ? sel : sel.substring(0, slash);
    }

    private void updateDetailWidgets() {
        boolean d = detail != null;
        if (backButton != null) {
            backButton.visible = d;
            bzButton.visible = d;
            bzButton.active = d && this.minecraft != null && this.minecraft.player != null;
        }
    }

    private static Component sortLabel(AuctionConfig cfg) {
        String s = switch (cfg.getLastBazaarSort()) {
            case BUY_PRICE_HIGH -> "Buy price";
            case SELL_PRICE_HIGH -> "Sell price";
            case SPREAD_HIGH -> "Spread";
            case VOLUME_HIGH -> "Volume";
        };
        return Component.literal("Sort: §6" + s);
    }

    // ---- data -----------------------------------------------------------------------------------------------------

    private String selection() {
        return AuctionConfig.getInstance().getLastBazaarCategoryFilter();
    }

    private void select(String sel) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setLastBazaarCategoryFilter(sel);
        cfg.save();
        String cat = categoryOf(sel);
        if (cat != null) {
            expandedCategory = cat;
        }
        listScroll = 0;
        detail = null;
        updateDetailWidgets();
    }

    private static boolean matches(BazaarProduct p, String sel) {
        if (sel.isEmpty()) {
            return true;
        }
        int slash = sel.indexOf('/');
        if (slash < 0) {
            return sel.equals(p.category());
        }
        return sel.substring(0, slash).equals(p.category()) && sel.substring(slash + 1).equals(p.group());
    }

    private void refilterIfNeeded() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        List<BazaarProduct> source = BazaarApi.getProducts();
        AuctionConfig.BazaarSortMode sort = cfg.getLastBazaarSort();
        String sel = selection();
        if (source == cachedSource && lastQuery.equals(cachedQuery) && sort == cachedSort && sel.equals(cachedSelection)) {
            return;
        }
        if (source != cachedSource) {
            Map<String, Integer> counts = new HashMap<>();
            Map<String, BazaarProduct> ids = new HashMap<>();
            for (BazaarProduct p : source) {
                ids.put(p.productId(), p);
                counts.merge(p.category(), 1, Integer::sum);
                counts.merge(p.category() + "/" + p.group(), 1, Integer::sum);
            }
            countBySelection = counts;
            byId = ids;
        }
        cachedSource = source;
        cachedQuery = lastQuery;
        cachedSort = sort;
        cachedSelection = sel;
        String q = lastQuery.trim().toLowerCase(Locale.ROOT);
        List<BazaarProduct> out = new ArrayList<>();
        if (!sel.equals(ORDERS)) {
            for (BazaarProduct p : source) {
                if (!q.isEmpty() && !p.displayName().toLowerCase(Locale.ROOT).contains(q)
                        && !p.productId().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                // A search looks through everything; the sidebar narrows only an empty search.
                if (q.isEmpty() && !matches(p, sel)) {
                    continue;
                }
                out.add(p);
            }
        }
        out.sort(comparatorFor(sort));
        filtered = out;
    }

    private static Comparator<BazaarProduct> comparatorFor(AuctionConfig.BazaarSortMode mode) {
        return switch (mode) {
            case BUY_PRICE_HIGH -> Comparator.comparingDouble(BazaarProduct::buyPrice).reversed();
            case SELL_PRICE_HIGH -> Comparator.comparingDouble(BazaarProduct::sellPrice).reversed();
            case SPREAD_HIGH -> Comparator.comparingDouble(BazaarProduct::spread).reversed();
            case VOLUME_HIGH -> Comparator.comparingLong(BazaarProduct::weeklyVolume).reversed();
        };
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY) * ROW_H * 3;
        if (mouseX >= sideX && mouseX < sideX + sideW && mouseY >= sideY && mouseY < sideY + sideH) {
            sideScroll = Math.max(0, sideScroll - step);
            return true;
        }
        if (detail == null && mouseX >= mainX && mouseX < mainX + mainW && mouseY >= mainY && mouseY < mainY + mainH) {
            listScroll = Math.max(0, listScroll - step);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (Hotspot h : hotspots) {
                if (h.contains(event.x(), event.y())) {
                    this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (detail != null && keyEvent.key() == InputConstants.KEY_ESCAPE) {
            closeDetail();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    private void openDetail(BazaarProduct p) {
        detail = p;
        updateDetailWidgets();
    }

    private void closeDetail() {
        detail = null;
        updateDetailWidgets();
    }

    private void openInHypixel() {
        if (detail == null) {
            return;
        }
        // "bz" is one of our own client commands, so sendCommand would hand this back to us rather than to Hypixel.
        ServerCommands.toServer("bz " + detail.displayName());
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hotspots.clear();
        layout.clear();
        pendingTooltip = null;
        refilterIfNeeded();
        if (detail != null && byId.get(detail.productId()) != null) {
            detail = byId.get(detail.productId()); // follow a refresh
        }
        bzButton.active = detail != null && this.minecraft.player != null;

        g.fill(0, 0, this.width, this.height, 0xB0000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, BORDER);
        region("panel", panelX, panelY, panelW, panelH);

        // Header: title left, status right.
        region("header", panelX + 6, headerY, panelW - 12, 10);
        text(g, "header", "§lBazaar", panelX + 6, headerY, ACCENT_BRIGHT);
        int titleW = this.font.width("§lBazaar") + 12;
        String status = fit(statusLine(), panelW - 12 - titleW);
        text(g, "header", status, Math.max(panelX + 6 + titleW, panelX + panelW - 6 - this.font.width(status)),
                headerY, DIM);

        drawSidebar(g, mouseX, mouseY);

        g.fill(mainX, mainY, mainX + mainW, mainY + mainH, LIST_BG);
        g.outline(mainX, mainY, mainW, mainH, BORDER);
        region("main", mainX, mainY, mainW, mainH);
        if (detail != null) {
            drawDetail(g, detail, mouseX, mouseY);
        } else if (selection().equals(ORDERS) && lastQuery.isBlank()) {
            drawOrders(g, mouseX, mouseY);
        } else {
            drawList(g, mouseX, mouseY);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
        for (var child : this.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible) {
                layout.add("widget " + w.getMessage().getString().replace(' ', '_') + " " + w.getX() + " " + w.getY()
                        + " " + w.getWidth() + " " + w.getHeight());
            }
        }

        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.setTooltipForNextFrame(this.font, pendingTooltip, Optional.empty(), mouseX, mouseY);
        }
    }

    private String statusLine() {
        int n = BazaarApi.getProducts().size();
        String base = n + " products";
        if (BazaarApi.isRefreshing()) {
            return base + " · refreshing...";
        }
        long fetched = BazaarApi.getLastFetchedMs();
        if (fetched == 0) {
            return base;
        }
        return base + " · updated " + ago(fetched);
    }

    private static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m ago";
        }
        if (s < 86400 * 2) {
            return (s / 3600) + "h ago";
        }
        return (s / 86400) + "d ago";
    }

    // Sidebar ----------------------------------------------------------------------------------------------------

    private record SideRow(String key, String label, int indent, boolean heading) {
    }

    private List<SideRow> sideRows() {
        List<SideRow> rows = new ArrayList<>();
        rows.add(new SideRow(ALL, "All products", 0, true));
        int orders = BazaarOrders.currentOrders().size();
        rows.add(new SideRow(ORDERS, "My Orders" + (orders > 0 ? " (" + orders + ")" : ""), 0, true));
        for (String cat : BazaarCatalog.categories()) {
            rows.add(new SideRow(cat, cat, 0, true));
            if (cat.equals(expandedCategory)) {
                for (Map.Entry<Integer, String> grp : BazaarCatalog.groupsOf(cat).entrySet()) {
                    String key = cat + "/" + grp.getValue();
                    if (countBySelection.getOrDefault(key, 0) > 0) {
                        rows.add(new SideRow(key, grp.getValue(), 8, false));
                    }
                }
            }
        }
        return rows;
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(sideX, sideY, sideX + sideW, sideY + sideH, SIDEBAR_BG);
        g.outline(sideX, sideY, sideW, sideH, BORDER);
        region("sidebar", sideX, sideY, sideW, sideH);
        List<SideRow> rows = sideRows();
        int contentH = rows.size() * SIDE_ROW_H + 8 + 2 * 4;
        int maxScroll = Math.max(0, contentH - sideH);
        sideScroll = Math.min(sideScroll, maxScroll);
        String sel = selection();
        int top = sideY + 3;
        int bottom = sideY + sideH - 3;
        g.enableScissor(sideX + 1, top, sideX + sideW - 1, bottom);
        try {
            int y = top - sideScroll;
            for (int i = 0; i < rows.size(); i++) {
                SideRow r = rows.get(i);
                if (i == 2) {
                    y += 4; // a gap between the two views and the categories
                    g.fill(sideX + 6, y - 2, sideX + sideW - 6, y - 1, BORDER);
                }
                int rowY = y;
                y += SIDE_ROW_H;
                if (rowY + SIDE_ROW_H <= top || rowY >= bottom) {
                    continue;
                }
                boolean selected = r.key().equals(sel);
                boolean hover = mouseX >= sideX && mouseX < sideX + sideW && mouseY >= Math.max(rowY, top)
                        && mouseY < Math.min(rowY + SIDE_ROW_H, bottom);
                if (selected) {
                    g.fill(sideX + 1, rowY, sideX + sideW - 1, rowY + SIDE_ROW_H, SELECTED_BG);
                    g.fill(sideX + 1, rowY, sideX + 3, rowY + SIDE_ROW_H, ACCENT);
                } else if (hover) {
                    g.fill(sideX + 1, rowY, sideX + sideW - 1, rowY + SIDE_ROW_H, ROW_HOVER);
                }
                int color = selected ? ACCENT_BRIGHT : r.heading() ? TEXT : DIM;
                String label = r.label();
                if (r.heading() && !r.key().equals(ALL) && !r.key().equals(ORDERS)) {
                    label = (r.key().equals(expandedCategory) ? "▾ " : "▸ ") + label;
                }
                int x = sideX + 6 + r.indent();
                String count = r.key().equals(ALL) || r.key().equals(ORDERS) ? ""
                        : String.valueOf(countBySelection.getOrDefault(r.key(), 0));
                int countW = count.isEmpty() ? 0 : this.font.width(count) + 4;
                if (rowY >= top && rowY + SIDE_ROW_H <= bottom) {
                    text(g, "sidebar", fit(label, sideX + sideW - 5 - countW - x), x, rowY + 2, color);
                    if (!count.isEmpty()) {
                        text(g, "sidebar", count, sideX + sideW - 5 - this.font.width(count), rowY + 2, FAINT);
                    }
                } else {
                    // Partly scrolled out: drawn but clipped by the scissor, so not recorded as a layout box.
                    g.text(this.font, fit(label, sideX + sideW - 5 - countW - x), x, rowY + 2, color, false);
                }
                String key = r.key();
                int hy = Math.max(rowY, top);
                int hh = Math.min(rowY + SIDE_ROW_H, bottom) - hy;
                if (hh > 0) {
                    hotspots.add(new Hotspot(sideX, hy, sideW, hh, () -> {
                        if (r.heading() && key.equals(expandedCategory) && key.equals(selection())) {
                            expandedCategory = null; // a second click on the open category folds it
                        } else {
                            select(key);
                        }
                    }));
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            drawScrollbar(g, sideX + sideW - 3, top, bottom - top, sideScroll, maxScroll, contentH);
        }
    }

    // Product list ---------------------------------------------------------------------------------------------

    private record Columns(int nameW, int buyX, int sellX, int marginX, int volX, int colW, boolean margin, boolean vol) {
    }

    private Columns columns(int x, int w) {
        int colW = Math.max(this.font.width("Sell offer") + 6, this.font.width("999.9M") + 10);
        int pad = 4;
        int iconW = 20;
        boolean vol = w - iconW - pad * 2 - colW * 4 >= 90;
        boolean margin = w - iconW - pad * 2 - colW * (vol ? 4 : 3) >= 80;
        int right = x + w - pad - 4; // keep clear of the scrollbar
        int volX = vol ? right - colW : right;
        int marginX = margin ? volX - colW : volX;
        int sellX = marginX - colW;
        int buyX = sellX - colW;
        int nameW = buyX - (x + iconW + 2) - 4;
        return new Columns(nameW, buyX, sellX, marginX, volX, colW, margin, vol);
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Columns c = columns(mainX, mainW);
        int headY = mainY + 4;
        region("list-head", mainX + 1, headY, mainW - 2, 10);
        text(g, "list-head", fit(listTitle(), c.nameW() + 18), mainX + 6, headY, DIM);
        rightText(g, "list-head", "Buy", c.buyX() + c.colW(), headY, FAINT);
        rightText(g, "list-head", "Sell", c.sellX() + c.colW(), headY, FAINT);
        if (c.margin()) {
            rightText(g, "list-head", "Margin", c.marginX() + c.colW(), headY, FAINT);
        }
        if (c.vol()) {
            rightText(g, "list-head", "7d vol", c.volX() + c.colW(), headY, FAINT);
        }
        int top = headY + 12;
        g.fill(mainX + 4, top - 2, mainX + mainW - 4, top - 1, BORDER);
        int bottom = mainY + mainH - 2;
        region("list", mainX + 1, top, mainW - 2, bottom - top);

        if (filtered.isEmpty()) {
            String msg = BazaarApi.getProducts().isEmpty()
                    ? (BazaarApi.isRefreshing() ? "Fetching Bazaar prices..." : "No data yet - press Refresh.")
                    : "Nothing matches your search.";
            text(g, "list", fit(msg, mainW - 16), mainX + 8, top + 6, DIM);
            return;
        }
        int contentH = filtered.size() * ROW_H;
        int maxScroll = Math.max(0, contentH - (bottom - top));
        listScroll = Math.min(listScroll, maxScroll);
        int first = listScroll / ROW_H;
        BazaarProduct hovered = null;
        g.enableScissor(mainX + 1, top, mainX + mainW - 1, bottom);
        try {
            for (int i = first; i < filtered.size(); i++) {
                int rowY = top + i * ROW_H - listScroll;
                if (rowY >= bottom) {
                    break;
                }
                BazaarProduct p = filtered.get(i);
                boolean whole = rowY >= top && rowY + ROW_H <= bottom;
                boolean hover = mouseX >= mainX && mouseX < mainX + mainW - 6 && mouseY >= Math.max(top, rowY)
                        && mouseY < Math.min(bottom, rowY + ROW_H);
                if (hover) {
                    hovered = p;
                    g.fill(mainX + 1, rowY, mainX + mainW - 1, rowY + ROW_H, ROW_HOVER);
                } else if (i % 2 == 1) {
                    g.fill(mainX + 1, rowY, mainX + mainW - 1, rowY + ROW_H, ROW_ALT);
                }
                drawProductRow(g, p, rowY, c, whole);
                int hy = Math.max(rowY, top);
                int hh = Math.min(rowY + ROW_H, bottom) - hy;
                if (hh > 0) {
                    hotspots.add(new Hotspot(mainX, hy, mainW - 6, hh, () -> openDetail(p)));
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            drawScrollbar(g, mainX + mainW - 4, top, bottom - top, listScroll, maxScroll, contentH);
        }
        if (hovered != null) {
            pendingTooltip = rowTooltip(hovered);
        }
    }

    private String listTitle() {
        String sel = selection();
        if (!lastQuery.isBlank()) {
            return filtered.size() + " results for \"" + lastQuery.trim() + "\"";
        }
        if (sel.isEmpty()) {
            return "All products (" + filtered.size() + ")";
        }
        return sel.replace("/", " › ") + " (" + filtered.size() + ")";
    }

    private void drawProductRow(GuiGraphicsExtractor g, BazaarProduct p, int y, Columns c, boolean record) {
        int x = mainX + 3;
        g.item(BazaarIcons.icon(p.productId()), x, y + 2);
        int tx = x + 20;
        String name = fit(p.displayName(), c.nameW());
        String sub = fit(p.category() + " › " + p.group(), c.nameW());
        String buy = price(p.buyPrice());
        String sell = price(p.sellPrice());
        String margin = String.format(Locale.US, "%.1f%%", p.marginPercent());
        String vol = shortNumber(p.weeklyVolume());
        if (record) {
            text(g, "list", name, tx, y + 2, nameColor(p));
            text(g, "list", sub, tx, y + 11, FAINT);
            rightText(g, "list", buy, c.buyX() + c.colW(), y + 6, GOLD);
            rightText(g, "list", sell, c.sellX() + c.colW(), y + 6, GOLD);
            if (c.margin()) {
                rightText(g, "list", margin, c.marginX() + c.colW(), y + 6, p.spread() >= 0 ? GREEN : RED);
            }
            if (c.vol()) {
                rightText(g, "list", vol, c.volX() + c.colW(), y + 6, DIM);
            }
        } else {
            g.text(this.font, name, tx, y + 2, nameColor(p), false);
            g.text(this.font, sub, tx, y + 11, FAINT, false);
            g.text(this.font, buy, c.buyX() + c.colW() - this.font.width(buy), y + 6, GOLD, false);
            g.text(this.font, sell, c.sellX() + c.colW() - this.font.width(sell), y + 6, GOLD, false);
            if (c.margin()) {
                g.text(this.font, margin, c.marginX() + c.colW() - this.font.width(margin), y + 6,
                        p.spread() >= 0 ? GREEN : RED, false);
            }
            if (c.vol()) {
                g.text(this.font, vol, c.volX() + c.colW() - this.font.width(vol), y + 6, DIM, false);
            }
        }
    }

    private List<Component> rowTooltip(BazaarProduct p) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(p.displayName()));
        lines.add(Component.literal("§7Instant buy: §6" + price(p.buyPrice()) + " coins"));
        lines.add(Component.literal("§7Instant sell: §6" + price(p.sellPrice()) + " coins"));
        lines.add(Component.literal("§7Spread: " + (p.spread() >= 0 ? "§a" : "§c") + price(p.spread())
                + String.format(Locale.US, " (%.1f%%)", p.marginPercent())));
        lines.add(Component.literal("§8Click for details"));
        return lines;
    }

    // Detail -------------------------------------------------------------------------------------------------------

    private void drawDetail(GuiGraphicsExtractor g, BazaarProduct p, int mouseX, int mouseY) {
        int x = mainX + 8;
        int right = mainX + mainW - 8;
        int y = mainY + 24;
        region("detail", mainX + 1, mainY + 1, mainW - 2, mainH - 2);
        g.fill(mainX + 4, mainY + 21, mainX + mainW - 4, mainY + 22, BORDER);

        ItemStack icon = BazaarIcons.icon(p.productId());
        g.pose().pushMatrix();
        g.pose().translate(x, y + 2);
        g.pose().scale(2f, 2f);
        g.item(icon, 0, 0);
        g.pose().popMatrix();
        int tx = x + 40;
        int textW = right - tx;
        text(g, "detail", fit(p.displayName(), textW), tx, y + 3, nameColor(p));
        if (y + 23 <= mainY + mainH - 4) {
            text(g, "detail", fit(p.category() + " › " + p.group(), textW), tx, y + 14, DIM);
        }
        if (y + 33 <= mainY + mainH - 4) {
            text(g, "detail", fit(p.productId(), textW), tx, y + 24, FAINT);
        }
        y += 40;

        // Prices and volumes, two columns of label/value pairs.
        String[][] stats = {
                {"Instant buy", price(p.buyPrice()) + " coins"},
                {"Instant sell", price(p.sellPrice()) + " coins"},
                {"Spread", price(p.spread()) + String.format(Locale.US, " (%.1f%%)", p.marginPercent())},
                {"After 1.25% tax", price(p.buyPrice() * 0.9875 - p.sellPrice()) + " per item"},
                {"Sell offers", shortNumber(p.buyVolume()) + " in " + p.buyOrders()},
                {"Buy orders", shortNumber(p.sellVolume()) + " in " + p.sellOrders()},
                {"Instant buys 7d", shortNumber(p.buyMovingWeek())},
                {"Instant sells 7d", shortNumber(p.sellMovingWeek())},
        };
        int colW = (right - x) / 2;
        boolean twoCols = colW >= 120;
        int perCol = twoCols ? (stats.length + 1) / 2 : stats.length;
        int bottom = mainY + mainH - 4;
        for (int i = 0; i < stats.length; i++) {
            int cx = twoCols && i >= perCol ? x + colW : x;
            int cy = y + (twoCols ? i % perCol : i) * 11;
            if (cy + 9 > bottom) {
                continue; // a short window: the rest of the stats do not fit, and nothing may spill out of the box
            }
            int cw = twoCols ? colW - 6 : right - x;
            int valW = this.font.width(stats[i][1]);
            String label = fit(stats[i][0], cw - valW - 6);
            text(g, "detail", label, cx, cy, DIM);
            int vColor = i < 2 ? GOLD : i < 4 ? (p.spread() >= 0 ? GREEN : RED) : TEXT;
            String value = fit(stats[i][1], cw - this.font.width(label) - 6);
            text(g, "detail", value, cx + cw - this.font.width(value), cy, vColor);
        }
        y += perCol * 11 + 8;

        List<BazaarOrderParser.Order> mine = new ArrayList<>();
        for (BazaarOrderParser.Order o : BazaarOrders.currentOrders()) {
            if (p.productId().equals(o.productId())) {
                mine.add(o);
            }
        }
        int mineH = mine.isEmpty() ? 0 : 14 + Math.min(mine.size(), 4) * 11;
        int bookBottom = Math.max(y, bottom - mineH);
        if (twoCols) {
            drawBook(g, "Top buy orders", p.topBuyOrders(), x, y, colW - 6, bookBottom);
            drawBook(g, "Top sell offers", p.topSellOffers(), x + colW, y, colW - 6, bookBottom);
        } else {
            int half = (bookBottom - y) / 2;
            drawBook(g, "Top buy orders", p.topBuyOrders(), x, y, right - x, y + half);
            drawBook(g, "Top sell offers", p.topSellOffers(), x, y + half, right - x, bookBottom);
        }
        if (!mine.isEmpty() && bookBottom + 2 + 9 <= bottom) {
            int my = bookBottom + 2;
            text(g, "detail", "Your orders", x, my, ACCENT_BRIGHT);
            my += 12;
            for (int i = 0; i < mine.size() && i < 4 && my + 9 <= bottom; i++) {
                BazaarOrderParser.Order o = mine.get(i);
                String line = (o.type() == BazaarOrderParser.Type.BUY ? "Buy " : "Sell ") + o.filled() + "/" + o.amount()
                        + " at " + price(o.pricePerUnit()) + " · " + standingText(o, p);
                text(g, "detail", fit(line, right - x), x, my, TEXT);
                my += 11;
            }
        }
    }

    private void drawBook(GuiGraphicsExtractor g, String title, List<BazaarOrderLevel> levels, int x, int y, int w,
            int bottom) {
        if (y + 10 > bottom) {
            return;
        }
        text(g, "detail", fit(title, w), x, y, ACCENT_BRIGHT);
        y += 12;
        if (levels.isEmpty()) {
            if (y + 9 <= bottom) {
                text(g, "detail", "None right now", x, y, FAINT);
            }
            return;
        }
        for (BazaarOrderLevel l : levels) {
            if (y + 9 > bottom) {
                break;
            }
            String priceText = price(l.pricePerUnit());
            String amount = shortNumber(l.amount()) + "x";
            String orders = l.orders() + (l.orders() == 1 ? " order" : " orders");
            int ordersW = this.font.width(orders);
            boolean showOrders = this.font.width(priceText) + this.font.width(amount) + ordersW + 16 <= w;
            text(g, "detail", fit(priceText, w / 2), x, y, GOLD);
            int amountRight = showOrders ? x + w - ordersW - 8 : x + w;
            rightText(g, "detail", amount, amountRight, y, TEXT);
            if (showOrders) {
                rightText(g, "detail", orders, x + w, y, FAINT);
            }
            y += 10;
        }
    }

    // My Orders ---------------------------------------------------------------------------------------------------

    private void drawOrders(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = mainX + 6;
        int right = mainX + mainW - 6;
        int headY = mainY + 4;
        region("orders-head", mainX + 1, headY, mainW - 2, 10);
        BazaarOrders.Snapshot snap = BazaarOrders.current();
        AuctionConfig cfg = AuctionConfig.getInstance();
        String head = !cfg.isTrackBazaarOrders() ? "Order tracking is off (Bazaar settings)"
                : snap == null ? "No orders read yet"
                : "Read " + ago(snap.seenAtMs()) + " from Manage Orders · " + ProfileTracker.displayName();
        text(g, "orders-head", fit(head, right - x), x, headY, DIM);
        int top = headY + 12;
        g.fill(mainX + 4, top - 2, mainX + mainW - 4, top - 1, BORDER);
        int bottom = mainY + mainH - 2;
        region("orders", mainX + 1, top, mainW - 2, bottom - top);
        List<BazaarOrderParser.Order> orders = snap == null ? List.of() : snap.orders();
        if (orders.isEmpty()) {
            String[] help = snap == null
                    ? new String[]{"Open the Bazaar on Hypixel and click", "Manage Orders once. Your buy orders and",
                    "sell offers show up here from then on.", "", "Nothing is ever clicked for you."}
                    : new String[]{"You had no open orders when Manage", "Orders was last opened."};
            int y = top + 8;
            for (String line : help) {
                if (!line.isEmpty() && y + 9 <= bottom) {
                    text(g, "orders", fit(line, right - x - 4), x + 2, y, DIM);
                }
                y += 11;
            }
            return;
        }
        int rowH = 22;
        int contentH = orders.size() * rowH;
        int maxScroll = Math.max(0, contentH - (bottom - top));
        listScroll = Math.min(listScroll, maxScroll);
        g.enableScissor(mainX + 1, top, mainX + mainW - 1, bottom);
        try {
            for (int i = 0; i < orders.size(); i++) {
                int rowY = top + i * rowH - listScroll;
                if (rowY + rowH <= top) {
                    continue;
                }
                if (rowY >= bottom) {
                    break;
                }
                BazaarOrderParser.Order o = orders.get(i);
                BazaarProduct live = o.productId() == null ? null : byId.get(o.productId());
                boolean whole = rowY >= top && rowY + rowH <= bottom;
                boolean hover = mouseX >= mainX && mouseX < mainX + mainW - 6 && mouseY >= Math.max(top, rowY)
                        && mouseY < Math.min(bottom, rowY + rowH);
                if (hover && live != null) {
                    g.fill(mainX + 1, rowY, mainX + mainW - 1, rowY + rowH, ROW_HOVER);
                } else if (i % 2 == 1) {
                    g.fill(mainX + 1, rowY, mainX + mainW - 1, rowY + rowH, ROW_ALT);
                }
                drawOrderRow(g, o, live, x, rowY, right, whole);
                if (live != null) {
                    int hy = Math.max(rowY, top);
                    int hh = Math.min(rowY + rowH, bottom) - hy;
                    if (hh > 0) {
                        hotspots.add(new Hotspot(mainX, hy, mainW - 6, hh, () -> openDetail(live)));
                    }
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            drawScrollbar(g, mainX + mainW - 4, top, bottom - top, listScroll, maxScroll, contentH);
        }
    }

    private void drawOrderRow(GuiGraphicsExtractor g, BazaarOrderParser.Order o, BazaarProduct live, int x, int y,
            int right, boolean record) {
        ItemStack icon = o.productId() != null ? BazaarIcons.icon(o.productId()) : new ItemStack(net.minecraft.world.item.Items.PAPER);
        g.item(icon, x - 3, y + 3);
        boolean buy = o.type() == BazaarOrderParser.Type.BUY;
        String tag = buy ? "BUY" : "SELL";
        int tagW = this.font.width("SELL") + 6;
        int tx = x + 17;
        g.fill(tx, y + 2, tx + tagW, y + 11, buy ? 0xFF1F4A1F : 0xFF4A3A10);
        String status = standingText(o, live);
        int statusColor = statusColor(o, live);
        int statusW = this.font.width(status);
        int nameX = tx + tagW + 4;
        String name = fit(o.productName(), right - statusW - 8 - nameX);
        String detailLine = fit(shortNumber(o.filled()) + "/" + shortNumber(o.amount()) + " filled · "
                + price(o.pricePerUnit()) + " each · " + price(o.total()) + " total", right - tx);
        if (record) {
            text(g, "orders", tag, tx + (tagW - this.font.width(tag)) / 2, y + 3, buy ? GREEN : GOLD);
            text(g, "orders", name, nameX, y + 3, TEXT);
            text(g, "orders", status, right - statusW, y + 3, statusColor);
            text(g, "orders", detailLine, tx, y + 12, FAINT);
        } else {
            g.text(this.font, name, nameX, y + 3, TEXT, false);
            g.text(this.font, detailLine, tx, y + 12, FAINT, false);
        }
    }

    private static String standingText(BazaarOrderParser.Order o, BazaarProduct live) {
        if (o.expired()) {
            return "Expired";
        }
        if (o.claimable()) {
            return o.full() ? "Filled - claim" : "Claimable";
        }
        if (o.full()) {
            return "Filled";
        }
        return switch (BazaarOrders.standing(o, live)) {
            case TOP -> "Top order";
            case MATCHED -> "Matched top";
            case OUTBID -> (o.type() == BazaarOrderParser.Type.BUY ? "Outbid: " : "Undercut: ")
                    + price(o.type() == BazaarOrderParser.Type.BUY ? live.topBuyOrderPrice() : live.topSellOfferPrice());
            case UNKNOWN -> "No live price";
        };
    }

    private static int statusColor(BazaarOrderParser.Order o, BazaarProduct live) {
        if (o.expired()) {
            return RED;
        }
        if (o.claimable() || o.full()) {
            return GREEN;
        }
        return switch (BazaarOrders.standing(o, live)) {
            case TOP -> GREEN;
            case MATCHED -> GOLD;
            case OUTBID -> RED;
            case UNKNOWN -> FAINT;
        };
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void drawScrollbar(GuiGraphicsExtractor g, int x, int y, int h, int scroll, int maxScroll, int contentH) {
        g.fill(x, y, x + 2, y + h, 0xFF1E1E1E);
        int thumbH = Math.max(12, (int) ((long) h * h / Math.max(1, contentH)));
        int thumbY = y + (int) ((long) (h - thumbH) * scroll / Math.max(1, maxScroll));
        g.fill(x, thumbY, x + 2, thumbY + thumbH, ACCENT);
    }

    private void region(String name, int x, int y, int w, int h) {
        layout.add("region " + name + " " + x + " " + y + " " + w + " " + h);
    }

    private void text(GuiGraphicsExtractor g, String region, String s, int x, int y, int color) {
        if (s.isEmpty()) {
            return;
        }
        g.text(this.font, s, x, y, color, false);
        layout.add("text " + region + " " + x + " " + y + " " + this.font.width(s) + " " + 8 + " " + s);
    }

    private void rightText(GuiGraphicsExtractor g, String region, String s, int rightEdge, int y, int color) {
        text(g, region, s, rightEdge - this.font.width(s), y, color);
    }

    /** {@code s} cut to {@code width} pixels, ending in "..." when it had to be cut. */
    private String fit(String s, int width) {
        if (width <= 0) {
            return "";
        }
        if (this.font.width(s) <= width) {
            return s;
        }
        String cut = this.font.plainSubstrByWidth(s, Math.max(0, width - this.font.width("...")));
        return cut.isEmpty() ? "" : cut + "...";
    }

    private static int nameColor(BazaarProduct p) {
        if (p.ultimate()) {
            return 0xFFFF55FF;
        }
        String code = SkyblockItemStackFactory.tierColorCode(p.tier());
        char c = code.length() >= 2 ? code.charAt(1) : 'f';
        ChatFormatting cf = ChatFormatting.getByCode(c);
        Integer rgb = cf != null ? ChatColors.color(cf) : null;
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }

    /** Coins, compact enough for a column: 4.4, 1,234.5, 123.4k, 12.35M, 1.23B. */
    static String price(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) {
            return String.format(Locale.US, "%.2fB", v / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.US, "%.2fM", v / 1e6);
        }
        if (a >= 1e5) {
            return String.format(Locale.US, "%.1fk", v / 1e3);
        }
        return String.format(Locale.US, "%,.1f", v);
    }

    static String shortNumber(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fB", n / 1e9);
        }
        if (n >= 1_000_000L) {
            return String.format(Locale.US, "%.2fM", n / 1e6);
        }
        if (n >= 10_000L) {
            return String.format(Locale.US, "%.1fk", n / 1e3);
        }
        return String.format(Locale.US, "%,d", n);
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /**
     * Every region, text and visible widget box of the LAST frame, one per line: {@code region <name> x y w h},
     * {@code text <region> x y w h <text>}, {@code widget <label> x y w h}. Texts carry the region they belong to,
     * so a check can require each one inside its region and the screen, and no two overlapping.
     */
    public List<String> layoutReport() {
        return List.copyOf(layout);
    }

    /** Opens the detail of {@code productId} (testkit), returning false when it is not in the current data. */
    public boolean openDetailForTest(String productId) {
        BazaarProduct p = byId.get(productId);
        if (p == null) {
            for (BazaarProduct q : BazaarApi.getProducts()) {
                if (q.productId().equals(productId)) {
                    p = q;
                }
            }
        }
        if (p == null) {
            return false;
        }
        openDetail(p);
        return true;
    }

    /** Leaves the detail view and selects a sidebar entry ("" All, "@orders", "Farming", "Farming/Carrot"). */
    public void selectForTest(String key) {
        select(key);
    }

    /** The command the detail view's button would send, or null outside the detail view (testkit). */
    public String bzCommandForTest() {
        return detail == null ? null : "bz " + detail.displayName();
    }

    /** How many products the list currently shows. */
    public int shownCountForTest() {
        refilterIfNeeded();
        return filtered.size();
    }
}
