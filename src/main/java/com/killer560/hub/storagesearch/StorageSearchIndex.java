package com.killer560.hub.storagesearch;

import com.killer560.hub.itemrarity.ItemRarity;
import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A one-shot snapshot of every searchable item: each Storage Overlay cache entry for the CURRENT
 *  account/profile (read-only via {@link StorageOverlayCache}, decoded once per snapshot, not per
 *  keystroke) plus - optionally - the player's live inventory, armor and offhand. Lower-cased name /
 *  Skyblock id / lore strings are precomputed so filtering on every keystroke is just substring checks. */
public final class StorageSearchIndex {

    public enum SourceType { ENDER_CHEST, BACKPACK, INVENTORY }

    /** One searchable stack. {@code storageKey}/{@code storageNumber}/{@code contentIndex} are only
     *  meaningful for storages; {@code inventorySlot} only for {@link SourceType#INVENTORY}. */
    public record Entry(ItemStack stack, String name, String nameLower, String idLower, String loreLower,
                        int rarityRgb, SourceType type, String storageKey, int storageNumber, int contentIndex,
                        int inventorySlot, String location, long updatedMs, boolean updatedIsUpperBound) {
    }

    /** Summary of one storage for the "cache last updated" footer / never-opened warning. */
    public record StorageInfo(String key, String label, boolean hasContents, long updatedMs, boolean updatedIsUpperBound) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<StorageInfo> storages = new ArrayList<>();

    private StorageSearchIndex() {
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<StorageInfo> storages() {
        return storages;
    }

    public static StorageSearchIndex build(boolean includeInventory) {
        StorageSearchIndex index = new StorageSearchIndex();
        String prefix = StorageOverlayFeature.accountProfilePrefix();
        StorageOverlayCache cache = StorageOverlayCache.getInstance();
        long fileTime = StorageSearchTimestamps.overlayCacheFileTime();

        List<String> keys = new ArrayList<>(cache.knownKeysFor(prefix + "|"));
        keys.sort((a, b) -> {
            int[] oa = order(a, prefix);
            int[] ob = order(b, prefix);
            return oa[0] != ob[0] ? Integer.compare(oa[0], ob[0]) : Integer.compare(oa[1], ob[1]);
        });

        for (String key : keys) {
            int[] ord = order(key, prefix);
            if (ord[0] > 1) {
                continue;
            }
            String label = labelFor(key, prefix);
            long recorded = StorageSearchTimestamps.get(key);
            boolean upperBound = recorded < 0;
            long updated = upperBound ? fileTime : recorded;
            boolean has = cache.hasContents(key);
            index.storages.add(new StorageInfo(key, label, has, updated, upperBound));
            if (!has) {
                continue;
            }
            List<ItemStack> contents = cache.get(key);
            if (contents == null) {
                continue;
            }
            SourceType type = ord[0] == 0 ? SourceType.ENDER_CHEST : SourceType.BACKPACK;
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                int row = i / 9 + 1;
                int col = i % 9 + 1;
                String location = label + " · slot " + (i + 1) + " (r" + row + " c" + col + ")";
                index.entries.add(makeEntry(stack, type, key, ord[1], i, -1, location, updated, upperBound));
            }
        }

        if (includeInventory) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                Inventory inv = client.player.getInventory();
                long now = System.currentTimeMillis();
                int size = Math.min(inv.getContainerSize(), Inventory.SLOT_OFFHAND + 1);
                for (int i = 0; i < size; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    index.entries.add(makeEntry(stack, SourceType.INVENTORY, null, 0, -1, i,
                            inventoryLabel(i), now, false));
                }
            }
        }
        return index;
    }

    /** Case-insensitive substring match over display name, Skyblock id, and (optionally) lore. A blank
     *  query matches everything. */
    public List<Entry> filter(String query, boolean searchLore, boolean includeInventory) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!includeInventory && e.type() == SourceType.INVENTORY) {
                continue;
            }
            if (q.isEmpty() || e.nameLower().contains(q) || e.idLower().contains(q)
                    || (searchLore && e.loreLower().contains(q))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Entry makeEntry(ItemStack stack, SourceType type, String key, int number, int contentIndex,
                                   int invSlot, String location, long updated, boolean upperBound) {
        String name = stack.getHoverName().getString();
        String id = "";
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            try {
                id = data.copyTag().getStringOr("id", "");
            } catch (Exception ignored) {
            }
        }
        StringBuilder lore = new StringBuilder();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) {
                lore.append(line.getString()).append('\n');
            }
        }
        int rarityRgb = -1;
        try {
            ItemRarity rarity = ItemRarityFeature.getRarity(stack);
            if (rarity != null) {
                rarityRgb = rarity.rgb;
            }
        } catch (Exception ignored) {
        }
        return new Entry(stack, name, name.toLowerCase(Locale.ROOT), id.toLowerCase(Locale.ROOT),
                lore.toString().toLowerCase(Locale.ROOT), rarityRgb, type, key, number, contentIndex, invSlot,
                location, updated, upperBound);
    }

    private static String inventoryLabel(int slot) {
        if (slot < 9) {
            return "Hotbar · slot " + (slot + 1);
        }
        if (slot < Inventory.INVENTORY_SIZE) {
            int i = slot - 9;
            return "Inventory · r" + (i / 9 + 1) + " c" + (i % 9 + 1);
        }
        EquipmentSlot eq = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
        if (eq == EquipmentSlot.OFFHAND) {
            return "Offhand";
        }
        if (eq != null) {
            String n = eq.getName();
            return "Armor · " + (n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1));
        }
        return "Inventory · slot " + slot;
    }

    /** Custom name from Storage Overlay's settings if one was set, else "Ender Chest #3" / "Backpack #5". */
    static String labelFor(String key, String prefix) {
        String custom = StorageOverlayConfig.getInstance().getCustomName(key);
        String def = StorageOverlayFeature.defaultLabelFor(key, prefix);
        return custom != null && !custom.isBlank() && !custom.equals(def) ? custom + " (" + def + ")" : def;
    }

    /** {type, number}: 0 = Ender Chest, 1 = Backpack, 2 = unknown. */
    static int[] order(String key, String prefix) {
        String local = key.length() > prefix.length() + 1 ? key.substring(prefix.length() + 1) : key;
        if (local.startsWith("enderchest_")) {
            return new int[]{0, parseInt(local.substring("enderchest_".length()))};
        }
        if (local.startsWith("backpack_")) {
            return new int[]{1, parseInt(local.substring("backpack_".length()))};
        }
        return new int[]{2, 0};
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
