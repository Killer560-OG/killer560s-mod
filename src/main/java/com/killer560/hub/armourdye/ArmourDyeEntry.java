package com.killer560.hub.armourdye;

/**
 * One item's client-side look override in Custom Items (Armour Recolour until 2026-10-08).
 * <p>
 * Keyed one of two ways (killer560, 2026-10-08: "select an item in your inventory and you can then apply a skin to it
 * or recolor"): the Skyblock item id, so every copy of the item looks the same (his original Armour Recolour ask, and
 * how every migrated entry stays), or {@code UUID:<uuid>} for that one physical item. {@link ArmourDye#entryFor}
 * tries the UUID key first. Every field is a pure render override; nothing here is ever written back to an
 * {@code ItemStack}, so nothing can reach the server.
 */
public final class ArmourDyeEntry {

    /** Key prefix for a per-item (UUID) entry; the rest is the Skyblock {@code uuid}, upper-cased. */
    public static final String UUID_PREFIX = "UUID:";

    /** The key: an upper-case Skyblock id (every copy), or {@link #UUID_PREFIX} + uuid (this item only). */
    public final String itemId;

    /** The item's display name when it was added, purely so the saved list is readable. */
    public String label;

    /** Per-entry off switch, so a look can be parked without losing it. */
    public boolean enabled = true;

    public boolean colorEnabled = false;

    /** ARGB. Vanilla's dye tint ignores alpha, so only the low 24 bits reach the screen. */
    public int color = 0xFFFFFFFF;

    /** Armour only: which armour set the piece is painted as on the body. */
    public ArmourSkin skin = ArmourSkin.NONE;

    /** Raw {@code namespace:path} equipment asset, only used when {@link #skin} is {@link ArmourSkin#CUSTOM}. */
    public String skinAsset = "";

    /** The "Look": a raw {@code namespace:path} item model the item is drawn as (inventory, hand, dropped).
     *  Blank = its own model. */
    public String iconModel = "";

    /** A player-head texture (the base64 {@code textures} value). Set = the item is drawn as that head. */
    public String headTexture = "";

    /** Raw trim material identifier (e.g. {@code minecraft:gold}). Blank = leave the real trim alone. */
    public String trimMaterial = "";

    /** Raw trim pattern identifier (e.g. {@code minecraft:sentry}). Blank = leave the real trim alone. */
    public String trimPattern = "";

    public ArmourDyeEntry(String itemId, String label) {
        this.itemId = itemId;
        this.label = label == null || label.isBlank() ? itemId : label;
    }

    /** A copy of every look field under another key (Applies To switched between every copy and this item). */
    public ArmourDyeEntry copyAs(String newKey) {
        ArmourDyeEntry e = new ArmourDyeEntry(newKey, label);
        e.enabled = enabled;
        e.colorEnabled = colorEnabled;
        e.color = color;
        e.skin = skin;
        e.skinAsset = skinAsset;
        e.iconModel = iconModel;
        e.headTexture = headTexture;
        e.trimMaterial = trimMaterial;
        e.trimPattern = trimPattern;
        return e;
    }

    public boolean isPerItem() {
        return itemId.startsWith(UUID_PREFIX);
    }

    /** True when this entry would change nothing. */
    public boolean isEmpty() {
        return !colorEnabled && skin == ArmourSkin.NONE && !hasTrim() && iconModel.isBlank() && headTexture.isBlank();
    }

    public boolean hasTrim() {
        return !trimMaterial.isBlank() && !trimPattern.isBlank();
    }
}
