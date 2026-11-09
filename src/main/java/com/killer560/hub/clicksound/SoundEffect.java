package com.killer560.hub.clicksound;

import net.minecraft.resources.Identifier;

/**
 * The game sounds the Sound Effects tab can replace (killer560, 2026-10-18). Each is matched by the sound event the game
 * plays: vanilla ids seen in the repo already ({@code entity.arrow.hit_player} in SecretSound, {@code entity.player.attack.*}
 * and {@code block.*.break} are the names Minecraft's own sound events use). A match is a SoundEvent id, so a resource pack
 * that renames them changes what it covers, which is the intended behavior.
 */
public enum SoundEffect {
    ARROW_HIT("arrow_hit", "Arrow Hit", "an arrow hitting a mob or a player"),
    ATTACK_HIT("attack_hit", "Attack Hit", "every left click that hits a mob (Minecraft's attack sound)"),
    BLOCK_HIT("block_hit", "Block Hit", "every left click on a block (the hit sound while you mine it)"),
    MOB_DEATH("mob_death", "Mob Death", "a mob dying (not a player)"),
    BLOCK_BREAK("block_break", "Block Break", "a block breaking"),
    ARROW_SHOOT("arrow_shoot", "Arrow Shot", "an arrow leaving a bow or crossbow"),
    XP_ORB("xp_orb", "XP Orb", "picking up an experience orb"),
    ITEM_PICKUP("item_pickup", "Item Pickup", "picking up an item"),
    LEVEL_UP("level_up", "Level Up", "the level-up chime"),
    ANVIL("anvil", "Anvil", "using or landing an anvil"),
    ENCHANT_TABLE("enchant_table", "Enchant Table", "the enchanting table's enchant sound"),
    CHEST("chest", "Chest", "a chest or ender chest opening or closing"),
    DOOR("door", "Door", "a door or trapdoor opening or closing"),
    EXPLOSION("explosion", "Explosion", "an explosion"),
    TELEPORT("teleport", "Teleport", "an ender pearl throw or an Enderman teleporting"),
    NOTE_BLOCK("note_block", "Note Block", "any note block sound"),
    VILLAGER("villager", "Villager", "a villager or NPC talking, trading or saying no"),
    WITHER("wither", "Wither", "a Wither spawning, shooting or getting hurt"),
    EAT_DRINK("eat_drink", "Eat And Drink", "eating food or drinking"),
    SPLASH_POTION("splash_potion", "Splash Potion", "a splash potion being thrown or breaking"),
    TOTEM("totem", "Totem", "a totem of undying popping"),
    FISHING("fishing", "Fishing", "a fishing bobber splashing or a rod reeling in");

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
            case ARROW_SHOOT -> p.equals("entity.arrow.shoot") || p.equals("item.crossbow.shoot");
            case XP_ORB -> p.equals("entity.experience_orb.pickup");
            case ITEM_PICKUP -> p.equals("entity.item.pickup");
            case LEVEL_UP -> p.equals("entity.player.levelup");
            case ANVIL -> p.startsWith("block.anvil.");
            case ENCHANT_TABLE -> p.equals("block.enchantment_table.use");
            case CHEST -> p.startsWith("block.chest.") || p.startsWith("block.ender_chest.");
            case DOOR -> p.startsWith("block.") && p.contains("door.") && (p.endsWith(".open") || p.endsWith(".close"));
            case EXPLOSION -> p.equals("entity.generic.explode");
            case TELEPORT -> p.equals("entity.ender_pearl.throw") || p.equals("entity.enderman.teleport");
            case NOTE_BLOCK -> p.startsWith("block.note_block.");
            case VILLAGER -> p.startsWith("entity.villager.");
            case WITHER -> p.startsWith("entity.wither.");
            case EAT_DRINK -> p.equals("entity.generic.eat") || p.equals("entity.generic.drink");
            case SPLASH_POTION -> p.startsWith("entity.splash_potion.");
            case TOTEM -> p.equals("item.totem.use");
            case FISHING -> p.startsWith("entity.fishing_bobber.");
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
