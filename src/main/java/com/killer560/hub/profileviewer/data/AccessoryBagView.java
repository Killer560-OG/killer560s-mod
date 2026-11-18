package com.killer560.hub.profileviewer.data;

import com.killer560.hub.accadvisor.AccessoryAdvisor;
import com.killer560.hub.accadvisor.AccessoryRanker;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.util.ItemNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Profile Viewer's Accessories page laid out as SkyBlock 0.27.3's Accessory Bag menu: every accessory has its own
 * slot, the ones the player lacks are shown grayed, the ones toggled off are marked. The API's bag holds owned items only,
 * so the missing ones come from the Accessory Advisor's catalog: one slot per upgrade family with no owned member, shown
 * as its lowest tier. A toggled-off accessory carries {@code accessory_disabled} in its ExtraAttributes and still counts
 * for Networth. Built once per (bag, catalog, families, sort, tab) and cached; never per frame.
 */
public final class AccessoryBagView {
    public enum State { OWNED, DISABLED, MISSING }

    /** One bag slot: the stack to draw (a catalog icon for a missing one), its name and tier. */
    public record Slot(ItemStack stack, String id, String name, String tier, State state) {}

    public static final int PAGE = 45;
    public static final int SORT_RARITY = 0, SORT_NAME = 1, SORT_MISSING = 2;
    public static final String[] SORT_LABELS = {"Rarity", "Alphabetical", "Missing First"};

    private static Object keyBag, keyCatalog;
    private static int keyFamilies = -1;
    private static boolean keyRift;
    private static int keySort = -1;
    private static List<Slot> cached = List.of();

    private AccessoryBagView() {}

    /** The slots for the main bag ({@code rift} false) or the Rift Accessory Bag, in the given SORT_ order. */
    public static List<Slot> slots(List<ItemStack> owned, boolean rift, int sort) {
        Map<String, AccessoryRanker.Acc> catalog = AccessoryAdvisor.catalogView();
        int families = AccessoryAdvisor.familiesVersion();
        if (owned == keyBag && catalog == keyCatalog && families == keyFamilies && rift == keyRift && sort == keySort) {
            return cached;
        }
        cached = build(owned, catalog, rift, sort);
        keyBag = owned;
        keyCatalog = catalog;
        keyFamilies = families;
        keyRift = rift;
        keySort = sort;
        return cached;
    }

    static List<Slot> build(List<ItemStack> owned, Map<String, AccessoryRanker.Acc> catalog, boolean rift, int sort) {
        List<Slot> out = new ArrayList<>();
        Set<String> ownedFamilies = new HashSet<>();
        if (owned != null) {
            for (ItemStack s : owned) {
                if (s == null || s.isEmpty()) continue;
                CompoundTag tag = ItemNbt.view(s);
                String id = tag == null ? null : tag.getStringOr("id", null);
                if (id != null) ownedFamilies.add(AccessoryAdvisor.familyKey(id));
                boolean off = tag != null && tag.getIntOr("accessory_disabled", 0) != 0
                        || com.killer560.hub.autopuzzles.AutoPuzzleUtil.loreContains(s, "Enabled: Off");
                String tier = tierOf(id);
                if (tag != null && tag.getIntOr("rarity_upgrades", 0) > 0) tier = bump(tier);
                out.add(new Slot(s, id, s.getHoverName().getString(), tier, off ? State.DISABLED : State.OWNED));
            }
        }
        // One missing slot per family nobody owns: its lowest tier, then by name.
        Map<String, AccessoryRanker.Acc> missing = new java.util.HashMap<>();
        for (AccessoryRanker.Acc a : catalog.values()) {
            if (AccessoryAdvisor.isIgnored(a.id()) || isRift(a.id()) != rift) continue;
            String fam = AccessoryAdvisor.familyKey(a.id());
            if (ownedFamilies.contains(fam)) continue;
            AccessoryRanker.Acc best = missing.get(fam);
            if (best == null || LOWEST.compare(a, best) < 0) missing.put(fam, a);
        }
        for (AccessoryRanker.Acc a : missing.values()) {
            out.add(new Slot(ItemIcons.catalogIcon(a.id()), a.id(), a.name(), a.tier(), State.MISSING));
        }
        // The 0.27.3 menu (capture 11-17): missing accessories sit among the owned ones inside their rarity group, each
        // group alphabetical; Missing First puts them ahead of the owned.
        Comparator<Slot> byNameCmp = Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT));
        Comparator<Slot> byRarity = Comparator.<Slot>comparingInt(s -> -LevelTables.rarityIndex(s.tier())).thenComparing(byNameCmp);
        out.sort(sort == SORT_NAME ? byNameCmp
                : sort == SORT_MISSING ? Comparator.<Slot>comparingInt(s -> s.state() == State.MISSING ? 0 : 1).thenComparing(byRarity)
                : byRarity);
        return List.copyOf(out);
    }

    private static final Comparator<AccessoryRanker.Acc> LOWEST = Comparator
            .<AccessoryRanker.Acc>comparingInt(a -> LevelTables.rarityIndex(a.tier()))
            .thenComparing(a -> a.name().toLowerCase(Locale.ROOT));

    private static boolean isRift(String id) {
        PriceData.ItemInfo info = PriceData.item(id);
        return info != null && info.accessory() != null && info.accessory().rift();
    }

    private static String tierOf(String id) {
        PriceData.ItemInfo info = PriceData.item(id);
        return info == null || info.accessory() == null ? "COMMON" : info.accessory().tier();
    }

    /** A recombobulated accessory reads one rarity higher. */
    private static String bump(String tier) {
        int i = LevelTables.rarityIndex(tier);
        return LevelTables.RARITIES.get(Math.min(i + 1, LevelTables.RARITIES.size() - 1));
    }

    /** Counts per state, for the legend line. */
    public static int[] counts(List<Slot> slots) {
        int[] c = new int[3];
        for (Slot s : slots) c[s.state().ordinal()]++;
        return c;
    }
}
