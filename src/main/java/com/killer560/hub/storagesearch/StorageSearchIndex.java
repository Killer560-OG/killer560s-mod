package com.killer560.hub.storagesearch;

import com.killer560.hub.itemrarity.ItemRarity;
import com.killer560.hub.itemrarity.ItemRarityFeature;
import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A one-shot snapshot of every searchable item: each Storage Overlay cache entry for the CURRENT
 *  account/profile (read-only via {@link StorageOverlayCache}, decoded once per snapshot, not per
 *  keystroke), the wardrobe / equipment / pets pages this feature has cached ({@link StorageSearchExtraCache}),
 *  every island chest killer560 has opened ({@link IslandChestCache}) and - optionally - the player's live
 *  inventory, armor and offhand. Lower-cased name / Skyblock id / lore strings are precomputed so filtering on
 *  every keystroke is just substring checks. */
public final class StorageSearchIndex {

    public enum SourceType {
        ENDER_CHEST, BACKPACK, INVENTORY, ISLAND_CHEST, WARDROBE, PETS, EQUIPMENT;

        public boolean isStorage() {
            return this == ENDER_CHEST || this == BACKPACK;
        }

        public boolean isExtra() {
            return this == WARDROBE || this == PETS || this == EQUIPMENT;
        }
    }

    /** One searchable stack. {@code storageKey}/{@code storageNumber}/{@code contentIndex} are only
     *  meaningful for storages and the extra menus; {@code inventorySlot} only for {@link SourceType#INVENTORY};
     *  {@code chestPos} only for {@link SourceType#ISLAND_CHEST}. */
    public record Entry(ItemStack stack, String name, String nameLower, String idLower, String loreLower,
                        int rarityRgb, SourceType type, String storageKey, int storageNumber, int contentIndex,
                        int inventorySlot, String location, long updatedMs, boolean updatedIsUpperBound,
                        BlockPos chestPos) {
    }

    /** Summary of one storage for the "cache last updated" footer / never-opened warning. */
    public record StorageInfo(String key, String label, boolean hasContents, long updatedMs, boolean updatedIsUpperBound) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<StorageInfo> storages = new ArrayList<>();
    private int unopenedChests = 0;
    private int knownChests = 0;
    private boolean chunkCacheActive = false;

    private StorageSearchIndex() {
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<StorageInfo> storages() {
        return storages;
    }

    /** Chest blocks the scan could see that this feature has no contents for - "open it once" nudge. */
    public int unopenedChests() {
        return unopenedChests;
    }

    public int knownChests() {
        return knownChests;
    }

    /** False means island-chest discovery could only see vanilla's own chunk ring - see {@link IslandChestScanner}. */
    public boolean chunkCacheActive() {
        return chunkCacheActive;
    }

    public static StorageSearchIndex build(boolean includeInventory) {
        StorageSearchIndex index = new StorageSearchIndex();
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
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
                index.entries.add(makeEntry(stack, type, key, ord[1], i, -1, location, updated, upperBound, null));
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
                            inventoryLabel(i), now, false, null));
                }
            }
        }

        if (cfg.isSearchExtras()) {
            addExtras(index, prefix);
        }
        if (cfg.isSearchChests()) {
            addIslandChests(index, prefix, cfg);
        }
        return index;
    }

    /** Wardrobe / equipment / pets pages - killer560 (2026-09-21): "make sure that it scans the wardrobe,
     *  equipment wardrobe, and my pets for items". Hypixel pads those menus with glass panes and page arrows, so
     *  the obvious chrome is skipped here rather than at capture time (capturing the raw page keeps the slot
     *  indices honest, which is what the open-and-highlight jump needs). */
    private static void addExtras(StorageSearchIndex index, String prefix) {
        StorageSearchExtraCache extras = StorageSearchExtraCache.getInstance();
        for (String key : extras.keysFor(prefix + "|")) {
            List<ItemStack> contents = extras.get(key);
            if (contents == null) {
                continue;
            }
            String local = localPart(key, prefix);
            SourceType type = local.startsWith("pets") ? SourceType.PETS
                    : local.startsWith("wardrobe") ? SourceType.WARDROBE : SourceType.EQUIPMENT;
            String label = extraLabel(local);
            long updated = extras.updatedMs(key);
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (stack == null || stack.isEmpty() || isMenuChrome(stack)) {
                    continue;
                }
                index.entries.add(makeEntry(stack, type, key, pageNumber(local), i, -1,
                        label + " · slot " + (i + 1), updated, false, null));
            }
        }
    }

    /** Island chests: contents from {@link IslandChestCache} (only a chest that has been opened has any), and a
     *  chunk-cache-backed scan for the "you've never opened N of the chests around you" nudge. */
    private static void addIslandChests(StorageSearchIndex index, String prefix, StorageSearchConfig cfg) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        String world = worldId();
        IslandChestCache chestCache = IslandChestCache.getInstance();
        List<IslandChestCache.ChestEntry> remembered = chestCache.entriesFor(prefix, world);
        // A chest that has been broken since is a stale result that would ESP empty air - drop those first.
        List<String> missing = IslandChestScanner.scanForMissing(remembered);
        if (!missing.isEmpty()) {
            chestCache.forget(missing);
            remembered = chestCache.entriesFor(prefix, world);
        }
        index.chunkCacheActive = IslandChestScanner.chunkCacheActive();
        List<BlockPos> scanned = IslandChestScanner.scan(cfg.getChestRadius());
        index.knownChests = remembered.size();
        index.unopenedChests = IslandChestScanner.countUnopened(scanned, remembered);

        for (IslandChestCache.ChestEntry entry : remembered) {
            List<ItemStack> contents = chestCache.contentsOf(entry);
            if (contents == null) {
                continue;
            }
            BlockPos pos = entry.pos();
            String label = entry.label() + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                index.entries.add(makeEntry(stack, SourceType.ISLAND_CHEST, entry.key(), 0, i, -1,
                        label + " · slot " + (i + 1), entry.updatedMs(), false, pos));
            }
        }
    }

    /** Case-insensitive substring match over display name, Skyblock id, and (optionally) lore, then the source
     *  filter, then the sort order - killer560 (2026-09-21) asked to be able to "customize the viewer if you have
     *  multiple items to sort by in chests or in storage". A blank query matches everything. */
    public List<Entry> filter(String query, boolean searchLore, boolean includeInventory) {
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        StorageSearchConfig.SourceFilter source = cfg.getSourceFilter();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!includeInventory && e.type() == SourceType.INVENTORY) {
                continue;
            }
            if (!matchesSource(e.type(), source)) {
                continue;
            }
            if (q.isEmpty() || e.nameLower().contains(q) || e.idLower().contains(q)
                    || (searchLore && e.loreLower().contains(q))) {
                out.add(e);
            }
        }
        sort(out, cfg.getSortMode());
        return out;
    }

    private static boolean matchesSource(SourceType type, StorageSearchConfig.SourceFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case STORAGE -> type.isStorage();
            case CHESTS -> type == SourceType.ISLAND_CHEST;
            case INVENTORY -> type == SourceType.INVENTORY;
            case EQUIPMENT -> type.isExtra();
        };
    }

    private static void sort(List<Entry> list, StorageSearchConfig.SortMode mode) {
        switch (mode) {
            case DEFAULT -> {
                // Already in storage order (the build order above) - leave it alone.
            }
            case NAME -> list.sort(Comparator.comparing(Entry::nameLower));
            case COUNT -> list.sort(Comparator.comparingInt((Entry e) -> e.stack().getCount()).reversed());
            case RECENT -> list.sort(Comparator.comparingLong((Entry e) -> e.updatedMs()).reversed());
            case DISTANCE -> {
                var player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                // Chests sort by real distance; everything else keeps its order behind them, since "how far away"
                // is meaningless for a backpack.
                list.sort(Comparator.comparingDouble(e -> {
                    BlockPos pos = e.chestPos();
                    return pos == null ? Double.MAX_VALUE : player.blockPosition().distSqr(pos);
                }));
            }
        }
    }

    private static Entry makeEntry(ItemStack stack, SourceType type, String key, int number, int contentIndex,
                                   int invSlot, String location, long updated, boolean upperBound, BlockPos chestPos) {
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
                location, updated, upperBound, chestPos);
    }

    /** Hypixel's menu filler: the unnamed/decorative panes and the page arrows. Never hides a real item, since a
     *  real Skyblock item is never a plain stained-glass pane. */
    static boolean isMenuChrome(ItemStack stack) {
        var item = stack.getItem();
        if (item == Items.BLACK_STAINED_GLASS_PANE || item == Items.GRAY_STAINED_GLASS_PANE
                || item == Items.LIGHT_GRAY_STAINED_GLASS_PANE || item == Items.WHITE_STAINED_GLASS_PANE
                || item == Items.RED_STAINED_GLASS_PANE || item == Items.GLASS_PANE) {
            return true;
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return name.contains("go back") || name.contains("close") || name.contains("next page")
                || name.contains("previous page") || name.contains("empty slot");
    }

    private static String worldId() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? "unknown" : client.level.dimension().identifier().getPath();
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

    static String localPart(String key, String prefix) {
        return key.length() > prefix.length() + 1 ? key.substring(prefix.length() + 1) : key;
    }

    /** "wardrobe_2" -> "Wardrobe p2", "pets_1" -> "Pets p1", "equipment" -> "Equipment". */
    static String extraLabel(String local) {
        int underscore = local.indexOf('_');
        String base = underscore < 0 ? local : local.substring(0, underscore);
        String pretty = base.isEmpty() ? base : Character.toUpperCase(base.charAt(0)) + base.substring(1);
        int page = pageNumber(local);
        return page > 0 ? pretty + " p" + page : pretty;
    }

    static int pageNumber(String local) {
        int underscore = local.indexOf('_');
        if (underscore < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(local.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {type, number}: 0 = Ender Chest, 1 = Backpack, 2 = unknown. */
    static int[] order(String key, String prefix) {
        String local = localPart(key, prefix);
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
