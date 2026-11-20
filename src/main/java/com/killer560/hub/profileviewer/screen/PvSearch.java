package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.storagesearch.StorageSearchIndex;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Inventories tab's search across every container of one profile, on the Storage Search's own matcher
 *  ({@link StorageSearchIndex#matches}): the containers are indexed once per decoded inventory, the entry of a stack is
 *  built once, and a query only counts matches per (view, page). */
final class PvSearch {
    /** View indexes in the Inventories tab. */
    static final int INVENTORY = 0, ENDER = 1, BACKPACKS = 2, WARDROBE = 3, ACCESSORIES = 4, VAULT = 5, QUIVER = 6,
            FISHING = 7, POTION = 8, SACKS = 9;

    private String query = "";
    private SbProfile.Inventories indexed;
    private Map<String, Long> sacks = Map.of();
    /** (view * 1000 + page) -> the stacks of that container. */
    private final Map<Integer, List<ItemStack>> containers = new HashMap<>();
    private final IdentityHashMap<ItemStack, StorageSearchIndex.Entry> entries = new IdentityHashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final java.util.Set<String> sackHit = new java.util.HashSet<>();

    String query() { return query; }

    boolean active() { return !query.isEmpty(); }

    void setQuery(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (q.equals(query)) return;
        query = q;
        recount();
    }

    /** Index the containers of {@code inv} (and the sack names) once; a different object re-indexes. */
    void index(SbProfile.Inventories inv, Map<String, Long> sackCounts) {
        Map<String, Long> s = sackCounts == null ? Map.of() : sackCounts;
        if (inv == indexed && s == sacks) return;
        indexed = inv;
        sacks = s;
        containers.clear();
        entries.clear();
        if (inv == null) {
            recount();
            return;
        }
        List<ItemStack> main = new java.util.ArrayList<>(inv.inventory());
        main.addAll(inv.armor());
        main.addAll(inv.equipment());
        put(INVENTORY, 0, main);
        putPages(ENDER, inv.enderChest());
        for (int i = 0; i < inv.backpacks().size(); i++) put(BACKPACKS, i, inv.backpacks().get(i).items());
        putPages(WARDROBE, inv.wardrobe());
        List<ItemStack> acc = new java.util.ArrayList<>();
        for (List<ItemStack> page : inv.accessories()) acc.addAll(page);
        put(ACCESSORIES, 0, acc);
        put(VAULT, 0, inv.personalVault());
        put(QUIVER, 0, inv.quiver());
        put(FISHING, 0, inv.fishingBag());
        put(POTION, 0, inv.potionBag());
        recount();
    }

    private void putPages(int view, List<List<ItemStack>> pages) {
        for (int i = 0; i < pages.size(); i++) put(view, i, pages.get(i));
    }

    private void put(int view, int page, List<ItemStack> items) {
        containers.put(view * 1000 + page, items);
    }

    private StorageSearchIndex.Entry entry(ItemStack s) {
        return entries.computeIfAbsent(s, StorageSearchIndex::entryFor);
    }

    boolean matches(ItemStack s) {
        return !s.isEmpty() && (query.isEmpty() || StorageSearchIndex.matches(entry(s), query, true));
    }

    /** A stack drawn dim: a query is on and it does not match. */
    boolean dims(ItemStack s) {
        return active() && !s.isEmpty() && !matches(s);
    }

    /** A sack row (its id) matches by id or by the printed name, like any other item. */
    boolean matchesSack(String id) {
        return query.isEmpty() || sackHit.contains(id);
    }

    private boolean sackMatch(String id) {
        return StorageSearchIndex.matches(entry(ItemIcons.forId(id)), query, true)
                || SbProfile.titleCase(id).toLowerCase(Locale.ROOT).contains(query);
    }

    private void recount() {
        counts.clear();
        sackHit.clear();
        if (!active()) return;
        for (Map.Entry<Integer, List<ItemStack>> e : containers.entrySet()) {
            int n = 0;
            for (ItemStack s : e.getValue()) if (matches(s)) n++;
            if (n > 0) counts.put(e.getKey(), n);
        }
        for (Map.Entry<String, Long> e : sacks.entrySet()) {
            if (!e.getKey().equals("total") && e.getValue() > 0 && sackMatch(e.getKey())) sackHit.add(e.getKey());
        }
    }

    /** Matches on one page of a view (0 for a view without pages). */
    int count(int view, int page) {
        return view == SACKS ? sackHit.size() : counts.getOrDefault(view * 1000 + page, 0);
    }

    /** Matches in the whole view. */
    int count(int view) {
        if (view == SACKS) return sackHit.size();
        int n = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) if (e.getKey() / 1000 == view) n += e.getValue();
        return n;
    }
}
