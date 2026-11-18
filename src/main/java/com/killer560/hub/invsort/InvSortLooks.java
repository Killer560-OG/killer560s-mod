package com.killer560.hub.invsort;

import com.killer560.hub.auction.BazaarIcons;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.packdisabler.ItemLooks;
import com.killer560.hub.storagesearch.StorageSearchNbtCodec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The icon of a saved layout item the player is not holding (invsorttex-1117, killer560 2026-11-17: the Spirit Sceptre
 * drew as paper). The saved vanilla item id alone cannot say what a SkyBlock item looks like: Hypixel items are
 * paper, skulls or tools wearing an item model, skin or dye. So a layout stores the look when the item was held
 * ({@link #encode}), and an older file resolves it from the SkyBlock id through the item catalog and Pack Disabler's
 * table, the same sources the Item Browser and the Bazaar draw with. Called when a preview or the grid is built,
 * never per frame.
 */
final class InvSortLooks {

    /** Decoded saved looks by blob, so reopening the screen decodes nothing twice. Bounded. */
    private static final Map<String, ItemStack> DECODED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > 256;
        }
    };

    private InvSortLooks() {
    }

    /** Only what decides the picture: item, skull skin, item model, custom model data, dye, glint. */
    static ItemStack trim(ItemStack stack) {
        ItemStack out = new ItemStack(stack.getItem());
        copy(stack, out, DataComponents.PROFILE);
        copy(stack, out, DataComponents.ITEM_MODEL);
        copy(stack, out, DataComponents.CUSTOM_MODEL_DATA);
        copy(stack, out, DataComponents.DYED_COLOR);
        copy(stack, out, DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        if (stack.hasFoil() && out.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null) {
            out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return out;
    }

    private static <T> void copy(ItemStack from, ItemStack to, DataComponentType<T> type) {
        T value = from.get(type);
        if (value != null) {
            to.set(type, value);
        }
    }

    /** The saved form of {@code stack}'s look, or null when it cannot be encoded yet (no world). */
    static String encode(ItemStack stack) {
        try {
            return StorageSearchNbtCodec.encodeOrThrow(List.of(trim(stack)));
        } catch (Exception e) {
            return null;
        }
    }

    /** The stack to draw for {@code slot} of {@code layout} when its item is not held; never null, never empty. */
    static ItemStack resolve(InventoryLayout layout, int slot) {
        String blob = layout.looks().get(slot);
        ItemStack out = blob == null ? null : DECODED.get(blob);
        if (blob != null && out == null) {
            try {
                List<ItemStack> list = StorageSearchNbtCodec.decodeOrThrow(blob);
                out = list == null || list.isEmpty() ? null : list.get(0);
            } catch (Exception e) {
                out = null;
            }
            if (out != null && !out.isEmpty()) {
                DECODED.put(blob, out);
            }
        }
        return out != null && !out.isEmpty() ? out.copy() : fromId(layout.identityAt(slot), layout.iconAt(slot));
    }

    /** An older file (no saved look): the catalog's own stack for the SkyBlock id (skin, model, dye) or else the saved
     *  vanilla icon, then the id's look from Pack Disabler's table, as the Bazaar draws it: the Hypixel pack's model
     *  when a loaded pack has it, else the pre-pack vanilla look (a Spirit Sceptre is paper wearing a pack model). */
    private static ItemStack fromId(String id, String iconId) {
        ItemStack stack = null;
        try {
            SkyblockItemEntry entry = SkyblockItemRepository.findOnDisk(id);
            if (entry != null) {
                stack = SkyblockItemStackFactory.build(entry).copy();
            }
        } catch (Exception ignored) {
            // catalog not readable: the saved vanilla icon below
        }
        if (stack == null) {
            Identifier key = iconId == null ? null : Identifier.tryParse(iconId);
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            stack = new ItemStack(item == null || item == Items.AIR ? Items.PAPER : item);
        }
        ItemLooks.Look look = ItemLooks.get(id);
        String model = look == null ? null : look.packModel();
        Identifier modelId = model == null || !BazaarIcons.packModelLoaded(model) ? null : Identifier.tryParse(model);
        if (modelId != null) {
            stack.set(DataComponents.ITEM_MODEL, modelId);
            if (look.glint()) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
        } else {
            ItemLooks.applyLook(stack, id);
        }
        return stack;
    }
}
