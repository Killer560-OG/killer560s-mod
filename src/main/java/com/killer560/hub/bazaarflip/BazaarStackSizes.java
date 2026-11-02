package com.killer560.hub.bazaarflip;

import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many of a Bazaar product fit in one inventory slot, and how many one order may hold (killer560, 2026-11-01: "it just
 * tried to buy a crap ton of kaleidoscopic minerals. I can only buy 256 at once. And they only single stack ... Try to
 * figure that out for all items").
 *
 * <p>The Bazaar Flipper sized purchases by the item's vanilla material, and Hypixel stacks by its own rules: Kaleidoscopic
 * Mineral ({@code SALMON_OPAL}) is PAPER, which stacks to 64 in vanilla, so it planned 254 for an inventory that holds one
 * per free slot (his Dungeons log, 2026-10-30 11:02, "Insta Buy 254 Kaleidoscopic Mineral" and "typed 254"). Sources, in
 * order:
 * <ol>
 *   <li>what a live Instant Buy page showed ({@link #learn}: "Fill my inventory!" is Hypixel's own count of the room);</li>
 *   <li>enchanted books ({@code ENCHANTMENT_*}) and reforge stones (category {@code REFORGE_STONE}, 107 Bazaar products on
 *       2026-11-01, Kaleidoscopic Mineral among them) never stack;</li>
 *   <li>Hypixel's item catalog: {@code unstackable} or {@code has_uuid} ({@link SkyblockItemRepository#isUnstackable});</li>
 *   <li>the material's vanilla stack size (16 for Ender Pearls), 64 when the item is not in the catalog.</li>
 * </ol>
 * The order limit is the wiki's (Bazaar, read 2026-11-01): "up to 71,680 of that item in a single offer, or 256 for
 * unstackable items"; an Instant Buy is limited by the inventory ("A player cannot instant buy more items than their
 * inventory can hold").
 */
public final class BazaarStackSizes {

    /** Units one Bazaar order may hold for an item that stacks. */
    public static final long ORDER_LIMIT = 71_680L;
    /** Units one Bazaar order may hold for an item that does not stack. */
    public static final long ORDER_LIMIT_UNSTACKABLE = 256L;

    /** Stack sizes the live pages showed this session, by product id. */
    private static final Map<String, Integer> LEARNED = new ConcurrentHashMap<>();

    private BazaarStackSizes() {
    }

    /** Units of {@code productId} one inventory slot holds. */
    public static int of(String productId) {
        if (productId == null || productId.isBlank()) {
            return 64;
        }
        Integer learned = LEARNED.get(productId);
        if (learned != null) {
            return learned;
        }
        if (productId.startsWith("ENCHANTMENT_")) {
            return 1;
        }
        SkyblockItemEntry entry = SkyblockItemRepository.findById(productId);
        if (entry == null) {
            return 64;
        }
        if ("REFORGE_STONE".equals(entry.category()) || SkyblockItemRepository.isUnstackable(productId)) {
            return 1;
        }
        return Math.max(1, SkyblockItemStackFactory.materialMaxStackSize(entry.material()));
    }

    /** The most units one Bazaar order of a product with this stack size may hold. */
    public static long orderLimit(int stackSize) {
        return stackSize <= 1 ? ORDER_LIMIT_UNSTACKABLE : ORDER_LIMIT;
    }

    /** The most units one Bazaar order of {@code productId} may hold. */
    public static long orderLimitOf(String productId) {
        return orderLimit(of(productId));
    }

    /**
     * A live Instant Buy page said "Fill my inventory!" buys {@code fillAmount} while the inventory had {@code emptySlots}
     * free slots. When the fill is no more than one per free slot, the product does not stack: remembered for the session.
     * Returns the stack size now believed.
     */
    public static int learn(String productId, long fillAmount, int emptySlots) {
        if (productId == null || productId.isBlank() || fillAmount < 1 || emptySlots < 1) {
            return of(productId);
        }
        int believed = of(productId);
        if (believed > 1 && fillAmount <= emptySlots) {
            LEARNED.put(productId, 1);
            return 1;
        }
        return believed;
    }

    /** Testkit: forget what live pages taught. */
    public static void clearLearnedForTest() {
        LEARNED.clear();
    }
}
