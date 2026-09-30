package com.killer560.hub.roomsim;

/**
 * One attack cadence for every sim weapon, from Hypixel's own formulas.
 *
 * <p>killer560 (2026-09-28): "For the attack speed make sure all items assume 100 attack speed equivalent on
 * hypicrl." So every weapon in the sim swings at 100 Attack Speed regardless of what his real gear would do -
 * fixed rather than modelled, because a rhythm that changed with his armour would be a different rhythm every
 * session and could not be compared across them, which is the whole point of a practice tool.
 *
 * <p><b>The numbers here are the wiki's, not mine.</b> Both constants in this class used to be guesses, and
 * both were wrong: melee was 4 ticks where the formula gives 5, and the Terminator was given 10 ticks, which
 * is the ZERO attack speed value rather than the 100 one. killer560 sent
 * <a href="https://hypixelskyblock.minecraft.wiki/w/Attack_Speed">the Attack Speed page</a> on 2026-09-30
 * precisely because "it can be different" per weapon class, and it is:
 *
 * <ul>
 *   <li><b>Melee:</b> invulnerability ticks = {@code round(10 / (1 + AS/100))}. At 0 AS that is 10 ticks
 *       (0.50s); at 100 AS it is 5.</li>
 *   <li><b>Shortbow:</b> shot cooldown = {@code ceil(10 / (1 + AS/100))} - note CEIL, not round. At 0 AS that
 *       is 10 ticks (2.00 shots/second); at 100 AS it is 5 (4.00 shots/second).</li>
 *   <li><b>Spirit Shortbow:</b> a longer base, {@code ceil(15 / (1 + AS/100))}.</li>
 * </ul>
 *
 * <p>They are written as formulas rather than the answers so that changing {@link #ATTACK_SPEED} stays
 * correct, and so the rounding difference between melee and bows - which is the easy thing to lose when
 * copying numbers across - is in the code rather than in a comment.
 *
 * <p>Vanilla's own cooldown is deliberately not used: it varies per item, and the sim's weapons are stand-ins
 * whose vanilla base item was chosen for how it looks, not how it swings.
 */
public final class SimAttackSpeed {

    /** The Attack Speed every sim weapon assumes. Hypixel's normal cap is 100. */
    public static final int ATTACK_SPEED = 100;

    /** Ticks between melee swings: {@code round(10 / (1 + AS/100))}. Five at 100 Attack Speed. */
    public static final int BASE_ATTACK_INTERVAL_TICKS =
            (int) Math.round(10.0 / (1.0 + ATTACK_SPEED / 100.0));

    /**
     * Ticks between shortbow shots: {@code ceil(10 / (1 + AS/100))}. Five at 100 Attack Speed, i.e. 4.00
     * shots a second.
     *
     * <p>The wiki notes that holding right-click caps at 4.00 shots a second in vanilla anyway unless the use
     * key is bound to the keyboard, so at this Attack Speed the two happen to agree.
     */
    public static final int SHORTBOW_INTERVAL_TICKS =
            (int) Math.ceil(10.0 / (1.0 + ATTACK_SPEED / 100.0));

    /** Ticks between Spirit Shortbow shots: {@code ceil(15 / (1 + AS/100))}. Eight at 100 Attack Speed. */
    public static final int SPIRIT_SHORTBOW_INTERVAL_TICKS =
            (int) Math.ceil(15.0 / (1.0 + ATTACK_SPEED / 100.0));

    private SimAttackSpeed() {
    }

    /** Whether enough ticks have passed since {@code lastUseTick} for another melee swing. */
    public static boolean ready(int nowTick, int lastUseTick) {
        return nowTick - lastUseTick >= BASE_ATTACK_INTERVAL_TICKS;
    }

    /** Whether enough ticks have passed since {@code lastShotTick} for another shortbow shot. */
    public static boolean shortbowReady(int nowTick, int lastShotTick) {
        return nowTick - lastShotTick >= SHORTBOW_INTERVAL_TICKS;
    }
}
