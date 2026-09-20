package com.killer560.hub.ap3;

/**
 * Formerly the clicking half of {@code /ap3 edit db} (right-click blocks into a BREAKER node). The BREAKER node was
 * removed on 2026-09-20 - killer560: "Remove the BREAKER node (Breaker Aura covers it)" - so there is nothing left
 * to click. {@link #register()} stays because {@code Killer560ModClient#onInitializeClient} calls it; it now does
 * nothing, and no {@code UseBlockCallback} is registered any more (edit mode used to swallow every block right-click).
 */
public final class Ap3EditInput {

    private Ap3EditInput() {
    }

    /** Kept for the registration call in {@code Killer560ModClient}; intentionally a no-op. */
    public static void register() {
    }

    /** Kept for callers that reset the old edit mode; intentionally a no-op. */
    public static void reset() {
    }
}
