package com.killer560.hub.profileviewer.item;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.fixes.ItemIdFix;
import net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel API inventory blobs ({@code {"type":0,"data":"<base64 gzipped NBT>"}}) -> modern ItemStacks.
 * <p>
 * The approach mirrors meowdding item-data-fixer's {@code LegacyDataFixer} (what skyblock-pv uses):
 * resolve the 1.8 numeric id + Damage to a modern item, then map the handful of legacy tag fields
 * Skyblock actually uses onto data components (name, lore, skull texture, leather color, glint,
 * ExtraAttributes as custom_data). Instead of hand-copying its 1000-line id table, the id resolution uses
 * vanilla's own DataFixer tables ({@link ItemIdFix#getItem} and
 * {@link ItemStackTheFlatteningFix#updateItem}) plus the few post-1.13 renames.
 */
public final class LegacyItems {

    private static final Map<String, String> POST_FLATTEN_RENAMES = Map.ofEntries(
            Map.entry("minecraft:cactus_green", "minecraft:green_dye"),
            Map.entry("minecraft:rose_red", "minecraft:red_dye"),
            Map.entry("minecraft:dandelion_yellow", "minecraft:yellow_dye"),
            Map.entry("minecraft:sign", "minecraft:oak_sign"),
            Map.entry("minecraft:grass", "minecraft:short_grass"),
            Map.entry("minecraft:grass_path", "minecraft:dirt_path"),
            Map.entry("minecraft:scute", "minecraft:turtle_scute"),
            Map.entry("minecraft:zombie_pigman_spawn_egg", "minecraft:zombified_piglin_spawn_egg"),
            Map.entry("minecraft:spawn_egg", "minecraft:pig_spawn_egg"),
            Map.entry("minecraft:stone_slab", "minecraft:smooth_stone_slab"),
            Map.entry("minecraft:fireworks", "minecraft:firework_rocket"),
            Map.entry("minecraft:firework_charge", "minecraft:firework_star")
    );

    private static final Map<String, ResolvableProfile> PROFILE_CACHE = new ConcurrentHashMap<>();
    private static final TooltipDisplay HIDE_VANILLA_LINES;

    static {
        SequencedSet<DataComponentType<?>> hidden = new LinkedHashSet<>();
        hidden.add(DataComponents.ATTRIBUTE_MODIFIERS);
        hidden.add(DataComponents.DYED_COLOR);
        hidden.add(DataComponents.ENCHANTMENTS);
        hidden.add(DataComponents.STORED_ENCHANTMENTS);
        hidden.add(DataComponents.UNBREAKABLE);
        hidden.add(DataComponents.POTION_CONTENTS);
        HIDE_VANILLA_LINES = new TooltipDisplay(false, hidden);
    }

    private LegacyItems() {
    }

    /** Decodes an API inventory object; slots stay in order (empty slots are {@link ItemStack#EMPTY}).
     *  Returns an empty list for null/unsupported/corrupt data. */
    public static List<ItemStack> decodeInventory(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return List.of();
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("type") && obj.get("type").isJsonPrimitive() && obj.get("type").getAsInt() != 0) {
            return List.of();
        }
        if (!obj.has("data") || !obj.get("data").isJsonPrimitive()) {
            return List.of();
        }
        return decodeBase64(obj.get("data").getAsString());
    }

    public static List<ItemStack> decodeBase64(String data) {
        if (data == null || data.isBlank()) {
            return List.of();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data.trim());
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            List<ItemStack> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(fromLegacyTag(list.getCompoundOrEmpty(i)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static ItemStack fromLegacyTag(CompoundTag tag) {
        try {
            if (tag == null || tag.isEmpty()) {
                return ItemStack.EMPTY;
            }
            Item item = resolveItem(tag);
            if (item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            int count = Math.max(1, tag.getByteOr("Count", (byte) 1));
            ItemStack stack = new ItemStack(item, count);
            CompoundTag extra = tag.getCompoundOrEmpty("tag");
            applyTag(stack, extra);
            return stack;
        } catch (Exception e) {
            return new ItemStack(Items.BARRIER);
        }
    }

    private static Item resolveItem(CompoundTag tag) {
        String legacyName;
        Optional<String> stringId = tag.getString("id");
        if (stringId.isPresent()) {
            legacyName = stringId.get();
        } else {
            int numeric = tag.getShort("id").map(Short::intValue).orElseGet(() -> tag.getIntOr("id", 0));
            if (numeric == 0) {
                return Items.AIR;
            }
            legacyName = ItemIdFix.getItem(numeric);
        }
        if (legacyName == null) {
            return Items.BARRIER;
        }
        if (!legacyName.contains(":")) {
            legacyName = "minecraft:" + legacyName;
        }
        int damage = tag.getShortOr("Damage", (short) 0);
        String flattened = ItemStackTheFlatteningFix.updateItem(legacyName, damage);
        String name = flattened != null ? flattened : legacyName;
        name = POST_FLATTEN_RENAMES.getOrDefault(name, name);
        Identifier id = Identifier.tryParse(name);
        if (id == null) {
            return Items.BARRIER;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) {
            return Items.BARRIER;
        }
        return item.get();
    }

    private static void applyTag(ItemStack stack, CompoundTag tag) {
        if (tag.isEmpty()) {
            return;
        }
        CompoundTag display = tag.getCompoundOrEmpty("display");
        display.getString("Name").ifPresent(name -> stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(name)));
        ListTag lore = display.getListOrEmpty("Lore");
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (int i = 0; i < lore.size(); i++) {
                lines.add(LegacyText.parse(lore.getStringOr(i, "")));
            }
            if (lines.size() > ItemLore.MAX_LINES) {
                lines = new ArrayList<>(lines.subList(0, ItemLore.MAX_LINES));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines, lines));
        }
        display.getInt("color").ifPresent(color -> stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color)));

        if (tag.contains("ench")) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }

        tag.getCompound("SkullOwner").ifPresent(owner -> {
            ListTag textures = owner.getCompoundOrEmpty("Properties").getListOrEmpty("textures");
            if (!textures.isEmpty()) {
                textures.getCompoundOrEmpty(0).getString("Value").ifPresent(value ->
                        stack.set(DataComponents.PROFILE, skullProfile(value)));
            }
        });

        tag.getCompound("ExtraAttributes").ifPresent(extra -> stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra.copy())));

        tag.getString("ItemModel").ifPresent(model -> {
            Identifier modelId = Identifier.tryParse(model);
            // Only vanilla models: a Hypixel pack model id renders as missing-texture when the pack
            // isn't loaded (e.g. viewing from singleplayer).
            if (modelId != null && "minecraft".equals(modelId.getNamespace())) {
                stack.set(DataComponents.ITEM_MODEL, modelId);
            }
        });

        stack.set(DataComponents.TOOLTIP_DISPLAY, HIDE_VANILLA_LINES);
    }

    /** A resolved profile carrying just the texture blob - same mechanism as
     *  {@code SkyblockItemStackFactory} and item-data-fixer's SkullTextureFixer. */
    public static ResolvableProfile skullProfile(String textureValue) {
        return PROFILE_CACHE.computeIfAbsent(textureValue, value -> {
            Multimap<String, Property> backing = LinkedHashMultimap.create();
            backing.put("textures", new Property("textures", value));
            UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ResolvableProfile.createResolved(new GameProfile(uuid, "SkyblockItem", new PropertyMap(backing)));
        });
    }

    public static ItemStack skull(String textureValue, Component name) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        if (textureValue != null && !textureValue.isEmpty()) {
            stack.set(DataComponents.PROFILE, skullProfile(textureValue));
        }
        if (name != null) {
            stack.set(DataComponents.CUSTOM_NAME, name);
        }
        stack.set(DataComponents.TOOLTIP_DISPLAY, HIDE_VANILLA_LINES);
        return stack;
    }

    /** Skyblock id from custom_data (ExtraAttributes.id), or "" if none. */
    public static String skyblockId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr("id", "");
    }
}
