package com.killer560.hub.rngmeter;

/**
 * One RNG-meter-eligible drop. {@code group} is the floor (F1-F7/M1-M7) or area (slayer tier,
 * "Crystal Nucleus", "Superpairs", corpse type) it belongs to. {@code pityXp} is the amount of
 * meter "fuel" (dungeon party score / slayer XP / nucleus XP / enchanting XP / corpse XP,
 * depending on category) needed to guarantee the drop.
 */
public record RngItem(String group, String name, String id, RngSource source, long pityXp, boolean soulbound) {
}
