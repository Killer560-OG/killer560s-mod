package com.killer560.hub.auction.screen;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * killer560's item 8.1, Bazaar half - same black+amber chrome and virtualized-list approach as
 * {@link AuctionHouseScreen} (see its class doc for why). Clicking a product runs Hypixel's own real
 * {@code /bz <name>} - Hypixel's own menu handles the actual buy/sell order, never this mod.
 */
public final class BazaarScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
    private static final int ROW_HOVER = 0xFF2A1A0A;
    private static final int ROW_BORDER = 0xFF262626;
    private static final int DIM = 0xFF9A8C80;
    private static final int VALUE = 0xFFFFA040;

    private static final int ROW_HEIGHT = 20;

    private final Screen parent;
    private EditBox searchBox;
    private String lastQuery = "";

    private String cachedQuery = null;
    private List<BazaarProduct> cachedSourceRef = null;
    private AuctionConfig.BazaarSortMode cachedSort = null;
    private List<BazaarProduct> filtered = List.of();

    private int scrollOffset = 0;
    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;

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
        panelW = Math.min(this.width - 16, 460);
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
            scrollOffset = 0;
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
                }).bounds(panelX + 8, y, panelW - 16, 16).build());
        y += 22;

        listX = panelX + 8;
        listY = y + 12;
        listW = panelW - 16;
        listH = Math.max(ROW_HEIGHT, panelY + panelH - 8 - listY);
    }

    private static Component sortLabel(AuctionConfig cfg) {
        return Component.literal("Sort: §b" + cfg.getLastBazaarSort().label);
    }

    private int visibleRowCount() {
        return Math.max(1, listH / ROW_HEIGHT);
    }

    private void refilterIfNeeded() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        List<BazaarProduct> source = BazaarApi.getProducts();
        AuctionConfig.BazaarSortMode sort = cfg.getLastBazaarSort();
        if (source == cachedSourceRef && lastQuery.equals(cachedQuery) && sort == cachedSort) {
            return;
        }
        cachedSourceRef = source;
        cachedQuery = lastQuery;
        cachedSort = sort;
        String q = lastQuery.trim().toLowerCase(Locale.ROOT);
        List<BazaarProduct> out = new ArrayList<>();
        for (BazaarProduct p : source) {
            if (q.isEmpty() || p.displayName().toLowerCase(Locale.ROOT).contains(q) || p.productId().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(p);
            }
        }
        out.sort(comparatorFor(sort));
        filtered = out;
        int maxOffset = Math.max(0, filtered.size() - visibleRowCount());
        scrollOffset = Math.min(scrollOffset, maxOffset);
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
        int maxOffset = Math.max(0, filtered.size() - visibleRowCount());
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(scrollY) * 3));
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
        this.minecraft.setScreen(parent);
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

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF090909);
        g.outline(listX, listY, listW, listH, BORDER);

        if (filtered.isEmpty()) {
            String msg = BazaarApi.getProducts().isEmpty()
                    ? (BazaarApi.isRefreshing() ? "Fetching Bazaar prices..." : "No data yet - click Refresh.")
                    : "No products match your search.";
            g.text(this.font, msg, listX + 8, listY + 8, DIM, false);
        } else {
            int rows = visibleRowCount();
            BazaarProduct hovered = null;
            for (int i = 0; i < rows; i++) {
                int idx = scrollOffset + i;
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
        String name = this.font.plainSubstrByWidth(p.displayName(), w - 20 - rightW - 8);
        g.text(this.font, name, tx, y + 1, 0xFFFFFFFF, false);
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("bz " + p.displayName());
        }
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
}
