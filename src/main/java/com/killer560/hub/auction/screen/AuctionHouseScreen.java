package com.killer560.hub.auction.screen;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.AuctionHouseConfig;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.auction.ah.AhMarket;
import com.killer560.hub.auction.ah.AhNav;
import com.killer560.hub.auction.ah.AhReskin;
import com.killer560.hub.auction.ah.AhUi;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.util.ServerCommands;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The unified Auction House's browser: every live auction from Hypixel's public API ({@link AuctionHouseApi}, scanned off
 * the render thread), drawn by the mod in the same frame as Hypixel's real AH menus are reskinned in ({@link AhReskin},
 * shared look {@link AhUi}). Redone 2026-10-07 (killer560: "redo the auction house in a similar manner ... to make it
 * look really nice").
 * <p>
 * Category tabs on top (Hypixel's own: Weapons, Armor, Accessories, Consumables, Blocks, Tools &amp; Misc), a search
 * field and the filters (sort, rarity, BIN only, price range) beside it, recent searches and recently viewed items on
 * the left, and a grid of listing cards (real icons, rarity, price, BIN or bids, time left, the lowest-BIN marker) that
 * scrolls - only the visible cards are touched each frame. Clicking a card sends exactly Hypixel's own
 * {@code /viewauction <uuid>} (his click, one command), and Hypixel's Auction View then opens in the same frame through
 * the reskin, where Bid / Buy Item Right Now / Confirm are Hypixel's real buttons. This screen never buys or bids.
 * <p>
 * Every text, region and hotspot of a frame is recorded ({@link #layoutReport}) for the testkit's overlap checks.
 */
public final class AuctionHouseScreen extends Screen {

    /** Price presets of the Price filter: {min, max} in coins, 0 = open. */
    public static final long[][] PRICE_PRESETS = {{0, 0}, {0, 100_000}, {100_000, 1_000_000}, {1_000_000, 10_000_000},
            {10_000_000, 100_000_000}, {100_000_000, 0}};
    private static final String[] RARITIES = {"", "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE",
            "SPECIAL", "VERY_SPECIAL"};
    private static final Set<String> MAIN_CATEGORIES = Set.of("weapon", "armor", "accessories", "consumables", "blocks");
    private static final int CARD_H = 36;
    private static final int MIN_CARD_W = 150;

    private final Screen parent;
    private String query = "";
    private boolean searchFocused;
    private int scroll;
    private long typedAtMs;
    private boolean typedSinceCommit;

    private List<AuctionListing> cachedSource;
    private String cachedKey;
    private List<AuctionListing> filtered = List.of();

    private final List<AhUi.Hotspot> hotspots = new ArrayList<>();
    private final List<String> layout = new ArrayList<>();
    private AuctionListing opening;
    private long openingAtMs;

    public AuctionHouseScreen(Screen parent) {
        super(Component.literal("Auction House"));
        this.parent = parent;
        AuctionHouseApi.ensureAutoScanStarted();
    }

    /** Opens on a category (null keeps the last one), a search (null keeps none) and, if asked, the search focused. */
    public void preset(String category, String search, boolean focusSearch) {
        if (category != null) {
            AuctionConfig cfg = AuctionConfig.getInstance();
            cfg.setLastCategoryFilter(category);
            cfg.save();
        }
        if (search != null) {
            query = search;
        }
        searchFocused = focusSearch;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        commitSearch();
        McCompat.setScreen(this.minecraft, parent);
    }

    // ---- filtering ------------------------------------------------------------------------------------------------

    /** The browser's whole filter: category tab, search, rarity, BIN/auction, price range, then the sort. */
    public static List<AuctionListing> filter(List<AuctionListing> all, String category, String query, String rarity,
            AuctionHouseConfig.TypeFilter type, long minPrice, long maxPrice, AuctionConfig.SortMode sort) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String r = rarity == null ? "" : rarity.toUpperCase(Locale.ROOT);
        String cat = category == null ? "" : category;
        List<AuctionListing> out = new ArrayList<>();
        for (AuctionListing l : all) {
            String lc = l.category() == null ? "" : l.category().toLowerCase(Locale.ROOT);
            if (cat.equals("*misc") ? MAIN_CATEGORIES.contains(lc) : !cat.isEmpty() && !cat.equalsIgnoreCase(lc)) {
                continue;
            }
            if (type == AuctionHouseConfig.TypeFilter.BIN && !l.bin() || type == AuctionHouseConfig.TypeFilter.AUCTION && l.bin()) {
                continue;
            }
            if (!r.isEmpty() && !r.equals(l.tier() == null ? "" : l.tier().toUpperCase(Locale.ROOT))) {
                continue;
            }
            long price = l.currentPrice();
            if (minPrice > 0 && price < minPrice || maxPrice > 0 && price > maxPrice) {
                continue;
            }
            if (!q.isEmpty() && !matches(l, q)) {
                continue;
            }
            out.add(l);
        }
        out.sort(comparator(sort));
        return out;
    }

    private static boolean matches(AuctionListing l, String q) {
        if (l.itemName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (!l.skyblockId().isEmpty()) {
            if (l.skyblockId().toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)) {
                return true;
            }
            SkyblockItemEntry entry = SkyblockItemRepository.findById(l.skyblockId());
            String name = entry == null || entry.name() == null ? null : ChatFormatting.stripFormatting(entry.name());
            return name != null && name.toLowerCase(Locale.ROOT).contains(q);
        }
        return false;
    }

    private static Comparator<AuctionListing> comparator(AuctionConfig.SortMode mode) {
        return switch (mode) {
            case PRICE_LOW -> Comparator.comparingLong(AuctionListing::currentPrice);
            case PRICE_HIGH -> Comparator.comparingLong(AuctionListing::currentPrice).reversed();
            case ENDING_SOONEST -> Comparator.comparingLong(AuctionListing::end);
            case ULTIMATE_ENCHANT -> Comparator
                    .comparingInt((AuctionListing l) -> l.hasUltimateEnchant() ? 0 : 1)
                    .thenComparing(Comparator.comparingInt(AuctionListing::ultimateEnchantTier).reversed())
                    .thenComparing(Comparator.comparingLong(AuctionListing::currentPrice));
        };
    }

    private void refilterIfNeeded() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        AuctionHouseConfig ah = AuctionHouseConfig.getInstance();
        List<AuctionListing> source = AuctionHouseApi.getListings();
        String key = cfg.getLastCategoryFilter() + "|" + query + "|" + cfg.getLastRarityFilter() + "|" + ah.getTypeFilter()
                + "|" + ah.getMinPrice() + "|" + ah.getMaxPrice() + "|" + cfg.getLastSort();
        if (source == cachedSource && key.equals(cachedKey)) {
            return;
        }
        boolean sameView = key.equals(cachedKey);
        cachedSource = source;
        cachedKey = key;
        filtered = filter(source, cfg.getLastCategoryFilter(), query, cfg.getLastRarityFilter(), ah.getTypeFilter(),
                ah.getMinPrice(), ah.getMaxPrice(), cfg.getLastSort());
        if (!sameView) {
            scroll = 0;
        }
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        refilterIfNeeded();
        AhMarket.markInUse();
        if (typedSinceCommit && System.currentTimeMillis() - typedAtMs > 2500) {
            commitSearch();
        }
        hotspots.clear();
        layout.clear();
        AhUi ui = new AhUi(g, this.width, this.height, mouseX, mouseY, layout, hotspots);
        ui.frame();
        AuctionConfig cfg = AuctionConfig.getInstance();
        AuctionHouseConfig ah = AuctionHouseConfig.getInstance();

        List<AhUi.Tab> tabs = new ArrayList<>();
        for (String[] t : AhUi.API_TABS) {
            String cat = t[1];
            tabs.add(new AhUi.Tab(t[0], tabIcon(cat), cat.equals(cfg.getLastCategoryFilter()), -1, ItemStack.EMPTY,
                    b -> selectCategory(cat)));
        }
        boolean busy = opening != null && System.currentTimeMillis() - openingAtMs < AhNav.PENDING_MS
                || AuctionHouseApi.isScanning();
        ui.header(tabs, "Hypixel AH", b -> openHypixelAh(), "hypixel-ah", busy);

        List<AhUi.Button> buttons = new ArrayList<>();
        buttons.add(new AhUi.Button("Sort: " + sortShort(cfg.getLastSort()), sortShort(cfg.getLastSort()), null, false, -1,
                ItemStack.EMPTY, b -> cycleSort(b == 1), "sort"));
        String rarity = cfg.getLastRarityFilter();
        buttons.add(new AhUi.Button("Rarity: " + (rarity.isEmpty() ? "Any" : nice(rarity)),
                rarity.isEmpty() ? "Rarity" : nice(rarity), null, !rarity.isEmpty(), -1, ItemStack.EMPTY,
                b -> cycleRarity(b == 1), "rarity"));
        buttons.add(new AhUi.Button(ah.getTypeFilter().label, ah.getTypeFilter() == AuctionHouseConfig.TypeFilter.ALL ? "All"
                : ah.getTypeFilter() == AuctionHouseConfig.TypeFilter.BIN ? "BIN" : "Auct.", null,
                ah.getTypeFilter() != AuctionHouseConfig.TypeFilter.ALL, -1, ItemStack.EMPTY, b -> cycleType(b == 1), "type"));
        String price = priceLabel(ah.getMinPrice(), ah.getMaxPrice());
        buttons.add(new AhUi.Button("Price: " + price, price.equals("Any") ? "Price" : price, null, !price.equals("Any"), -1,
                ItemStack.EMPTY, b -> cyclePrice(b == 1), "price"));
        ui.toolbar(query, "Search the Auction House", searchFocused, b -> searchFocused = true, "search", -1,
                ItemStack.EMPTY, buttons, null);

        ui.recentsRail(q -> {
            query = q;
            searchFocused = false;
            AuctionConfig c = AuctionConfig.getInstance();
            c.setLastCategoryFilter("");
            c.save();
        }, v -> {
            query = viewedQuery(v);
            searchFocused = false;
        }, () -> {
            AuctionHouseConfig c = AuctionHouseConfig.getInstance();
            c.clearRecents();
            c.save();
        });

        drawGrid(ui);

        List<AhUi.Button> bar = new ArrayList<>();
        bar.add(new AhUi.Button("Create · Manage · Bids", "Hypixel AH", new ItemStack(Items.GOLD_BLOCK), false, -1,
                ItemStack.EMPTY, b -> openHypixelAh(), "manage"));
        List<AhUi.Button> right = new ArrayList<>();
        right.add(new AhUi.Button(AuctionHouseApi.isScanning() ? "Scanning..." : "Refresh", "Refresh", null, false, -1,
                ItemStack.EMPTY, b -> AuctionHouseApi.refreshAsync(), "refresh"));
        ui.bottomBar(bar, statusLine(), right);

        if (!ui.hoverStack.isEmpty()) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, ui.hoverStack));
            lines.addAll(ui.hoverExtra);
            g.setTooltipForNextFrame(this.font, lines, Optional.empty(), mouseX, mouseY);
        } else if (ui.hoverLines != null) {
            g.setTooltipForNextFrame(this.font, ui.hoverLines, Optional.empty(), mouseX, mouseY);
        }
    }

    private void drawGrid(AhUi ui) {
        int x = ui.contentX;
        int y = ui.bodyY;
        int cw = ui.contentW;
        int bottom = ui.bodyBottom;
        ui.region("content", x, y, cw, bottom - y);
        if (filtered.isEmpty()) {
            String msg = AuctionHouseApi.getListings().isEmpty()
                    ? (AuctionHouseApi.isScanning() ? "Scanning the Auction House..." : AuctionHouseApi.getLastScanError() != null
                    ? "Could not reach Hypixel's API - Refresh to try again." : "No auction data yet - Refresh to scan.")
                    : "No listings match. Try another tab or clear a filter.";
            ui.text("content", ui.fit(msg, cw - 16), x + 8, y + 8, ui.t.dim());
            return;
        }
        int[] grid = ui.gridColumns(cw - 6, MIN_CARD_W);
        int cols = grid[0], cardW = grid[1], gap = grid[2];
        int rows = (filtered.size() + cols - 1) / cols;
        int top = y + 3;
        int total = rows * (CARD_H + gap) - gap;
        int maxScroll = Math.max(0, total - (bottom - top - 3));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int firstRow = Math.max(0, scroll / (CARD_H + gap));
        int lastRow = Math.min(rows - 1, (scroll + bottom - top) / (CARD_H + gap));
        long now = System.currentTimeMillis();
        ui.beginClip(x, top, cw, bottom);
        try {
            for (int r = firstRow; r <= lastRow; r++) {
                for (int c = 0; c < cols; c++) {
                    int i = r * cols + c;
                    if (i >= filtered.size()) {
                        break;
                    }
                    AuctionListing l = filtered.get(i);
                    int cx = x + 3 + c * (cardW + gap);
                    int cy = top + r * (CARD_H + gap) - scroll;
                    drawCard(ui, l, cx, cy, cardW, now);
                }
            }
        } finally {
            ui.endClip();
        }
        if (maxScroll > 0) {
            int sx = x + cw - 3;
            int hgt = bottom - top;
            ui.g.fill(sx, top, sx + 2, bottom, ui.t.surfaceAlt());
            int thumb = Math.max(12, (int) ((long) hgt * hgt / Math.max(1, total)));
            int ty = top + (int) ((long) (hgt - thumb) * scroll / Math.max(1, maxScroll));
            ui.g.fill(sx, ty, sx + 2, ty + thumb, ui.t.accent());
        }
    }

    private void drawCard(AhUi ui, AuctionListing l, int cx, int cy, int cardW, long now) {
        long lowest = AhMarket.lowestBin(l.skyblockId());
        AhMarket.Stats market = AhMarket.stats(l.skyblockId());
        // Marked only where it means something: the cheapest of two or more BINs of the same item.
        boolean cheapest = l.bin() && lowest > 0 && l.startingBid() <= lowest && market != null && market.binCount() > 1;
        String price = l.bin() ? AhUi.coins(l.currentPrice()) + " coins"
                : (l.highestBid() > 0 ? "Top bid " : "Starting bid ") + AhUi.coins(l.currentPrice());
        String badge;
        int badgeColor;
        if (l.bin()) {
            badge = "BIN";
            badgeColor = ui.t.gold();
        } else {
            badge = l.bidCount() > 0 ? l.bidCount() + (l.bidCount() == 1 ? " bid" : " bids") : "Auction";
            badgeColor = ui.t.aqua();
        }
        List<String> sub = new ArrayList<>();
        if (cheapest) {
            sub.add("Lowest BIN");
        }
        if (l.isPet()) {
            sub.add("Lvl " + l.petLevel());
        }
        sub.add(AhUi.timeLeft(l.end() - now));
        List<Component> extra = new ArrayList<>();
        AhMarket.Stats st = AhMarket.stats(l.skyblockId());
        if (st != null && st.lowestBin() > 0) {
            extra.add(Component.literal("§8Lowest BIN: §6" + AhUi.coins(st.lowestBin()) + " §8(" + st.binCount() + " listed)"));
        }
        List<AhMarket.Point> sales = AhMarket.sales(l.skyblockId());
        if (!sales.isEmpty()) {
            long sum = 0;
            for (AhMarket.Point p : sales) {
                sum += p.price();
            }
            extra.add(Component.literal("§8Recent sales: §6" + AhUi.coins(sum / sales.size()) + " §8avg of " + sales.size()));
        }
        extra.add(Component.literal("§eClick to open on Hypixel"));
        Component name = l.icon().has(DataComponents.CUSTOM_NAME) ? l.icon().getHoverName()
                : Component.literal(SkyblockItemStackFactory.tierColorCode(l.tier()) + l.itemName());
        boolean isOpening = opening == l && System.currentTimeMillis() - openingAtMs < AhNav.PENDING_MS;
        ui.listingCard(cx, cy, cardW, CARD_H, l.icon(), name, isOpening ? "Opening..." : price, ui.t.gold(), badge,
                badgeColor, String.join(" · ", sub), AhUi.tierColor(l.tier(), ui.t.border()), cheapest, -1,
                b -> {
                    if (b == 0) {
                        open(l);
                    }
                }, "listing:" + l.uuid().toString().replace("-", ""), true, extra);
    }

    private String statusLine() {
        int n = filtered.size();
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%,d", n)).append(n == 1 ? " listing" : " listings");
        if (AuctionHouseApi.isScanning()) {
            int pct = AuctionHouseApi.getScanProgressPercent();
            sb.append(" · scanning").append(pct >= 0 ? " " + pct + "%" : "...");
        } else if (AuctionHouseApi.getLastScanFinishedMs() > 0) {
            long age = (System.currentTimeMillis() - AuctionHouseApi.getLastScanFinishedMs()) / 1000;
            sb.append(" · updated ").append(age < 60 ? age + "s" : age / 60 + "m").append(" ago");
        }
        return sb.toString();
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    private void open(AuctionListing l) {
        commitSearch();
        opening = l;
        openingAtMs = System.currentTimeMillis();
        AhNav.openListing(l);
    }

    /** Hypixel's own /ah (Create Auction, Manage Auctions and View Bids live in its menu, reskinned). */
    private void openHypixelAh() {
        commitSearch();
        ServerCommands.toServer("ah");
    }

    private void selectCategory(String cat) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setLastCategoryFilter(cat);
        cfg.save();
    }

    private void cycleSort(boolean back) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setLastSort(back ? cfg.getLastSort().previous() : cfg.getLastSort().next());
        cfg.save();
    }

    private void cycleRarity(boolean back) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        String cur = cfg.getLastRarityFilter();
        int idx = 0;
        for (int i = 0; i < RARITIES.length; i++) {
            if (RARITIES[i].equalsIgnoreCase(cur)) {
                idx = i;
            }
        }
        idx = (idx + (back ? RARITIES.length - 1 : 1)) % RARITIES.length;
        cfg.setLastRarityFilter(RARITIES[idx]);
        cfg.save();
    }

    private void cycleType(boolean back) {
        AuctionHouseConfig ah = AuctionHouseConfig.getInstance();
        ah.setTypeFilter(back ? ah.getTypeFilter().previous() : ah.getTypeFilter().next());
        ah.save();
    }

    private void cyclePrice(boolean back) {
        AuctionHouseConfig ah = AuctionHouseConfig.getInstance();
        int idx = 0;
        for (int i = 0; i < PRICE_PRESETS.length; i++) {
            if (PRICE_PRESETS[i][0] == ah.getMinPrice() && PRICE_PRESETS[i][1] == ah.getMaxPrice()) {
                idx = i;
            }
        }
        idx = (idx + (back ? PRICE_PRESETS.length - 1 : 1)) % PRICE_PRESETS.length;
        ah.setMinPrice(PRICE_PRESETS[idx][0]);
        ah.setMaxPrice(PRICE_PRESETS[idx][1]);
        ah.save();
    }

    private static String priceLabel(long min, long max) {
        if (min <= 0 && max <= 0) {
            return "Any";
        }
        if (min <= 0) {
            return "< " + AhUi.coins(max);
        }
        if (max <= 0) {
            return "> " + AhUi.coins(min);
        }
        return AhUi.coins(min) + "-" + AhUi.coins(max);
    }

    private static String sortShort(AuctionConfig.SortMode m) {
        return switch (m) {
            case PRICE_LOW -> "Lowest price";
            case PRICE_HIGH -> "Highest price";
            case ENDING_SOONEST -> "Ending soon";
            case ULTIMATE_ENCHANT -> "Ultimate enchant";
        };
    }

    private static String nice(String rarity) {
        return SkyblockItemStackFactory.niceCategory(rarity);
    }

    private static ItemStack tabIcon(String category) {
        return switch (category) {
            case "weapon" -> new ItemStack(Items.GOLDEN_SWORD);
            case "armor" -> new ItemStack(Items.DIAMOND_CHESTPLATE);
            case "accessories" -> new ItemStack(Items.EMERALD);
            case "consumables" -> new ItemStack(Items.APPLE);
            case "blocks" -> new ItemStack(Items.COBBLESTONE);
            case "*misc" -> new ItemStack(Items.STICK);
            default -> new ItemStack(Items.NETHER_STAR);
        };
    }

    /** What a recently viewed item searches for: its SkyBlock id as words (matches every listing of that item, whatever
     *  its reforge or stars), else its name. */
    public static String viewedQuery(AuctionHouseConfig.Viewed v) {
        return v.skyblockId().isEmpty() ? v.name() : v.skyblockId().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** A search he ran goes into the recents once he stops typing, presses Enter or acts on it. */
    private void commitSearch() {
        if (!typedSinceCommit) {
            return;
        }
        typedSinceCommit = false;
        if (!query.isBlank()) {
            AuctionHouseConfig cfg = AuctionHouseConfig.getInstance();
            cfg.addRecentSearch(query);
            cfg.save();
        }
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int button = event.button();
        if (button != 0 && button != 1) {
            return super.mouseClicked(event, doubleClick);
        }
        boolean hitSearch = false;
        for (AhUi.Hotspot h : hotspots) {
            if (h.contains(event.x(), event.y())) {
                if (h.action() != null) {
                    this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    hitSearch = "Search".equals(h.label());
                    h.action().accept(button);
                }
                if (!hitSearch) {
                    searchFocused = false;
                }
                return true;
            }
        }
        searchFocused = false;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * (CARD_H + 5));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (searchFocused) {
            if (key == InputConstants.KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                typedSinceCommit = true;
                commitSearch();
                searchFocused = false;
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                if (event.hasControlDown()) {
                    query = "";
                } else if (!query.isEmpty()) {
                    query = query.substring(0, query.length() - 1);
                }
                typed();
                return true;
            }
            if (key == InputConstants.KEY_V && event.hasControlDown()) {
                String clip = this.minecraft.keyboardHandler.getClipboard();
                if (clip != null) {
                    query = (query + clip.replaceAll("[\\r\\n\\t]", " ")).substring(0,
                            Math.min(64, query.length() + clip.length()));
                    typed();
                }
                return true;
            }
            return true; // a focused field swallows every other key (no inventory-key close while typing)
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String s = event.codepointAsString();
        if (s.isEmpty() || Character.isISOControl(s.charAt(0))) {
            return false;
        }
        // Typing anywhere starts a search: the field takes focus with the first character.
        searchFocused = true;
        if (query.length() < 64) {
            query += s;
            typed();
        }
        return true;
    }

    private void typed() {
        typedAtMs = System.currentTimeMillis();
        typedSinceCommit = true;
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** Every region, text and hotspot of the last frame ({@link AhUi}'s format). */
    public List<String> layoutReport() {
        return List.copyOf(layout);
    }

    public int shownCountForTest() {
        refilterIfNeeded();
        return filtered.size();
    }

    public void setQueryForTest(String q) {
        query = q == null ? "" : q;
        typed();
    }

    public void commitSearchForTest() {
        commitSearch();
    }

    public void scrollForTest(int px) {
        scroll = Math.max(0, px);
    }

    /** The current price of the first {@code n} listings shown, in order. */
    public long[] shownPricesForTest(int n) {
        refilterIfNeeded();
        long[] out = new long[Math.min(n, filtered.size())];
        for (int i = 0; i < out.length; i++) {
            out[i] = filtered.get(i).currentPrice();
        }
        return out;
    }

    public String queryForTest() {
        return query;
    }

    /** The uuid (32 hex) of the n-th listing shown, or "". */
    public String listingIdForTest(int n) {
        refilterIfNeeded();
        return n >= 0 && n < filtered.size() ? filtered.get(n).uuid().toString().replace("-", "") : "";
    }
}
