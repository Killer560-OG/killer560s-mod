package com.killer560.hub.bazaarflip.autopilot;

/**
 * The one next thing the Bazaar Flipper's autopilot wants done ({@link FlipAutopilot#decide}). Each is executed by the
 * menu driver ({@code BazaarFlipAutopilotDriver}) through the Bazaar GUI, or by the simulator in {@code tools/bazaarflip-sim}.
 * Every action carries a short {@code why} for the log and the chat line.
 */
public sealed interface FlipAction {

    String why();

    /** Buy {@code amount} units straight from the sell offers. */
    record InstaBuy(String productId, String name, long amount, double expectedEach, String why) implements FlipAction {
    }

    /** Place a buy order for {@code amount} units at {@code price} each (best buy order + 0.1). */
    record PlaceBuyOrder(String productId, String name, double price, long amount, String why) implements FlipAction {
    }

    /** Place a sell offer for every unit carried at {@code price} each (cheapest sell offer - 0.1). Hypixel's sell offer
     *  takes the whole carried amount of the item; {@code amount} is what the bot expects it to list. */
    record PlaceSellOffer(String productId, String name, double price, long amount, String why) implements FlipAction {
    }

    /** Sell the carried units straight into the buy orders. */
    record InstaSell(String productId, String name, long amount, double expectedEach, String why) implements FlipAction {
    }

    /** Cancel an order that is outbid or expired and place it again at {@code newPrice} for what is left of it. */
    record Relist(FlipOrder order, double newPrice, String why) implements FlipAction {
    }

    /** Cancel an order that no longer pays at any price worth bidding, freeing its coins or items. */
    record Cancel(FlipOrder order, String why) implements FlipAction {
    }

    /** Claim the filled items (buy order) or coins (sell offer) of an order from Manage Orders. */
    record Collect(FlipOrder order, String why) implements FlipAction {
    }

    /** Sell carried units to the NPC through /trades. */
    record SellToNpc(String productId, String name, long amount, double npcEach, String why) implements FlipAction {
    }

    /** Nothing worth doing now; look again later. */
    record Wait(String why) implements FlipAction {
    }

    /** Stop the session (purse below the reserve with nothing to collect, order cap reached with nothing open). */
    record Stop(String why) implements FlipAction {
    }
}
