package com.killer560.hub.auction.screen;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.AuctionHouseFeature;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * killer560's item 8.1: the custom Auction House browser. Black+amber chrome (same palette as
 * {@code ProfileViewerScreen}/{@code ModScreen}). Clicking a listing never buys anything itself - it runs
 * Hypixel's own real {@code /viewauction <uuid>} so Hypixel's own menu handles the transaction.
 * <p>
 * Rendering is virtualized (per the brief: "no per-frame allocation in the list render... draw only
 * visible rows") - {@link #visibleRowCount} rows are computed from the panel height and only that many
 * entries of the already-filtered/sorted list are ever touched per frame, the same way
 * {@code ItemBrowserFeature} only draws the grid cells that fit on screen. The filtered/sorted list itself
 * is rebuilt only when the query, filters, sort mode or the underlying scan data actually changed (see
 * {@link #refilterIfNeeded}), not every frame, mirroring {@code ItemBrowserFeature#filteredItemsCached}.
 */
public final class AuctionHouseScreen extends Screen {

    private static final int ACCENT = 0xFFCC6600;
    private static final int BORDER = 0xFF553311;
    private static final int PANEL_BG = 0xFF0D0D0D;
    private static final int ROW_HOVER = 0xFF2A1A0A;
    private static final int ROW_BORDER = 0xFF262626;
    private static final int TEXT = 0xFFF0E6DC;
    private static final int DIM = 0xFF9A8C80;
    private static final int VALUE = 0xFFFFA040;
    private static final int BAD = 0xFFFF5555;

    private static final int ROW_HEIGHT = 20;
    private static final String[] RARITIES = {"", "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL"};
    private static final int[] PET_LEVEL_STEPS = {0, 1, 25, 50, 75, 100};

    private final Screen parent;
    private EditBox searchBox;
    private String lastQuery = "";

    private String cachedQuery = null;
    private List<AuctionListing> cachedSourceRef = null;
    private AuctionConfig.SortMode cachedSort = null;
    private String cachedRarity = null;
    private int cachedMinPetLevel = -1;
    private List<AuctionListing> filtered = List.of();

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

    public AuctionHouseScreen(Screen parent) {
        super(Component.literal("Auction House"));
        this.parent = parent;
        AuctionHouseApi.ensureAutoScanStarted();
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 16, 480);
        panelH = this.height - 16;
        panelX = (this.width - panelW) / 2;
        panelY = 8;

        int y = panelY + 26;
        int gap = 6;

        int refreshW = 70;
        searchBox = new EditBox(this.font, panelX + 8, y, panelW - 16 - refreshW - gap, 16, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search items (e.g. \"hyp\")..."));
        searchBox.setValue(lastQuery);
        searchBox.setResponder(text -> {
            lastQuery = text;
            scrollOffset = 0;
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(AuctionHouseApi.isScanning() ? "Scanning..." : "Refresh"),
                        btn -> AuctionHouseApi.refreshAsync())
                .bounds(panelX + panelW - 8 - refreshW, y, refreshW, 16).build());
        y += 22;

        AuctionConfig cfg = AuctionConfig.getInstance();
        int colW = (panelW - 16 - gap * 2) / 3;
        addRenderableWidget(SettingsButtonWidget.builder(sortLabel(cfg), btn -> {
                    cfg.setLastSort(cfg.getLastSort().next());
                    cfg.save();
                    btn.setMessage(sortLabel(cfg));
                }).bounds(panelX + 8, y, colW, 16).build());
        addRenderableWidget(SettingsButtonWidget.builder(rarityLabel(cfg), btn -> {
                    cfg.setLastRarityFilter(nextRarity(cfg.getLastRarityFilter()));
                    cfg.save();
                    btn.setMessage(rarityLabel(cfg));
                    scrollOffset = 0;
                }).bounds(panelX + 8 + colW + gap, y, colW, 16).build());
        addRenderableWidget(SettingsButtonWidget.builder(petLevelLabel(cfg), btn -> {
                    cfg.setLastMinPetLevel(nextPetLevelStep(cfg.getLastMinPetLevel()));
                    cfg.save();
                    btn.setMessage(petLevelLabel(cfg));
                    scrollOffset = 0;
                }).bounds(panelX + 8 + (colW + gap) * 2, y, colW, 16).build());
        y += 22;

        listX = panelX + 8;
        listY = y + 12;
        listW = panelW - 16;
        listH = Math.max(ROW_HEIGHT, panelY + panelH - 8 - listY);
    }

    private static String nextRarity(String current) {
        for (int i = 0; i < RARITIES.length; i++) {
            if (RARITIES[i].equalsIgnoreCase(current)) {
                return RARITIES[(i + 1) % RARITIES.length];
            }
        }
        return RARITIES[0];
    }

    private static int nextPetLevelStep(int current) {
        for (int i = 0; i < PET_LEVEL_STEPS.length; i++) {
            if (PET_LEVEL_STEPS[i] == current) {
                return PET_LEVEL_STEPS[(i + 1) % PET_LEVEL_STEPS.length];
            }
        }
        return PET_LEVEL_STEPS[0];
    }

    private static Component sortLabel(AuctionConfig cfg) {
        return Component.literal("Sort: §b" + cfg.getLastSort().label);
    }

    private static Component rarityLabel(AuctionConfig cfg) {
        String r = cfg.getLastRarityFilter();
        return Component.literal("Rarity: §b" + (r.isEmpty() ? "Any" : r));
    }

    private static Component petLevelLabel(AuctionConfig cfg) {
        int lvl = cfg.getLastMinPetLevel();
        return Component.literal("Min Pet Lvl: §b" + (lvl <= 0 ? "Any" : lvl));
    }

    private int visibleRowCount() {
        return Math.max(1, listH / ROW_HEIGHT);
    }

    /** Only re-filters/sorts the whole list when something that would change the result actually changed
     *  since last frame - see the class doc. */
    private void refilterIfNeeded() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        List<AuctionListing> source = AuctionHouseApi.getListings();
        AuctionConfig.SortMode sort = cfg.getLastSort();
        String rarity = cfg.getLastRarityFilter();
        int minPetLevel = cfg.getLastMinPetLevel();
        if (source == cachedSourceRef && lastQuery.equals(cachedQuery) && sort == cachedSort
                && rarity.equals(cachedRarity) && minPetLevel == cachedMinPetLevel) {
            return;
        }
        cachedSourceRef = source;
        cachedQuery = lastQuery;
        cachedSort = sort;
        cachedRarity = rarity;
        cachedMinPetLevel = minPetLevel;
        filtered = AuctionHouseFeature.filterAndSort(source, lastQuery, rarity, minPetLevel, sort);
        int maxOffset = Math.max(0, filtered.size() - visibleRowCount());
        scrollOffset = Math.min(scrollOffset, maxOffset);
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
        g.text(this.font, "Auction House", panelX + 8, panelY + 8, ACCENT, false);

        String status = statusLine();
        g.text(this.font, status, panelX + panelW - 8 - this.font.width(status), panelY + 8, DIM, false);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF090909);
        g.outline(listX, listY, listW, listH, BORDER);

        if (filtered.isEmpty()) {
            String msg = AuctionHouseApi.getListings().isEmpty()
                    ? (AuctionHouseApi.isScanning() ? "Scanning the Auction House..." : "No data yet - click Refresh.")
                    : "No listings match your search/filters.";
            g.text(this.font, msg, listX + 8, listY + 8, DIM, false);
        } else {
            int rows = visibleRowCount();
            AuctionListing hovered = null;
            for (int i = 0; i < rows; i++) {
                int idx = scrollOffset + i;
                if (idx >= filtered.size()) {
                    break;
                }
                int rowY = listY + i * ROW_HEIGHT;
                AuctionListing listing = filtered.get(idx);
                boolean isHover = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (isHover) {
                    hovered = listing;
                }
                drawRow(g, listing, listX, rowY, listW, isHover);
                hotspots.add(new Hotspot(listX, rowY, listW, ROW_HEIGHT, () -> openAuction(listing)));
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
        int count = filtered.size();
        String base = count + " listing" + (count == 1 ? "" : "s");
        if (AuctionHouseApi.isScanning()) {
            int pct = AuctionHouseApi.getScanProgressPercent();
            return base + " · scanning" + (pct >= 0 ? " " + pct + "%" : "...");
        }
        long finished = AuctionHouseApi.getLastScanFinishedMs();
        if (finished == 0) {
            return base;
        }
        long ageSec = (System.currentTimeMillis() - finished) / 1000;
        String age = ageSec < 60 ? ageSec + "s ago" : (ageSec / 60) + "m ago";
        return base + " · scanned " + age;
    }

    private void drawRow(GuiGraphicsExtractor g, AuctionListing l, int x, int y, int w, boolean hovered) {
        if (hovered) {
            g.fill(x, y, x + w, y + ROW_HEIGHT, ROW_HOVER);
        }
        g.outline(x, y, w, ROW_HEIGHT, ROW_BORDER);
        g.item(l.icon(), x + 1, y + 1);
        int tx = x + 20;
        String price = DungeonChestValuer.formatCoins(l.startingBid()) + " coins";
        int priceW = this.font.width(price);
        int nameColor = tierColorInt(l.tier());
        String name = this.font.plainSubstrByWidth(l.itemName(), w - 20 - priceW - 8);
        g.text(this.font, name, tx, y + 1, nameColor, false);
        g.text(this.font, price, x + w - 4 - priceW, y + 1, 0xFF000000 | VALUE, false);

        StringBuilder sub = new StringBuilder();
        if (l.isPet()) {
            sub.append("§7Lvl ").append(l.petLevel()).append("  ");
        }
        if (l.hasUltimateEnchant()) {
            sub.append("§d").append(l.ultimateEnchantName()).append(' ').append(roman(l.ultimateEnchantTier())).append("  ");
        }
        sub.append("§8").append(endsIn(l.end()));
        g.text(this.font, sub.toString(), tx, y + 11, 0xFF000000 | DIM, false);
    }

    private List<Component> buildTooltip(AuctionListing l) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(l.itemName()));
        if (l.tier() != null || l.category() != null) {
            lines.add(Component.literal("§7" + (l.tier() != null ? SkyblockItemStackFactory.niceCategory(l.tier()) : "")
                    + (l.tier() != null && l.category() != null ? " - " : "")
                    + (l.category() != null ? SkyblockItemStackFactory.niceCategory(l.category()) : "")));
        }
        for (String line : l.lore()) {
            if (!line.isBlank()) {
                lines.add(Component.literal("§7" + line));
            }
        }
        lines.add(Component.literal("§6" + DungeonChestValuer.formatCoins(l.startingBid()) + " coins"));
        lines.add(Component.literal("§8" + endsIn(l.end())));
        lines.add(Component.literal("§8Click to /viewauction"));
        return lines;
    }

    private void openAuction(AuctionListing l) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("viewauction " + l.uuid());
        }
    }

    private static String endsIn(long endMs) {
        long remain = endMs - System.currentTimeMillis();
        if (remain <= 0) {
            return "Ending now";
        }
        long s = remain / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return h + "h " + m + "m left";
        }
        if (m > 0) {
            return m + "m " + sec + "s left";
        }
        return sec + "s left";
    }

    private static String roman(int n) {
        if (n <= 0) {
            return "";
        }
        String[] vals = {"X", "IX", "V", "IV", "I"};
        int[] nums = {10, 9, 5, 4, 1};
        StringBuilder sb = new StringBuilder();
        int remaining = Math.min(n, 30);
        for (int i = 0; i < nums.length; i++) {
            while (remaining >= nums[i]) {
                sb.append(vals[i]);
                remaining -= nums[i];
            }
        }
        return sb.toString();
    }

    private static int tierColorInt(String tier) {
        String code = SkyblockItemStackFactory.tierColorCode(tier);
        char c = code.length() >= 2 ? code.charAt(1) : 'f';
        ChatFormatting cf = ChatFormatting.getByCode(c);
        Integer rgb = cf != null ? cf.getColor() : null;
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }
}
