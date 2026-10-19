package com.killer560.hub.bazaarflip.autopilot;

/**
 * One of the player's open Bazaar orders as the autopilot sees it: what Manage Orders showed (through
 * {@code BazaarOrderParser}), plus what the driver itself remembers about it (when it was placed, how often relisted).
 * Pure, so the decision core and the simulator share it.
 *
 * @param buy            true for a buy order, false for a sell offer
 * @param filled         units filled so far (approximate below a full fill: Hypixel abbreviates "1.2k")
 * @param claimableItems items waiting to be claimed, or -1 when the menu did not say
 * @param claimableCoins coins waiting to be claimed, or -1 when the menu did not say
 * @param claimable      the menu said "Click to claim!" (or a claim count above was positive)
 * @param ageMs          how long ago the bot placed or last relisted it, or -1 when unknown (placed by hand / last session)
 * @param relists        how many times this session relisted it
 * @param ours           placed by this session (the bot only relists or cancels its own orders)
 */
public record FlipOrder(boolean buy, String productId, String name, long amount, long filled, double price,
                        long claimableItems, double claimableCoins, boolean claimable, boolean expired, long ageMs,
                        int relists, boolean ours) {

    public long unfilled() {
        return Math.max(0, amount - filled);
    }

    /** Coins this order holds on the Bazaar: a buy order's unfilled part was paid for when it was placed. */
    public double escrowCoins() {
        return buy ? unfilled() * price : 0;
    }

    /** The same identity {@code BazaarOrderParser.Order.key()} gives the order, so the two can be matched. */
    public String key() {
        return (buy ? "BUY|" : "SELL|") + name.toLowerCase(java.util.Locale.ROOT) + "|" + price + "|" + amount;
    }
}
