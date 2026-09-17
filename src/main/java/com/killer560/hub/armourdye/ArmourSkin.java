package com.killer560.hub.armourdye;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;

import java.util.Locale;

/**
 * The built-in armour "skins" - killer560 (2026-09-16): "an option from skyblocker where you can custom recolor
 * armor, using dyes and armor skin client side."
 * <p>
 * An armour skin is a vanilla {@link EquipmentAsset} key. That key is what
 * {@code EquipmentLayerRenderer} feeds to the {@code EquipmentAssetManager} to decide which armour texture is
 * painted on the body, so swapping it swaps the worn look of the piece without touching the real item. Skyblocker
 * calls the same thing {@code customArmorModel} and swaps the key with a {@code @ModifyVariable} on
 * {@code EquipmentLayerRenderer#renderLayers}; we get the same result one level earlier by rewriting the
 * {@code minecraft:equippable} component's {@code assetId} (see {@code mixin/ArmourDyeComponentMixin}), which is
 * where {@code HumanoidArmorLayer} reads the key from in the first place. Credit: Skyblocker
 * (github.com/SkyblockerMod/Skyblocker), same as QUOI, Odin, NoammAddons and Devonian elsewhere in this mod.
 * <p>
 * Each preset also names the vanilla item whose inventory icon matches, so "Diamond" looks like diamond armour in
 * the inventory as well as on the body. {@code TURTLE} only has a helmet in vanilla, so the other three slots keep
 * their real icon rather than showing a wrong one.
 */
public enum ArmourSkin {

    NONE("None", null, null, null, null, null),
    LEATHER("Leather", EquipmentAssets.LEATHER, "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots"),
    COPPER("Copper", EquipmentAssets.COPPER, "copper_helmet", "copper_chestplate", "copper_leggings", "copper_boots"),
    CHAINMAIL("Chainmail", EquipmentAssets.CHAINMAIL, "chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots"),
    IRON("Iron", EquipmentAssets.IRON, "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"),
    GOLD("Gold", EquipmentAssets.GOLD, "golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots"),
    DIAMOND("Diamond", EquipmentAssets.DIAMOND, "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"),
    NETHERITE("Netherite", EquipmentAssets.NETHERITE, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots"),
    TURTLE("Turtle Shell", EquipmentAssets.TURTLE_SCUTE, "turtle_helmet", null, null, null),
    /** Anything not in the list above - the entry's own {@code skinAsset} / {@code iconModel} identifiers are used. */
    CUSTOM("Custom ID", null, null, null, null, null);

    public final String label;
    private final ResourceKey<EquipmentAsset> asset;
    private final String headModel;
    private final String chestModel;
    private final String legsModel;
    private final String feetModel;

    ArmourSkin(String label, ResourceKey<EquipmentAsset> asset,
               String headModel, String chestModel, String legsModel, String feetModel) {
        this.label = label;
        this.asset = asset;
        this.headModel = headModel;
        this.chestModel = chestModel;
        this.legsModel = legsModel;
        this.feetModel = feetModel;
    }

    /** @return the equipment asset this preset paints on the body, or null for {@link #NONE}/{@link #CUSTOM}. */
    public ResourceKey<EquipmentAsset> asset() {
        return asset;
    }

    /** @return the matching vanilla item model for this slot, or null when the preset has no icon for it. */
    public Identifier iconModel(EquipmentSlot slot) {
        String path = switch (slot) {
            case HEAD -> headModel;
            case CHEST -> chestModel;
            case LEGS -> legsModel;
            case FEET -> feetModel;
            default -> null;
        };
        return path == null ? null : Identifier.withDefaultNamespace(path);
    }

    public ArmourSkin next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Saved by {@link #name()}; an unknown/renamed constant falls back to {@link #NONE}. */
    public static ArmourSkin byName(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return NONE;
        }
    }

    /** Builds an equipment-asset key from a raw {@code namespace:path}, or null when it isn't a valid identifier. */
    public static ResourceKey<EquipmentAsset> assetFromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(raw.trim());
        return id == null ? null : ResourceKey.create(EquipmentAssets.ROOT_ID, id);
    }
}
