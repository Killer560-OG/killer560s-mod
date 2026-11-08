package com.killer560.hub.bazaar;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.auction.BazaarOrders;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.auction.ah.AhPages;
import com.killer560.hub.croesus.DungeonChestValuer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bazaar Order Helper (killer560, 2026-11-02: "add in skyhanni's bazaar order helper as well"). In Hypixel's Manage
 * Orders menu ({@code Your Bazaar Orders} / {@code Co-op Bazaar Orders}) each order's slot is colored: green when it is
 * filled (100%) or has something to claim, red when another player has a better order on its side (a buy order below
 * the best buy order, a sell offer above the cheapest sell offer). Its tooltip says which, against what price, and how
 * old that price is.
 * <p>
 * SkyHanni's {@code BazaarOrderHelper.kt} (behavior only, read 2026-11-02): full orders green, a buy order under the
 * instant-sell price or a sell offer over the instant-buy price gold, nothing else. Here the orders are read by the
 * mod's own {@link BazaarOrderParser} (the same one {@link BazaarOrders} and the Bazaar screen use), the judgment is
 * {@link BazaarOrders#standing} against the mod's own Bazaar API data ({@link BazaarApi}), and an order that only has
 * something to claim is green too. With no price data, or prices older than {@link #MAX_PRICE_AGE_MS}, only the filled
 * state shows.
 * <p>
 * Draw-only: nothing here clicks or sends anything. The reskinned Bazaar screen shows the same states as a tinted row
 * ({@link #rowColor}).
 */
public final class BazaarOrderHelper {

    /** Same titles {@link BazaarOrders} and {@link BazaarPages} read. */
    static final Pattern ORDERS_TITLE = Pattern.compile("^(?:Your|Co-op) Bazaar Orders$");
    /** Prices older than this are not used to call an order outbid (the API is rescanned every few minutes). */
    public static final long MAX_PRICE_AGE_MS = 15L * 60_000L;

    public enum State { NONE, FILLED, OUTBID, EXPIRED }

    private static AbstractContainerScreen<?> cachedScreen;
    private static boolean cachedIsOrders;
    private static boolean cachedIsAuctions;
    private static final Map<ItemStack, Integer> AH_STATE = new IdentityHashMap<>();
    /** Hypixel's Auction House lore: "Status: Sold!" / "Ended!" (claimable), "Status: Expired!". */
    private static final Pattern AH_STATUS = Pattern.compile("^Status: (Sold|Ended|Expired)!?$");
    private static final Map<ItemStack, BazaarOrderParser.Order> PARSED = new IdentityHashMap<>();
    private static final BazaarOrderParser.Order NOT_AN_ORDER = new BazaarOrderParser.Order(BazaarOrderParser.Type.BUY,
            "", null, 0, 0, false, 0, -1, -1, false, false, null, null);

    private BazaarOrderHelper() {
    }

    /** The live product for an order, or null when there is none or it is too old to judge by. */
    static BazaarProduct live(BazaarOrderParser.Order o) {
        if (!pricesFresh()) {
            return null;
        }
        BazaarProduct p = o.productId() != null ? BazaarFormat.byId(o.productId()) : null;
        return p != null ? p : BazaarFormat.byName(o.productName());
    }

    static boolean pricesFresh() {
        long at = BazaarApi.getProductsUpdatedMs();
        return at > 0 && !BazaarApi.peekProducts().isEmpty() && System.currentTimeMillis() - at <= MAX_PRICE_AGE_MS;
    }

    /** What the order's slot shows, settings ignored. */
    public static State classify(BazaarOrderParser.Order o) {
        if (o == null) {
            return State.NONE;
        }
        if (o.full()) {
            return State.FILLED;
        }
        if (o.expired()) {
            return State.EXPIRED; // red (killer560, 2026-11-03), even with something to claim
        }
        if (BazaarOrders.standing(o, live(o)) == BazaarOrders.Standing.OUTBID) {
            return State.OUTBID;
        }
        return o.claimable() ? State.FILLED : State.NONE;
    }

    /** The ARGB the settings give this order, or 0 for none (feature off, that highlight off, or no state). */
    public static int color(BazaarOrderParser.Order o) {
        BazaarOrderHelperConfig cfg = BazaarOrderHelperConfig.getInstance();
        if (!cfg.isEnabled()) {
            return 0;
        }
        return switch (classify(o)) {
            case FILLED -> cfg.isHighlightFilled() ? cfg.getFilledColor() : 0;
            case OUTBID -> cfg.isHighlightOutbid() ? cfg.getOutbidColor() : 0;
            case EXPIRED -> cfg.isHighlightExpired() ? cfg.getExpiredColor() : 0;
            case NONE -> 0;
        };
    }

    /** The reskinned Bazaar screen's order rows: the same color as the slot would get. */
    static int rowColor(BazaarOrderParser.Order o) {
        return color(o);
    }

    private static boolean isOrdersMenu(AbstractContainerScreen<?> screen) {
        if (screen != cachedScreen) {
            cachedScreen = screen;
            PARSED.clear();
            AH_STATE.clear();
            AhPages.Kind kind = AhPages.kindOf(BazaarOrderParser.strip(screen.getTitle().getString()));
            cachedIsAuctions = kind == AhPages.Kind.MANAGE || kind == AhPages.Kind.BIDS;
            cachedIsOrders = ORDERS_TITLE.matcher(BazaarOrderParser.strip(screen.getTitle().getString())).matches();
            if (cachedIsOrders) {
                BazaarApi.ensureAutoStarted(); // prices for the outbid check even with the Bazaar Browser off
            }
        }
        return cachedIsOrders;
    }

    /** The ARGB for an Auction House item in Manage Auctions / Your Bids: claimable green, expired red, else 0. */
    private static int auctionColor(AbstractContainerScreen<?> screen, Slot slot) {
        BazaarOrderHelperConfig cfg = BazaarOrderHelperConfig.getInstance();
        if (slot == null || slot.container instanceof Inventory || !cfg.isHighlightAuctions()) {
            return 0;
        }
        isOrdersMenu(screen);
        ItemStack stack = slot.getItem();
        if (!cachedIsAuctions || stack == null || stack.isEmpty()) {
            return 0;
        }
        if (AH_STATE.size() > 256) {
            AH_STATE.clear();
        }
        int state = AH_STATE.computeIfAbsent(stack, s -> {
            for (String line : DungeonChestValuer.cleanLore(s)) {
                java.util.regex.Matcher m = AH_STATUS.matcher(line);
                if (m.matches()) {
                    return m.group(1).equals("Expired") ? 2 : 1;
                }
            }
            return 0;
        });
        return state == 1 ? cfg.getFilledColor() : state == 2 ? cfg.getExpiredColor() : 0;
    }

    private static BazaarOrderParser.Order orderIn(AbstractContainerScreen<?> screen, Slot slot) {
        if (slot == null || slot.container instanceof Inventory || !isOrdersMenu(screen)) {
            return null;
        }
        ItemStack stack = slot.getItem();
        return stack == null || stack.isEmpty() ? null : orderOf(stack);
    }

    private static BazaarOrderParser.Order orderOf(ItemStack stack) {
        if (PARSED.size() > 256) {
            PARSED.clear();
        }
        BazaarOrderParser.Order o = PARSED.computeIfAbsent(stack, s -> {
            BazaarOrderParser.Order parsed = BazaarOrderParser.parse(s.getHoverName().getString(),
                    DungeonChestValuer.cleanLore(s));
            return parsed == null ? NOT_AN_ORDER : parsed;
        });
        return o == NOT_AN_ORDER ? null : o;
    }

    /** From the slot mixin, behind the item: a fill in the color's own alpha and an opaque 1 px outline. */
    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        try {
            if (!BazaarOrderHelperConfig.getInstance().isEnabled()) {
                return;
            }
            int argb = color(orderIn(screen, slot));
            if (argb == 0) {
                argb = auctionColor(screen, slot);
            }
            if (argb == 0) {
                return;
            }
            int x = slot.x;
            int y = slot.y;
            graphics.fill(x, y, x + 16, y + 16, argb);
            int edge = 0xFF000000 | argb;
            graphics.fill(x, y, x + 16, y + 1, edge);
            graphics.fill(x, y + 15, x + 16, y + 16, edge);
            graphics.fill(x, y + 1, x + 1, y + 15, edge);
            graphics.fill(x + 15, y + 1, x + 16, y + 15, edge);
        } catch (RuntimeException ignored) {
            // visual only
        }
    }

    /** From the tooltip mixin: one or two lines under an order's tooltip, or null to leave it alone. */
    public static List<Component> appendTooltip(AbstractContainerScreen<?> screen, ItemStack stack, List<Component> lines) {
        if (lines == null || stack == null || stack.isEmpty() || !BazaarOrderHelperConfig.getInstance().isEnabled()
                || !isOrdersMenu(screen)) {
            return null;
        }
        BazaarOrderParser.Order o = orderOf(stack);
        if (o == null) {
            return null;
        }
        List<Component> out = new ArrayList<>(lines);
        out.add(Component.empty());
        for (String[] l : tooltipLines(o)) {
            out.add(Component.literal(l[0]).withStyle(ChatFormatting.valueOf(l[1])));
        }
        return out;
    }

    /** The helper's tooltip lines as {text, ChatFormatting name}; public for the testkit. */
    public static List<String[]> tooltipLines(BazaarOrderParser.Order o) {
        List<String[]> out = new ArrayList<>();
        boolean buy = o.type() == BazaarOrderParser.Type.BUY;
        BazaarProduct live = live(o);
        String head = "Order Helper: ";
        if (o.full()) {
            out.add(new String[]{head + "Filled - ready to claim", "GREEN"});
        } else if (o.expired()) {
            out.add(new String[]{head + "Expired", "RED"});
        } else {
            switch (BazaarOrders.standing(o, live)) {
                case OUTBID -> out.add(new String[]{head + (buy ? "Outbid by a buy order at " : "Undercut by a sell offer at ")
                        + BazaarFormat.price(buy ? live.topBuyOrderPrice() : live.topSellOfferPrice()), "YELLOW"});
                case TOP, MATCHED -> out.add(new String[]{head + (buy ? "Best buy order" : "Best sell offer"), "GOLD"});
                case UNKNOWN -> out.add(new String[]{head + "No Bazaar price to compare", "GRAY"});
            }
            if (o.claimable()) {
                out.add(new String[]{"Something to claim", "GREEN"});
            }
        }
        long at = BazaarApi.getProductsUpdatedMs();
        if (at <= 0 || BazaarApi.peekProducts().isEmpty()) {
            out.add(new String[]{"Bazaar prices: not loaded yet", "DARK_GRAY"});
        } else {
            out.add(new String[]{"Bazaar prices from " + BazaarFormat.ago(at)
                    + (pricesFresh() ? "" : " (too old to judge)"), "DARK_GRAY"});
        }
        return out;
    }
}
