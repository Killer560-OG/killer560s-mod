package com.killer560.hub.lfg;

import java.util.regex.Pattern;

/**
 * STUB - dual-listing a Kuudra Party Finder queue (killer560, 2026-10-18: "if you que in dungeon or kuudra party finder it
 * should auto dual list there").
 * <p>
 * The dungeon side reads Hypixel's "Group Builder" and its "Party Finder > Your party has been queued in the dungeon
 * finder!" line (from his screenshots, {@code autokick.QueuedFloorDetector}). Neither the Kuudra Group Builder's title, the
 * item that names the tier, nor Kuudra's queued line is known, and none of them is guessed. Until his screenshots supply
 * them, {@link #TIER_LORE} and {@link #QUEUED} are null and {@link #onLine} does nothing; when they are known, read the tier
 * the same way the dungeon detector reads the floor (item found by NAME, colour codes stripped, anchored lines) and call
 * {@link LfgFeature#onHypixelKuudraQueued} with one of {@link LfgCategory#KUUDRA}'s activities.
 */
public final class KuudraQueueDetector {

    /** Unknown: the lore line naming the selected Kuudra tier in the Kuudra Group Builder. */
    static final Pattern TIER_LORE = null;
    /** Unknown: Hypixel's chat line when a Kuudra party is queued. */
    static final Pattern QUEUED = null;

    private KuudraQueueDetector() {
    }

    /** True once the patterns above are filled in. */
    public static boolean isImplemented() {
        return TIER_LORE != null && QUEUED != null;
    }

    /** One chat line (colour stripped). Does nothing until {@link #isImplemented()}. */
    public static void onLine(String plain) {
        if (!isImplemented()) {
            return;
        }
    }
}
