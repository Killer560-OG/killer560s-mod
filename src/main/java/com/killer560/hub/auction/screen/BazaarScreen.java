package com.killer560.hub.auction.screen;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.util.ServerCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ChatColors;

/**
 * killer560's item 8.1, Bazaar half - same black+amber chrome as {@link AuctionHouseScreen} (see its class
 * doc for why), and, since the 2026-09-27 redesign, the same two-pane/paginated layout too: killer560,
 * 2026-09-27: "for the custom bazaar I dont like this layout either can you set it up differently...
 * have the two feel like a consistent pair." A left category rail (from the shared item catalog's own
 * {@code category} field - see {@link BazaarProduct}) plus a paginated product list replace the old single
 * continuous scroll list, mirroring {@code AuctionHouseScreen} exactly. Clicking a product runs Hypixel's
 * own real {@code /bz <name>} - Hypixel's own menu handles the actual buy/sell order, never this mod.
 */
public final class BazaarScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
    private static final int RAIL_BG = 0xFF120C06;
    private static final int ROW_HOVER = 0xFF2A1A0A;
    private static final int ROW_BORDER = 0xFF262626;
    private static final int DIM = 0xFF9A8C80;
    private static final int VALUE = 0xFFFFA040;

    private static final int ROW_HEIGHT = 20;
    private static final int RAIL_ROW_HEIGHT = 16;
    private static final int RAIL_W = 100;

    private final Screen parent;
    private EditBox searchBox;
    private String lastQuery = "";
    private int page = 0;

    private String cachedQuery = null;
    private List<BazaarProduct> cachedSourceRef = null;
    private AuctionConfig.BazaarSortMode cachedSort = null;
    private String cachedCategory = null;
    private List<BazaarProduct> filtered = List.of();

    private int panelX, panelY, panelW, panelH;
    private int railX, railY, railW, railH;
    private int listX, listY, listW, listH;
    private int footerY;

    /** Same "click one, refresh every label" trick as {@code AuctionHouseScreen}'s rail - see its doc. */
    private final Map<String, SettingsButtonWidget> railButtons = new LinkedHashMap<>();

    private record Hotspot(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hotspot> hotspots = new ArrayList<>();
    private List<Component> pendingTooltip;

    public BazaarScreen(Screen parent) {
        super(Component.literal("Bazaar"));
        this.parent = parent;
        BazaarApi.ensureAutoStarted();
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 16, 560);
        panelH = this.height - 16;
        panelX = (this.width - panelW) / 2;
        panelY = 8;

        int y = panelY + 26;
        int refreshW = 70;
        int gap = 6;
        searchBox = new EditBox(this.font, panelX + 8, y, panelW - 16 - refreshW - gap, 16, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search products..."));
        searchBox.setValue(lastQuery);
        searchBox.setResponder(text -> {
            lastQuery = text;
            page = 0;
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(BazaarApi.isRefreshing() ? "Refreshing..." : "Refresh"),
                        btn -> BazaarApi.refreshAsync())
                .bounds(panelX + panelW - 8 - refreshW, y, refreshW, 16).build());
        y += 22;

        AuctionConfig cfg = AuctionConfig.getInstance();
        addRenderableWidget(SettingsButtonWidget.builder(sortLabel(cfg), btn -> {
                    cfg.setLastBazaarSort(cfg.getLastBazaarSort().next());
                    cfg.save();
                    btn.setMessage(sortLabel(cfg));
                }).secondaryPress(btn -> {
                    cfg.setLastBazaarSort(cfg.getLastBazaarSort().previous());
                    cfg.save();
                    btn.setMessage(sortLabel(cfg));
                }).bounds(panelX + 8, y, panelW - 16, 16).build());
        y += 22;

        int bodyY = y + 4;
        railX = panelX + 8;
        railY = bodyY;
        railW = RAIL_W;
        int footerH = 18;
        int bodyBottom = panelY + panelH - 8;
        footerY = bodyBottom - footerH;
        railH = Math.max(RAIL_ROW_HEIGHT, footerY - railY - 4);

        listX = railX + railW + gap;
        listY = bodyY;
        listW = panelW - 16 - railW - gap;
        listH = Math.max(ROW_HEIGHT, footerY - 4 - listY);

        buildCategoryRail(cfg);

        int pageBtnW = 60;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("◀ Prev"), btn -> {
                    if (page > 0) {
                        page--;
                    }
                }).bounds(listX, footerY, pageBtnW, footerH).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Next ▶"), btn -> {
                    int maxPage = Math.max(0, (filtered.size() - 1) / Math.max(1, visibleRowCount()));
                    if (page < maxPage) {
                        page++;
                    }
                }).bounds(listX + listW - pageBtnW, footerY, pageBtnW, footerH).build());
    }

    /** Same live-derived-from-data approach as {@code AuctionHouseScreen#buildCategoryRail} - here from the
     *  shared item catalog's {@code category} field on each already-fetched {@link BazaarProduct} rather
     *  than hardcoding Hypixel's taxonomy. */
    private void buildCategoryRail(AuctionConfig cfg) {
        railButtons.clear();
        TreeSet<String> categories = new TreeSet<>();
        for (BazaarProduct p : BazaarApi.getProducts()) {
            if (p.category() != null && !p.category().isBlank()) {
                categories.add(p.category());
            }
        }
        List<String> ordered = new ArrayList<>();
        ordered.add(""); // "All", always first
        ordered.addAll(categories);

        int maxRows = Math.max(1, railH / (RAIL_ROW_HEIGHT + 2));
        for (int i = 0; i < ordered.size() && i < maxRows; i++) {
            String value = ordered.get(i);
            String label = value.isEmpty() ? "All" : SkyblockItemStackFactory.niceCategory(value);
            SettingsButtonWidget btn = SettingsButtonWidget.builder(railLabel(label, value.equals(cfg.getLastBazaarCategoryFilter())),
                            b -> selectCategory(value))
                    .bounds(railX, railY + i * (RAIL_ROW_HEIGHT + 2), railW, RAIL_ROW_HEIGHT).build();
            railButtons.put(value, btn);
            addRenderableWidget(btn);
        }
    }

    private void selectCategory(String value) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setLastBazaarCategoryFilter(value);
        cfg.save();
        page = 0;
        for (Map.Entry<String, SettingsButtonWidget> entry : railButtons.entrySet()) {
            String label = entry.getKey().isEmpty() ? "All" : SkyblockItemStackFactory.niceCategory(entry.getKey());
            entry.getValue().setMessage(railLabel(label, entry.getKey().equals(value)));
        }
    }

    private static Component railLabel(String label, boolean selected) {
        return Component.literal((selected ? "§b▶ " : "§7") + label);
    }

    private static Component sortLabel(AuctionConfig cfg) {
        return Component.literal("Sort: §b" + cfg.getLastBazaarSort().label);
    }

    private int visibleRowCount() {
        return Math.max(1, listH / ROW_HEIGHT);
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(filtered.size() / (double) visibleRowCount()));
    }

    private void refilterIfNeeded() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        List<BazaarProduct> source = BazaarApi.getProducts();
        AuctionConfig.BazaarSortMode sort = cfg.getLastBazaarSort();
        String category = cfg.getLastBazaarCategoryFilter();
        if (source == cachedSourceRef && lastQuery.equals(cachedQuery) && sort == cachedSort
                && category.equals(cachedCategory)) {
            return;
        }
        cachedSourceRef = source;
        cachedQuery = lastQuery;
        cachedSort = sort;
        cachedCategory = category;
        String q = lastQuery.trim().toLowerCase(Locale.ROOT);
        List<BazaarProduct> out = new ArrayList<>();
        for (BazaarProduct p : source) {
            if (!q.isEmpty() && !p.displayName().toLowerCase(Locale.ROOT).contains(q) && !p.productId().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (!category.isEmpty() && !category.equalsIgnoreCase(p.category() == null ? "" : p.category())) {
                continue;
            }
            out.add(p);
        }
        out.sort(comparatorFor(sort));
        filtered = out;
        page = Math.min(page, totalPages() - 1);
    }

    private static Comparator<BazaarProduct> comparatorFor(AuctionConfig.BazaarSortMode mode) {
        return switch (mode) {
            case BUY_PRICE_HIGH -> Comparator.comparingDouble(BazaarProduct::buyPrice).reversed();
            case SELL_PRICE_HIGH -> Comparator.comparingDouble(BazaarProduct::sellPrice).reversed();
            case SPREAD_HIGH -> Comparator.comparingDouble(BazaarProduct::spread).reversed();
            case VOLUME_HIGH -> Comparator.comparingLong((BazaarProduct p) -> p.buyVolume() + p.sellVolume()).reversed();
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxPage = totalPages() - 1;
        page = Math.max(0, Math.min(maxPage, page - (int) Math.signum(scrollY)));
        return true;
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
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hotspots.clear();
        pendingTooltip = null;
        refilterIfNeeded();

        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, BORDER);
        g.text(this.font, "Bazaar", panelX + 8, panelY + 8, ACCENT, false);

        String status = statusLine();
        g.text(this.font, status, panelX + panelW - 8 - this.font.width(status), panelY + 8, DIM, false);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.fill(railX, railY, railX + railW, railY + railH, RAIL_BG);
        g.outline(railX, railY, railW, railH, BORDER);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF090909);
        g.outline(listX, listY, listW, listH, BORDER);

        if (filtered.isEmpty()) {
            String msg = BazaarApi.getProducts().isEmpty()
                    ? (BazaarApi.isRefreshing() ? "Fetching Bazaar prices..." : "No data yet - click Refresh.")
                    : "No products match your search.";
            g.text(this.font, msg, listX + 8, listY + 8, DIM, false);
        } else {
            int rows = visibleRowCount();
            int base = page * rows;
            BazaarProduct hovered = null;
            for (int i = 0; i < rows; i++) {
                int idx = base + i;
                if (idx >= filtered.size()) {
                    break;
                }
                int rowY = listY + i * ROW_HEIGHT;
                BazaarProduct product = filtered.get(idx);
                boolean isHover = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (isHover) {
                    hovered = product;
                }
                drawRow(g, product, listX, rowY, listW, isHover);
                hotspots.add(new Hotspot(listX, rowY, listW, ROW_HEIGHT, () -> orderProduct(product)));
            }
            if (hovered != null) {
                pendingTooltip = buildTooltip(hovered);
            }
        }

        String pageText = "Page " + (page + 1) + " / " + totalPages();
        g.text(this.font, pageText, listX + (listW - this.font.width(pageText)) / 2, footerY + 5, DIM, false);

        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.setTooltipForNextFrame(this.font, pendingTooltip, Optional.empty(), mouseX, mouseY);
        }
    }

    private String statusLine() {
        String base = filtered.size() + " product" + (filtered.size() == 1 ? "" : "s");
        if (BazaarApi.isRefreshing()) {
            return base + " · refreshing...";
        }
        long fetched = BazaarApi.getLastFetchedMs();
        if (fetched == 0) {
            return base;
        }
        long ageSec = (System.currentTimeMillis() - fetched) / 1000;
        return base + " · " + (ageSec < 60 ? ageSec + "s ago" : (ageSec / 60) + "m ago");
    }

    private void drawRow(GuiGraphicsExtractor g, BazaarProduct p, int x, int y, int w, boolean hovered) {
        if (hovered) {
            g.fill(x, y, x + w, y + ROW_HEIGHT, ROW_HOVER);
        }
        g.outline(x, y, w, ROW_HEIGHT, ROW_BORDER);
        g.item(p.icon(), x + 1, y + 1);
        int tx = x + 20;
        String buy = "B: " + DungeonChestValuer.formatCoins(Math.round(p.buyPrice()));
        String sell = "S: " + DungeonChestValuer.formatCoins(Math.round(p.sellPrice()));
        String right = buy + "  " + sell;
        int rightW = this.font.width(right);
        int nameColor = tierColorInt(p.tier());
        String name = this.font.plainSubstrByWidth(p.displayName(), w - 20 - rightW - 8);
        g.text(this.font, name, tx, y + 1, nameColor, false);
        g.text(this.font, right, x + w - 4 - rightW, y + 1, 0xFF000000 | VALUE, false);

        String sub = "Spread: " + DungeonChestValuer.formatCoins(Math.round(p.spread()))
                + "  Vol: " + shortNumber(p.buyVolume() + p.sellVolume()) + "/day";
        g.text(this.font, sub, tx, y + 11, 0xFF000000 | DIM, false);
    }

    private List<Component> buildTooltip(BazaarProduct p) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(p.displayName()));
        lines.add(Component.literal("§7Instant Buy: §6" + DungeonChestValuer.formatCoins(Math.round(p.buyPrice())) + " coins"));
        lines.add(Component.literal("§7Instant Sell: §6" + DungeonChestValuer.formatCoins(Math.round(p.sellPrice())) + " coins"));
        lines.add(Component.literal((p.spread() >= 0 ? "§a" : "§c") + "Spread: " + DungeonChestValuer.formatCoins(Math.round(p.spread())) + " coins"));
        lines.add(Component.literal("§7Buy Volume: §f" + shortNumber(p.buyVolume())));
        lines.add(Component.literal("§7Sell Volume: §f" + shortNumber(p.sellVolume())));
        lines.add(Component.literal("§8Click to /bz " + p.displayName()));
        return lines;
    }

    private void orderProduct(BazaarProduct p) {
        // "bz" is one of our own client commands, so sendCommand would have handed this back to us rather than to
        // Hypixel - the same recursion that crashed /ah and /fl. See ServerCommands.
        ServerCommands.toServer("bz " + p.displayName());
    }

    private static String shortNumber(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fB", n / 1e9);
        }
        if (n >= 1_000_000L) {
            return String.format(Locale.US, "%.2fM", n / 1e6);
        }
        if (n >= 1_000L) {
            return String.format(Locale.US, "%.1fk", n / 1e3);
        }
        return String.valueOf(n);
    }

    private static int tierColorInt(String tier) {
        String code = SkyblockItemStackFactory.tierColorCode(tier);
        char c = code.length() >= 2 ? code.charAt(1) : 'f';
        ChatFormatting cf = ChatFormatting.getByCode(c);
        Integer rgb = cf != null ? ChatColors.color(cf) : null;
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }
}
