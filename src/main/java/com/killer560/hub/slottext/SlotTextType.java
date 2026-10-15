package com.killer560.hub.slottext;

/** One kind of small text {@link SlotTextFeature} can draw on a slot. Each has its own on/off setting. Names and the
 *  menu each one applies to were read off Skyblocker's Slot Text documentation; the code is this mod's own. */
public enum SlotTextType {
    PET_LEVEL("Pet Level", "A pet's level, top left. Pets in the pets menu, the choose-pet menu and your inventory.", true),
    ENCHANT_LEVEL("Enchanted Book Level", "The level of the enchantment on an enchanted book, bottom left. Also Bazaar book listings.", true),
    ENCHANT_ABBREVIATION("Enchantment Abbreviation", "A short tag for the enchantment on a book, top left (SHA for Sharpness, BB for Big Brain). Ultimate enchantments are purple.", false),
    MINION_TIER("Minion Tier", "A minion's tier as a number, top right.", true),
    POTION_LEVEL("Potion Level", "A potion's level, bottom right.", true),
    RANCHER_SPEED("Rancher's Boots Speed", "The speed cap set on Rancher's Boots, bottom left.", true),
    ATTRIBUTE_LEVEL("Attribute Shard Level", "The level of the attribute on an attribute shard, bottom right.", true),
    SKILL_LEVEL("Skill Level", "Each skill's level in the Your Skills menu, bottom left. Gold when maxed.", true),
    COLLECTION_LEVEL("Collection Level", "Each collection's tier in the collection menus, bottom right. Gold when every tier is done.", true),
    BESTIARY_LEVEL("Bestiary Level", "Each mob's Bestiary level in the Bestiary and Fishing menus, bottom right. Gold at 100% progress.", true),
    PERK_LEVEL("Heart Perk Level", "A perk's level in the Heart of the Mountain and Heart of the Forest menus, bottom right.", true),
    STATS_TUNING("Stats Tuning Points", "Points assigned to each stat in the Stats Tuning menu, and the unassigned points on its button, bottom right.", true);

    public final String label;
    public final String description;
    public final boolean defaultOn;

    SlotTextType(String label, String description, boolean defaultOn) {
        this.label = label;
        this.description = description;
        this.defaultOn = defaultOn;
    }
}
