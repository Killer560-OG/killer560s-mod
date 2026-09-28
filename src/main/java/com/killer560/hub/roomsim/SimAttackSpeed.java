package com.killer560.hub.roomsim;

/**
 * One attack cadence for every sim weapon, fixed at Hypixel's baseline.
 *
 * <p>killer560 (2026-09-28): "For the attack speed make sure all items assume 100 attack speed equivalent on
 * hypicrl." 100 Attack Speed is Hypixel's own baseline - the rate before any bonus attack speed - so every
 * weapon in the sim swings at it regardless of what the real item would do in his hands.
 *
 * <p><b>Why fixed rather than modelled.</b> On Hypixel the rate depends on his gear, so a sim that guessed at
 * it would teach a rhythm that is wrong for everyone including him the day he changes a piece. Fixed means the
 * rhythm practised in here is the same rhythm every time and can be compared across sessions, which is the
 * whole point of a practice tool.
 *
 * <p>Vanilla's own cooldown is deliberately not used: it varies per item (a sword and an axe differ), and the
 * sim's weapons are stand-ins whose vanilla base item was chosen for how it looks, not how it swings.
 */
public final class SimAttackSpeed {

    /**
     * Ticks between swings at 100 Attack Speed.
     *
     * <p>Four ticks - five swings a second. Named here rather than repeated in each weapon so "all items
     * assume 100" is one number in one place, and changing it changes every weapon together.
     */
    public static final int BASE_ATTACK_INTERVAL_TICKS = 4;

    private SimAttackSpeed() {
    }

    /** Whether enough ticks have passed since {@code lastUseTick} for another swing. */
    public static boolean ready(int nowTick, int lastUseTick) {
        return nowTick - lastUseTick >= BASE_ATTACK_INTERVAL_TICKS;
    }
}
