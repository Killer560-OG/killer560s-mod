package com.killer560.hub.petwheel;

/**
 * One pet read off the real Hypixel {@code /pets} menu, identified by its own item's real
 * {@code ExtraAttributes.uuid} (same field {@link com.killer560.hub.itemprotect.ItemProtect#itemUuid} already
 * reads for item identity) - name + rarity is not unique (killer560 can own two of the same pet at two
 * different levels, or two of the exact same level), the per-item uuid is.
 *
 * @param uuid      the item's ExtraAttributes uuid - never null/blank; an item without one is never turned
 *                  into a PetEntry (see {@link PetsMenuScanner#scanSlot}), so it can never end up on the wheel.
 * @param name      formatting-stripped display name with the "[Lvl N]" prefix removed (kept separately in
 *                  {@link #level}) - e.g. "Golden Dragon".
 * @param tier      rarity token off {@code ExtraAttributes.petInfo}'s {@code tier} field (e.g. "LEGENDARY"),
 *                  or null if the item had no parseable {@code petInfo} that tick.
 * @param level     parsed off the real {@code "[Lvl N]"} prefix Hypixel puts in the pet's display name
 *                  (verified against Devonian's own compiled pet-name regex - see {@link PetsMenuScanner}),
 *                  or -1 if the name didn't have one.
 * @param skinValue base64 {@code textures} property off the item's real {@code minecraft:profile} component,
 *                  used to render the pet's real head texture on the wheel - null falls back to a plain
 *                  player head icon.
 */
public record PetEntry(String uuid, String name, String tier, int level, String skinValue) {

    public PetEntry {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("PetEntry requires a non-blank ExtraAttributes uuid");
        }
        name = name == null || name.isBlank() ? "Unknown Pet" : name;
    }

    /** A short label for buttons/rows: {@code "[Lvl 100] Golden Dragon"}, or just the name if the level or
     *  tier couldn't be read that scan. */
    public String shortLabel() {
        return level >= 0 ? "[Lvl " + level + "] " + name : name;
    }

    /** Merges freshly-scanned fields onto this entry: whichever field the fresh read actually has wins,
     *  otherwise the previous value is kept - a single frame's item can briefly lack lore/petInfo while
     *  Hypixel is still sending the menu (same "don't overwrite a good read with a blank one" rule
     *  {@code SpiritLeapOverlayFeature}'s settle window exists for, applied here without needing a settle
     *  window at all since a stale display is harmless for a passive cache, unlike a click target). */
    public PetEntry mergedWith(PetEntry fresh) {
        return new PetEntry(
                uuid,
                fresh.name != null && !fresh.name.equals("Unknown Pet") ? fresh.name : name,
                fresh.tier != null ? fresh.tier : tier,
                fresh.level >= 0 ? fresh.level : level,
                fresh.skinValue != null ? fresh.skinValue : skinValue
        );
    }
}
