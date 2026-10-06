package com.killer560.hub.roomsim;

/**
 * The few Hypixel numbers SkyBlock 0.27.2 (2026-10-06) changed that the dungeon sim emulates, old and new side by
 * side so the sim can play either. {@link #POST_0_27_2} picks; it is true because the patch notes are official
 * ("Reduced the amount of time it takes to pick up a secret from 11 -> 5 ticks", "Increased the activation range of
 * Fels by 5 blocks").
 *
 * <p>The sim builds no F7 terminals or Simon Says, so the 3-row Melody / 10-number / 4-round changes have nothing to
 * mimic here (Termism builds its boards from {@code terminals.TerminalLayouts}).
 */
public final class SimHypixelRules {

    /** Play SkyBlock 0.27.2's numbers (true) or the ones before it (false). */
    public static final boolean POST_0_27_2 = true;

    /** Ticks before a dropped secret item can be picked up: 11 before 0.27.2, 5 after. */
    public static final int SECRET_PICKUP_TICKS = POST_0_27_2 ? 5 : 11;

    /** How close a player must come for a Fel to wake: the sim's 4 blocks, +5 since 0.27.2. */
    public static final double FEL_WAKE_RADIUS = POST_0_27_2 ? 9.0 : 4.0;

    private SimHypixelRules() {
    }
}
