package com.killer560.hub.bazaaradvisor;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.bazaarflip.BazaarFlipNpcLimit;
import com.killer560.hub.bazaarflip.autopilot.FlipPricing;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;
import java.util.Locale;

/**
 * Bazaar Advisor (killer560, 2026-10-19: "use all the info they give to recommend the right item ... make sure to account
 * for things like amount sold ... Make this highly advanced"). Works from the answers {@code /bzflip} stored in
 * {@link BazaarAdvisorConfig} and prints one recommendation in chat every time a Bazaar menu opens.
 * <p>
 * For each product: <b>buy</b> is the cheapest sell offer (Insta Buy) or the best buy order plus 0.1 (Buy Order, a bid that
 * outbids the current best). <b>sell</b> is the NPC price, or on the Bazaar the best buy order less the 1.25% tax (Insta Sell)
 * or the cheapest sell offer less 0.1 and the same tax (Sell Offer). <b>How many</b>: an Insta Buy is capped by the free
 * inventory room, a Buy Order only by the purse (as high as possible); both are capped by the purse, and NPC sales by the
 * daily NPC limit. <b>How much sells</b>: the Bazaar's weekly volume on the side you sell into, divided by seven, is what
 * sells in a day, so the expected profit counts only the units that can be sold within one day - a big stack of a product
 * nobody buys ranks low. The ranking is that expected profit (Total profit per inventory) or the profit percentage (Best
 * profit percentage).
 */
public final class BazaarAdvisorFeature {

    private static final String CHAT = "Bazaar Advisor";

    /** The screen the advice was last given for: one line per menu open, not one per tick. */
    private static AbstractContainerScreen<?> advisedScreen;

    private BazaarAdvisorFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BazaarAdvisorFeature::tick);
    }

    private static void tick(Minecraft client) {
        try {
            if (!BazaarAdvisorConfig.getInstance().isEnabled() || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
                return;
            }
            if (screen == advisedScreen || !BazaarFlipMenus.titleOf(screen).startsWith("Bazaar")) {
                return;
            }
            advisedScreen = screen;
            advise(screen.getMenu());
        } catch (RuntimeException e) {
            // A bad read must never stop the game's tick; the advisor just says nothing for this menu.
            advisedScreen = null;
        }
    }

    /** Prints the current recommendation for the open Bazaar menu. Also used by the setup screen's summary. */
    public static void advise(AbstractContainerMenu menu) {
        List<BazaarProduct> products = BazaarApi.getProducts();
        if (products == null || products.isEmpty()) {
            ModChat.send(CHAT, ModChat.dim("No Bazaar prices loaded yet - open the menu again in a moment."));
            return;
        }
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        Double purse = BazaarFlipFeature.readPurse();
        Recommendation r = recommend(products, cfg, menu, purse);
        if (r == null) {
            ModChat.send(CHAT, ModChat.text("Nothing is worth buying with your settings right now ("),
                    ModChat.dim(cfg.summary() + ")."));
            return;
        }
        ModChat.send(CHAT, ModChat.text("Buy "), ModChat.value(String.format(Locale.US, "%,d", r.units()) + " " + r.name()),
                ModChat.text(" - " + r.buyLabel() + " at ~" + money(r.buy()) + " each, " + r.sellLabel() + " at ~" + money(r.sell())
                        + " for +" + money(r.profitEach()) + " each (" + String.format(Locale.US, "%.1f", r.profitPercent()) + "%)."));
        ModChat.send(CHAT, ModChat.dim("Spend ~" + money(r.spend()) + ". Expected profit ~" + money(r.expectedProfit())
                + " from ~" + String.format(Locale.US, "%,d", r.expectedSold()) + " sold in a day. " + r.capNote()
                + " Settings: " + cfg.summary() + "."));
    }

    /**
     * The best product for these answers, or null when nothing is profitable under them. Pure apart from the inventory room
     * read from {@code menu}, so the setup summary and the menu hook share it.
     */
    public static Recommendation recommend(List<BazaarProduct> products, BazaarAdvisorConfig cfg, AbstractContainerMenu menu, Double purse) {
        // The model itself is FlipPricing (pure, shared with the Bazaar Flipper's autopilot); this only supplies the
        // Minecraft-side lookups.
        FlipPricing.Pick p = FlipPricing.best(products, answers(cfg), lookups(menu), purse, null);
        if (p == null) {
            return null;
        }
        return new Recommendation(p.productId(), p.name(), p.units(), p.buy(), p.sell(), p.profitEach(), p.profitPercent(),
                p.expectedProfit(), p.expectedSold(), p.buyLabel(), p.sellLabel(), p.capNote());
    }

    /** The stored /bzflip answers as the pricing model's flags. */
    public static FlipPricing.Answers answers(BazaarAdvisorConfig cfg) {
        return new FlipPricing.Answers(cfg.getMode() == BazaarAdvisorConfig.Mode.NPC,
                cfg.getGoal() == BazaarAdvisorConfig.Goal.PROFIT_PERCENT,
                cfg.getBuyMethod() == BazaarAdvisorConfig.BuyMethod.INSTA_BUY,
                cfg.getSellMethod() == BazaarAdvisorConfig.SellMethod.INSTA_SELL);
    }

    /** NPC price and stack size from the item repository, inventory room from {@code menu} (no cap when null), the
     *  NPC room from {@link BazaarFlipNpcLimit}. */
    public static FlipPricing.Lookups lookups(AbstractContainerMenu menu) {
        return new FlipPricing.Lookups() {
            @Override
            public Double npcPrice(String productId) {
                SkyblockItemEntry entry = SkyblockItemRepository.findById(productId);
                return entry == null ? null : entry.npcSellPrice();
            }

            @Override
            public int stackSize(String productId) {
                SkyblockItemEntry entry = SkyblockItemRepository.findById(productId);
                return entry == null ? 64 : Math.max(1, SkyblockItemStackFactory.materialMaxStackSize(entry.material()));
            }

            @Override
            public long room(String productId, String displayName, int stackSize) {
                return menu == null ? -1 : Math.max(0, BazaarFlipMenus.roomFor(menu, productId, displayName, stackSize));
            }

            @Override
            public double npcRemaining() {
                return BazaarFlipNpcLimit.remaining();
            }
        };
    }

    private static String money(double coins) {
        return String.format(Locale.US, "%,.1f", coins);
    }

    /** One recommendation: what to buy, how many, both prices, the profit, and what the sales can absorb in a day. */
    public record Recommendation(String productId, String name, long units, double buy, double sell, double profitEach,
                                 double profitPercent, double expectedProfit, long expectedSold, String buyLabel,
                                 String sellLabel, String capNote) {

        public double spend() {
            return units * buy;
        }
    }
}
