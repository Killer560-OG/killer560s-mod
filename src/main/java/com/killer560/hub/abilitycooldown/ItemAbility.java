package com.killer560.hub.abilitycooldown;

import java.util.Locale;

/**
 * The Skyblock item-ability -> cooldown table.
 *
 * <p><b>Every number here is ported, not invented.</b> Source: SkyHanni {@code beta} branch,
 * {@code src/main/java/at/hannibal2/skyhanni/features/itemabilities/abilitycooldown/ItemAbility.kt}
 * (fetched 2026-09-16 from
 * {@code https://raw.githubusercontent.com/hannibal002/SkyHanni/beta/src/main/java/at/hannibal2/skyhanni/features/itemabilities/abilitycooldown/ItemAbility.kt}).
 * The enum constant names are kept identical to SkyHanni's so the two tables can be diffed line for line
 * the next time SkyHanni's is updated; only the human-readable labels and the {@link #isDungeon()} flag are
 * this mod's own additions.
 *
 * <p>Cross-checked against Devonian (local Kotlin source, a local Devonian checkout):
 * <ul>
 *   <li>{@code features/misc/WitherShieldTimer.kt} uses {@code cooldown = if (scrolls.size == 3) 100 else 200}
 *       server ticks, i.e. 5 s with all three scrolls (Wither Impact) - exactly SkyHanni's
 *       {@code WITHER_IMPACT(5)}.</li>
 *   <li>{@code features/misc/TacticalInsertionTimer.kt} shows a 60-tick (3 s) phase off the
 *       {@code item.flintandsteel.use} sound at pitch {@code 0.74603176} - the same sound, pitch and 3 s
 *       first phase SkyHanni uses for {@code TACTICAL_INSERTION}.</li>
 *   <li>Ragnarock Axe's 20 s matches this mod's own {@code ragaxe/RagAxeState} (Odin + the Hypixel wiki).</li>
 * </ul>
 *
 * <p><b>Deliberately NOT in this table</b> (they are already timed elsewhere in this mod, and two timers for
 * one ability would just disagree with each other):
 * <ul>
 *   <li><b>Ragnarock Axe</b> - {@code com.killer560.hub.ragaxe} owns it (3 s channel, 10 s strength buff,
 *       configurable 20 s cooldown, plus the "rag now" prompts). SkyHanni's value for it is
 *       {@code RAGNAROCK_AXE(20)}, the same 20 s {@code RagAxeConfig} already defaults to.</li>
 *   <li><b>Spirit Mask / Bonzo's Mask / Phoenix Pet</b> - {@code com.killer560.hub.maskinvincibility} owns
 *       them (30 s / 180 s / 60 s). They are death-save procs, not used abilities, and SkyHanni's ability
 *       table does not contain them either.</li>
 * </ul>
 *
 * <p><b>Not added because no reference mod has a real number for them:</b> Bonzo's Staff, Jerry-chine Gun
 * and Spirit Sceptre have no cooldown in SkyHanni's table (they are mana-cost-only abilities). Nothing here
 * guesses one.
 *
 * <p><b>Removed 2026-09-21</b> (killer560: "remove shadow warp, wither shield and implosion from that
 * list"): {@code WITHER_SHIELD_SCROLL}, {@code SHADOW_WARP_SCROLL} and {@code IMPLOSION_SCROLL} used to be
 * separate toggleable entries here (SkyHanni: {@code WITHER_SHIELD_SCROLL(10, ignoreMageCooldownReduction =
 * true)}, {@code SHADOW_WARP_SCROLL(10)}, {@code IMPLOSION_SCROLL(10)}). {@link #WITHER_IMPACT} - the combo
 * of all three scrolls on one item - is the only ability of the three still timed; see
 * {@code AbilityCooldownState#hasAllWitherComboScrolls}. A saved per-ability toggle for one of the three
 * removed ids just stops being read (see {@code AbilityCooldownConfig#load}) - it never throws.
 */
public enum ItemAbility {

    // --- ability scrolls / Hyperion-class (dungeon) -------------------------------------------------
    /** All three scrolls on one item. SkyHanni: {@code WITHER_IMPACT(5, ignoreMageCooldownReduction = true)}. */
    WITHER_IMPACT("Wither Impact", 5, true, true),

    // --- other dungeon-relevant items ---------------------------------------------------------------
    /** SkyHanni: {@code GYROKINETIC_WAND_LEFT(30, "GYROKINETIC_WAND")}. */
    GYROKINETIC_WAND_LEFT("Gyrokinetic Wand (Left)", 30, true, false, "GYROKINETIC_WAND"),
    /** SkyHanni: {@code GYROKINETIC_WAND_RIGHT(10, "GYROKINETIC_WAND")}. */
    GYROKINETIC_WAND_RIGHT("Gyrokinetic Wand (Right)", 10, true, false, "GYROKINETIC_WAND"),
    /** SkyHanni: {@code GIANTS_SWORD(30)}. */
    GIANTS_SWORD("Giants Sword", 30, true, false),
    /** SkyHanni: {@code ICE_SPRAY_WAND(5, "STARRED_ICE_SPRAY_WAND")}. */
    ICE_SPRAY_WAND("Ice Spray Wand", 5, true, false, "STARRED_ICE_SPRAY_WAND"),
    /** SkyHanni: {@code WAND_OF_ATONEMENT(7, "WAND_OF_HEALING", "WAND_OF_MENDING", "WAND_OF_RESTORATION")}.
     *  SkyHanni also hard-excludes this one from the mage multiplier in {@code getCooldown()}. */
    WAND_OF_ATONEMENT("Wand of Atonement", 7, true, true,
            "WAND_OF_HEALING", "WAND_OF_MENDING", "WAND_OF_RESTORATION"),
    /** SkyHanni: {@code SHADOW_FURY(15, "STARRED_SHADOW_FURY")}. */
    SHADOW_FURY("Shadow Fury", 15, true, false, "STARRED_SHADOW_FURY"),
    /** SkyHanni: {@code FIRE_FREEZE_STAFF(10)}. */
    FIRE_FREEZE_STAFF("Fire Freeze Staff", 10, true, false),
    /** SkyHanni: {@code WITHER_CLOAK(10)}. */
    WITHER_CLOAK("Wither Cloak", 10, true, false),
    /** SkyHanni: {@code TACTICAL_INSERTION(20)} - a 3 s placed phase then a 17 s cooldown, see
     *  {@link AbilityCooldownState}. */
    TACTICAL_INSERTION("Tactical Insertion", 20, true, false),
    /** SkyHanni: {@code WEIRD_TUBA(20)}. */
    WEIRD_TUBA("Weird Tuba", 20, true, false),
    /** SkyHanni: {@code WEIRDER_TUBA(30)}. */
    WEIRDER_TUBA("Weirder Tuba", 30, true, false),
    /** SkyHanni: {@code SOUL_ESOWARD(20)}. */
    SOUL_ESOWARD("Soul Esoward", 20, true, false),
    /** SkyHanni: {@code SWORD_OF_BAD_HEALTH(5)}. */
    SWORD_OF_BAD_HEALTH("Sword of Bad Health", 5, true, false),
    /** SkyHanni: {@code STARLIGHT_WAND(2)}. */
    STARLIGHT_WAND("Starlight Wand", 2, true, false),
    /** SkyHanni: {@code HOLY_ICE(4)}. */
    HOLY_ICE("Holy Ice", 4, true, false),

    // --- non-dungeon items ---------------------------------------------------------------------------
    /** SkyHanni: {@code SOS_FLARE(10)}. */
    SOS_FLARE("SOS Flare", 10, false, false),
    /** SkyHanni: {@code ALERT_FLARE(20, "WARNING_FLARE")}. */
    ALERT_FLARE("Alert Flare", 20, false, false, "WARNING_FLARE"),
    /** SkyHanni: {@code GOLEM_SWORD(3)}. */
    GOLEM_SWORD("Golem Sword", 3, false, false),
    /** SkyHanni: {@code END_STONE_SWORD(5)}. */
    END_STONE_SWORD("End Stone Sword", 5, false, false),
    /** SkyHanni: {@code PIGMAN_SWORD(5)}. */
    PIGMAN_SWORD("Pigman Sword", 5, false, false),
    /** SkyHanni: {@code EMBER_ROD(30)}. */
    EMBER_ROD("Ember Rod", 30, false, false),
    /** SkyHanni: {@code STAFF_OF_THE_VOLCANO(30)}. */
    STAFF_OF_THE_VOLCANO("Staff of the Volcano", 30, false, false),
    /** SkyHanni: {@code VOODOO_DOLL(5)}. */
    VOODOO_DOLL("Voodoo Doll", 5, false, false),
    /** SkyHanni: {@code VOODOO_DOLL_WILTED(3)}. */
    VOODOO_DOLL_WILTED("Wilted Voodoo Doll", 3, false, false),
    /** SkyHanni: {@code FIRE_FURY_STAFF(20)}. */
    FIRE_FURY_STAFF("Fire Fury Staff", 20, false, false),
    /** SkyHanni: {@code ROYAL_PIGEON(5)}. */
    ROYAL_PIGEON("Royal Pigeon", 5, false, false),
    /** SkyHanni: {@code WAND_OF_STRENGTH(10)}. */
    WAND_OF_STRENGTH("Wand of Strength", 10, false, false),
    /** SkyHanni: {@code TOTEM_OF_CORRUPTION(20)}. */
    TOTEM_OF_CORRUPTION("Totem of Corruption", 20, false, false),
    /** SkyHanni: {@code ENRAGER(20)}. */
    ENRAGER("Enrager", 20, false, false),

    // --- action-bar-detected only (SkyHanni's own comment: "doesn't have a sound") --------------------
    /** SkyHanni: {@code ENDER_BOW("Ender Warp", 5, "Ender Bow")}. */
    ENDER_BOW("Ender Bow", 5, true, false, "Ender Warp", true),
    /** SkyHanni: {@code LIVID_DAGGER("Throw", 5, "Livid Dagger")}. */
    LIVID_DAGGER("Livid Dagger", 5, true, false, "Throw", true),
    /** SkyHanni: {@code FIRE_VEIL("Fire Veil", 5, "Fire Veil Wand")}. */
    FIRE_VEIL("Fire Veil Wand", 5, true, false, "Fire Veil", true),
    /** SkyHanni: {@code INK_WAND("Ink Bomb", 30, "Ink Wand")}. */
    INK_WAND("Ink Wand", 30, false, false, "Ink Bomb", true),
    /** SkyHanni: {@code ROGUE_SWORD("Speed Boost", 30, "Rogue Sword", ignoreMageCooldownReduction = true)}. */
    ROGUE_SWORD("Rogue Sword", 30, false, true, "Speed Boost", true),
    /** SkyHanni: {@code TALBOTS_THEODOLITE("Track", 10, "Talbot's Theodolite")}. */
    TALBOTS_THEODOLITE("Talbots Theodolite", 10, false, false, "Track", true),
    /** SkyHanni: {@code ATOMSPLIT_KATANA("Soulcry", 4, "Atomsplit Katana", "Vorpal Katana",
     *  "Voidedge Katana", ignoreMageCooldownReduction = true)}. */
    ATOMSPLIT_KATANA("Atomsplit Katana", 4, true, true, "Soulcry", true),
    /** SkyHanni: {@code ECHO("Echo", 3, "Ancestral Spade")} - "doesn't have a consistent sound". */
    ECHO("Ancestral Spade", 3, false, false, "Echo", true);

    private final String label;
    private final int cooldownSeconds;
    private final boolean dungeon;
    private final boolean ignoreMageReduction;
    /** Extra Skyblock item ids that also count as this ability (SkyHanni's {@code alternateInternalNames}).
     *  The constant's own name is always treated as an id too. */
    private final String[] extraItemIds;
    /** The name Hypixel puts in the action bar's "-N Mana (Name)" line, or null if this one is sound-detected. */
    private final String actionBarName;

    ItemAbility(String label, int cooldownSeconds, boolean dungeon, boolean ignoreMageReduction,
                String... extraItemIds) {
        this.label = label;
        this.cooldownSeconds = cooldownSeconds;
        this.dungeon = dungeon;
        this.ignoreMageReduction = ignoreMageReduction;
        this.extraItemIds = extraItemIds;
        this.actionBarName = null;
    }

    ItemAbility(String label, int cooldownSeconds, boolean dungeon, boolean ignoreMageReduction,
                String actionBarName, boolean actionBarDetected) {
        this.label = label;
        this.cooldownSeconds = cooldownSeconds;
        this.dungeon = dungeon;
        this.ignoreMageReduction = ignoreMageReduction;
        this.extraItemIds = new String[0];
        this.actionBarName = actionBarDetected ? actionBarName : null;
    }

    public String label() {
        return label;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** Base cooldown in ms, before any mage reduction. */
    public long baseCooldownMs() {
        return cooldownSeconds * 1000L;
    }

    /** Whether this one is worth showing to an F7/M7 player (drives the "Dungeon Abilities Only" filter). */
    public boolean isDungeon() {
        return dungeon;
    }

    /** SkyHanni's {@code ignoreMageCooldownReduction}. */
    public boolean ignoresMageReduction() {
        return ignoreMageReduction;
    }

    public String actionBarName() {
        return actionBarName;
    }

    /** @return true if {@code skyblockId} names this ability's item. */
    public boolean matchesItem(String skyblockId) {
        if (skyblockId == null) {
            return false;
        }
        if (skyblockId.equals(name())) {
            return true;
        }
        for (String id : extraItemIds) {
            if (skyblockId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Stable key for the config file, so renaming a label never loses a saved toggle. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
