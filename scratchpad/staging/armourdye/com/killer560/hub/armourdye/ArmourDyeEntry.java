package com.killer560.hub.armourdye;

/**
 * One armour piece's client-side look override, keyed on its Skyblock item id.
 * <p>
 * Skyblocker keys the same feature on the per-item {@code ExtraAttributes.uuid}, so re-dyeing means re-doing every
 * copy. killer560 asked for the item id instead - "keyed on the Skyblock item id, so every copy of that piece you
 * own looks the same" - so one entry covers a Necron's Chestplate whether it's starred, fragged or reforged
 * ({@code ItemIdentity.of} already strips {@code STARRED_}, the reforge {@code modifier} and {@code upgrade_level}).
 * <p>
 * Every field is a pure render override. Nothing here is ever written back to an {@code ItemStack}, so nothing can
 * reach the server.
 */
public final class ArmourDyeEntry {

    /** {@code ItemIdentity.of(stack)} - upper-case Skyblock id, or the cleaned display name for id-less items. */
    public final String itemId;

    /** The item's display name when it was captured, purely so the settings list is readable. */
    public String label;

    /** Per-entry off switch, so a look can be parked without losing its colours. */
    public boolean enabled = true;

    public boolean colorEnabled = false;

    /** ARGB. Vanilla's dye tint ignores alpha, so only the low 24 bits reach the screen. */
    public int color = 0xFFFFFFFF;

    public ArmourSkin skin = ArmourSkin.NONE;

    /** Raw {@code namespace:path} equipment asset, only used when {@link #skin} is {@link ArmourSkin#CUSTOM}. */
    public String skinAsset = "";

    /** Raw {@code namespace:path} item model for the inventory icon. Blank = derive it from {@link #skin}. */
    public String iconModel = "";

    /** Raw trim material identifier (e.g. {@code minecraft:gold}). Blank = leave the real trim alone. */
    public String trimMaterial = "";

    /** Raw trim pattern identifier (e.g. {@code minecraft:sentry}). Blank = leave the real trim alone. */
    public String trimPattern = "";

    public ArmourDyeEntry(String itemId, String label) {
        this.itemId = itemId;
        this.label = label == null || label.isBlank() ? itemId : label;
    }

    /** True when this entry would change nothing - the tab greys those out rather than hiding them. */
    public boolean isEmpty() {
        return !colorEnabled && skin == ArmourSkin.NONE && !hasTrim();
    }

    public boolean hasTrim() {
        return !trimMaterial.isBlank() && !trimPattern.isBlank();
    }
}
