package com.killer560.hub.ragaxe;

/**
 * The built-in "rag now" prompts. Each one names a moment in a boss fight where you want the Ragnarock
 * strength buff to already be up, and the fixed offset from its trigger to that moment.
 * <p>
 * Timing model (see {@link RagAxeState} for the ability's own numbers): the Ragnarock Axe channels for
 * <b>3 s</b> before the buff applies (hypixelskyblock.minecraft.wiki/w/Ragnarock - "Begin a channel. After
 * not taking damage for 3s, gain 1.5x this weapon's Strength for 10s", 500 mana, 20 s cooldown). So a prompt
 * with the default lead of 3000 ms fires exactly one channel before {@link #expectedOffsetMs} has elapsed,
 * i.e. the buff lands on the moment. Raising the lead prompts earlier (useful if you also need to walk/aim
 * first, or if the offset estimate below is a little short on your setup); a lead at or above the offset
 * prompts the instant the trigger line lands.
 * <p>
 * Offsets and their confidence:
 * <ul>
 * <li>{@link #M7_DRAGONS} 3000 ms - reproduces NoammAddons' own M7 alert exactly (its
 * {@code features/impl/dungeon/Ragnarock.kt} shows "rag" the moment the Wither King line lands, and the
 * channel is 3 s), local copy C:\Users\Hunter\noammaddonsmod.
 * <li>{@link #M7_DRAGON_SPAWN} 0 - not line-timed at all: it reads the live per-dragon spawn countdown out
 * of {@code com.killer560.hub.witherdragons} (Odin's 100-server-tick particle-burst countdown) and prompts
 * {@code lead} ms before that dragon actually spawns. Tick-accurate, no estimate involved.
 * <li>{@link #M7_NECRON} 8000 ms - ESTIMATE. Necron's entry line fires while he is still descending into the
 * arena; no reference mod times this, so 8 s is this mod's own figure for "line -&gt; Necron is down and
 * hittable". Tune with the lead slider.
 * <li>{@link #M7_STORM} 5000 ms - ESTIMATE, same reasoning for Storm's entry line.
 * <li>{@link #F5_LIVID} 19500 ms - 390 server ticks, the Livid invulnerability window this mod already
 * counts down in {@code com.killer560.hub.boss.LividSolverFeature} (armed on the exact same entry line). So
 * the default prompt lands one channel before Livid can first be damaged, which is the natural rag point on
 * F5/M5 - there is no dedicated "rag here" callout on that floor in Odin, NoammAddons, Skytils or SkyHanni.
 * </ul>
 */
public enum RagAxePrompt {

    /** M7 P5: "[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you." */
    M7_DRAGONS("M7 Wither King Line", "m7_dragons", 3_000),
    /** M7 P5: each dragon's own spawn countdown (needs the Wither Dragons feature on - see {@link RagAxePrompts}). */
    M7_DRAGON_SPAWN("M7 Dragon Spawns", "m7_dragon_spawn", 0),
    /** F7/M7 P4: "[BOSS] Necron: You went further than any human before, congratulations." */
    M7_NECRON("M7 Necron Drop-down", "m7_necron", 8_000),
    /** F7/M7 P2: "[BOSS] Storm: Pathetic Maxor, just like expected." */
    M7_STORM("M7 Storm (P2)", "m7_storm", 5_000),
    /** F5/M5: "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows." */
    F5_LIVID("F5/M5 Livid", "f5_livid", 19_500);

    public final String label;
    /** Config-key fragment, e.g. {@code ragPrompt_m7_storm_enabled}. */
    public final String key;
    /** ms from the trigger until the moment the buff should already be up (0 = event-timed). */
    public final int expectedOffsetMs;

    RagAxePrompt(String label, String key, int expectedOffsetMs) {
        this.label = label;
        this.key = key;
        this.expectedOffsetMs = expectedOffsetMs;
    }
}
