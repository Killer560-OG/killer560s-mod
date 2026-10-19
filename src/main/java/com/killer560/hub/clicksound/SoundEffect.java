package com.killer560.hub.clicksound;

import net.minecraft.resources.Identifier;

/**
 * The game sounds the Sound Effects tab can replace (killer560, 2026-10-18). Each is matched by the sound event the game
 * plays: vanilla ids seen in the repo already ({@code entity.arrow.hit_player} in SecretSound, {@code entity.player.attack.*}
 * and {@code block.*.break} are the names Minecraft's own sound events use). A match is a SoundEvent id, so a resource pack
 * that renames them changes what it covers, which is the intended behaviour.
 */
public enum SoundEffect {
    ARROW_HIT("arrow_hit", "Arrow Hit", "an arrow hitting a mob or a player"),
    ATTACK_HIT("attack_hit", "Attack Hit", "every left click that hits a mob (Minecraft's attack sound)"),
    BLOCK_HIT("block_hit", "Block Hit", "every left click on a block (the hit sound while you mine it)"),
    MOB_DEATH("mob_death", "Mob Death", "a mob dying (not a player)"),
    BLOCK_BREAK("block_break", "Block Break", "a block breaking");

    /** The key the config file uses. */
    public final String key;
    /** The row's name in the tab. */
    public final String label;
    /** What it covers, for the tooltip. */
    public final String covers;

    SoundEffect(String key, String label, String covers) {
        this.key = key;
        this.label = label;
        this.covers = covers;
    }

    /** Whether the game's sound event {@code id} is this effect. Only Minecraft's own sounds are matched. */
    public boolean matches(Identifier id) {
        if (!"minecraft".equals(id.getNamespace())) {
            return false;
        }
        String p = id.getPath();
        return switch (this) {
            case ARROW_HIT -> p.equals("entity.arrow.hit") || p.equals("entity.arrow.hit_player");
            case ATTACK_HIT -> p.startsWith("entity.player.attack.");
            case BLOCK_HIT -> p.startsWith("block.") && p.endsWith(".hit");
            case MOB_DEATH -> p.startsWith("entity.") && p.endsWith(".death") && !p.startsWith("entity.player.");
            case BLOCK_BREAK -> p.startsWith("block.") && p.endsWith(".break");
        };
    }

    /** Cached: {@link #values()} copies the array, and {@link #of} runs for every sound the game plays. */
    private static final SoundEffect[] ALL = values();

    /** The effect a played sound is, or null when it is none of them. */
    public static SoundEffect of(Identifier id) {
        for (SoundEffect e : ALL) {
            if (e.matches(id)) {
                return e;
            }
        }
        return null;
    }
}
