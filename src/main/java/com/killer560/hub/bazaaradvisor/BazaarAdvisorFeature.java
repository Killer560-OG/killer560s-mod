package com.killer560.hub.bazaaradvisor;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bazaar Advisor: when a Bazaar menu opens, prints what to buy and how much (legit, read-only).
 * <p>
 * Buy price is the cheapest sell offer (what an instant buy costs). Bazaar pick sells into the best buy order after the
 * Bazaar's 1.25% sell tax, ranked by profit per unit times units sold a week. NPC pick sells to the NPC at its listed
 * price, ranked by profit per unit. Quantity is what the purse covers, capped by free inventory room at 64 a stack.
 */
public final class BazaarAdvisorFeature {

    private static final String CHAT = "Bazaar Advisor";
    /** Bazaar sell tax taken from the amount a sell offer fills at. */
    private static final double SELL_TAX = 0.0125;
    /** Assumed stack size for the inventory-room cap; the advisor line says "up to", so an approximation is fine. */
    private static final int STACK = 64;

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
            advise(client, screen.getMenu());
        } catch (RuntimeException e) {
            // A bad read must never stop the game's tick; the advisor just says nothing for this menu.
            advisedScreen = null;
        }
    }

    private static void advise(Minecraft client, AbstractContainerMenu menu) {
        List<BazaarProduct> products = BazaarApi.getProducts();
        if (products == null || products.isEmpty()) {
            ModChat.send(CHAT, ModChat.dim("No Bazaar prices loaded yet - open the menu again in a moment."));
            return;
        }
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        Double purse = BazaarFlipFeature.readPurse();
        if (cfg.isBazaarMode()) {
            Pick best = bazaarPick(products);
            report(client, menu, "Bazaar", best, purse);
        }
        if (cfg.isNpcMode()) {
            Pick best = npcPick(products);
            report(client, menu, "NPC", best, purse);
        }
    }

    /** The product to buy and sell into buy orders, ranked by profit per unit times units sold a week. */
    static Pick bazaarPick(List<BazaarProduct> products) {
        Pick best = null;
        for (BazaarProduct p : products) {
            double buy = p.topSellOfferPrice();
            double sell = p.topBuyOrderPrice();
            if (buy <= 0 || sell <= 0) {
                continue;
            }
            double profit = sell * (1 - SELL_TAX) - buy;
            if (profit <= 0 || p.sellMovingWeek() <= 0) {
                continue;
            }
            double score = profit * p.sellMovingWeek();
            if (best == null || score > best.score()) {
                best = new Pick(p.productId(), p.displayName(), buy, sell, profit, score, p.sellMovingWeek());
            }
        }
        return best;
    }

    /** The product whose cheapest sell offer leaves the most profit when the NPC buys it. */
    static Pick npcPick(List<BazaarProduct> products) {
        Map<String, BazaarProduct> byId = new HashMap<>();
        for (BazaarProduct p : products) {
            byId.put(p.productId(), p);
        }
        Pick best = null;
        for (SkyblockItemEntry entry : SkyblockItemRepository.getItems()) {
            Double npc = entry.npcSellPrice();
            BazaarProduct p = byId.get(entry.id());
            if (npc == null || npc <= 0 || p == null) {
                continue;
            }
            double buy = p.topSellOfferPrice();
            if (buy <= 0) {
                continue;
            }
            double profit = npc - buy;
            if (profit <= 0) {
                continue;
            }
            if (best == null || profit > best.score()) {
                best = new Pick(p.productId(), p.displayName(), buy, npc, profit, profit, 0);
            }
        }
        return best;
    }

    private static void report(Minecraft client, AbstractContainerMenu menu, String label, Pick pick, Double purse) {
        if (pick == null) {
            ModChat.send(CHAT, ModChat.dim(label + ": nothing is profitable right now."));
            return;
        }
        int room = Math.max(0, BazaarFlipMenus.roomFor(menu, pick.productId(), pick.name(), STACK));
        long byPurse = purse == null ? Long.MAX_VALUE : (long) Math.floor(purse / pick.buy());
        long units = Math.min(room, byPurse);
        String how = units <= 0 ? "no room or purse for one" : String.format(Locale.US, "%,d", units)
                + (purse == null ? " (purse unknown, room only)" : "");
        String sold = pick.soldPerWeek() > 0 ? String.format(Locale.US, ", %,d sold a week", pick.soldPerWeek()) : "";
        ModChat.send(CHAT, ModChat.text(label + ": buy "), ModChat.value(how),
                ModChat.text(" " + pick.name() + " at ~" + String.format(Locale.US, "%,.1f", pick.buy()) + " each, sell at ~"
                        + String.format(Locale.US, "%,.1f", pick.sell()) + " for +" + String.format(Locale.US, "%,.1f", pick.profit())
                        + " each" + sold));
    }

    /** One recommendation: what to buy, its buy and sell price per unit, the profit per unit, the ranking score and volume. */
    record Pick(String productId, String name, double buy, double sell, double profit, double score, long soldPerWeek) {
    }
}
