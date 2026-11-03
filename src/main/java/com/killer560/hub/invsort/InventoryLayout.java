package com.killer560.hub.invsort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One saved layout: a snapshot of "which item belongs in which inventory slot" (killer560: "I should be able to
 * save item locations in my inventory and have them sorted there"). Slot numbering is
 * {@link net.minecraft.world.entity.player.Inventory}'s own item index, 0-35 (0-8 hotbar, 9-35 main storage) - the
 * same numbering {@code autoroutes/ItemIdentity} already reads hotbar slots with, not a container-screen index
 * (those shift depending on which menu is open; see {@link InventorySorterExecutor#toContainerSlot}).
 * <p>
 * Only slots that actually held an item when the layout was saved are recorded - an empty slot at save time isn't
 * "the item that belongs here is nothing", it's just not part of the layout, so applying it never tries to empty
 * a slot the layout doesn't mention. Order is insertion order (the order slots were scanned when saved, 0..35),
 * which is also the order the executor tries to satisfy them in - not that it matters for correctness, but it
 * makes a hand-edited file's earlier entries settle first.
 * <p>
 * Since 2026-11-03 a slot may also carry an EXACT key ({@link InvSortMatch}): that copy of the item and no other. And a
 * layout may name FREE slots (killer560, 2026-11-02: leave chosen items out of a layout): whatever is in one when the
 * layout is applied stays there - never claimed for another slot, never moved, never put away.
 */
public final class InventoryLayout {

    /** Inventory index 8, the bottom-right hotbar slot (killer560, 2026-11-03): the Skyblock Menu, dungeon map or arrows, which
     *  can never be removed, so the sorter treats it as always Free: never a source, a target, fetched into or put away. */
    public static final int LOCKED_SLOT = 8;

    private final String name;
    private final Map<Integer, String> slotIdentities;
    /** Slot -&gt; vanilla item id ({@code minecraft:diamond_sword}) of the item saved there, so the editor can draw
     *  the layout when that item is not in the inventory right now. Optional; a hand-written file can leave it out. */
    private final Map<Integer, String> slotIcons;
    /** Slot -&gt; exact key ({@link InvSortMatch#exact}) of the very item saved there. Optional: a slot without one (any
     *  layout saved before 2026-11-03) matches any copy of its id. */
    private final Map<Integer, String> slotExact;
    /** Slots the layout leaves alone (see the class doc). */
    private final Set<Integer> freeSlots;
    /** Storage mode (killer560, 2026-11-01): before sorting, take the layout's missing items out of storage (Ender
     *  Chest pages and backpacks the Storage Overlay has seen). */
    private final boolean fetchFromStorage;
    /** Storage mode's dump page ({@code enderchest_3}, {@code backpack_5}): items in the inventory that the layout does
     *  not use are shift-clicked into it first. Null for none. */
    private final String dumpPage;

    public InventoryLayout(String name, Map<Integer, String> slotIdentities) {
        this(name, slotIdentities, Map.of());
    }

    public InventoryLayout(String name, Map<Integer, String> slotIdentities, Map<Integer, String> slotIcons) {
        this(name, slotIdentities, slotIcons, false, null);
    }

    public InventoryLayout(String name, Map<Integer, String> slotIdentities, Map<Integer, String> slotIcons,
                           boolean fetchFromStorage, String dumpPage) {
        this(name, slotIdentities, slotIcons, Map.of(), Set.of(), fetchFromStorage, dumpPage);
    }

    public InventoryLayout(String name, Map<Integer, String> slotIdentities, Map<Integer, String> slotIcons,
                           Map<Integer, String> slotExact, Set<Integer> freeSlots, boolean fetchFromStorage,
                           String dumpPage) {
        this.name = name;
        this.slotIdentities = new LinkedHashMap<>(slotIdentities);
        this.slotIdentities.remove(LOCKED_SLOT); // the last hotbar slot is Hypixel's locked item: no layout holds it
        this.slotIcons = new LinkedHashMap<>(slotIcons);
        this.slotIcons.remove(LOCKED_SLOT);
        this.slotExact = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : slotExact.entrySet()) {
            // Only for a slot the layout has, and only when it is that slot's item.
            String id = this.slotIdentities.get(e.getKey());
            if (id != null && e.getValue() != null && InvSortMatch.isExact(e.getValue())
                    && InvSortMatch.id(e.getValue()).equalsIgnoreCase(id)) {
                this.slotExact.put(e.getKey(), e.getValue());
            }
        }
        this.freeSlots = new TreeSet<>();
        for (int s : freeSlots) {
            if (s >= 0 && s < 36 && s != LOCKED_SLOT && !this.slotIdentities.containsKey(s)) {
                this.freeSlots.add(s);
            }
        }
        this.fetchFromStorage = fetchFromStorage;
        this.dumpPage = dumpPage == null || dumpPage.isBlank() ? null : dumpPage;
    }

    /** The same slots under another name. */
    public InventoryLayout renamed(String newName) {
        return new InventoryLayout(newName, slotIdentities, slotIcons, slotExact, freeSlots, fetchFromStorage, dumpPage);
    }

    /** The same slots with other storage-mode settings. */
    public InventoryLayout withStorage(boolean fetch, String dump) {
        return new InventoryLayout(name, slotIdentities, slotIcons, slotExact, freeSlots, fetch, dump);
    }

    /** The same layout with these free slots (slots it also manages are dropped). */
    public InventoryLayout withFree(Set<Integer> free) {
        return new InventoryLayout(name, slotIdentities, slotIcons, slotExact, free, fetchFromStorage, dumpPage);
    }

    public boolean fetchFromStorage() {
        return fetchFromStorage;
    }

    /** The dump page's local id ({@code enderchest_3} / {@code backpack_5}), or null. */
    public String dumpPage() {
        return dumpPage;
    }

    /** Whether applying it visits storage first. */
    public boolean usesStorage() {
        return fetchFromStorage || dumpPage != null;
    }

    /** The vanilla item id saved for {@code slot}'s icon, or null. */
    public String iconAt(int slot) {
        return slotIcons.get(slot);
    }

    public Map<Integer, String> icons() {
        return java.util.Collections.unmodifiableMap(slotIcons);
    }

    /** Unmodifiable view, slot -&gt; exact key, for the slots that have one. */
    public Map<Integer, String> exactKeys() {
        return java.util.Collections.unmodifiableMap(slotExact);
    }

    /** Slot -&gt; what must be there: the exact key where the slot has one, else its id. What every matcher uses. */
    public Map<Integer, String> wants() {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : slotIdentities.entrySet()) {
            out.put(e.getKey(), slotExact.getOrDefault(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** The exact key, else the id, for {@code slot}; null when the layout does not manage it. */
    public String wantAt(int slot) {
        String exact = slotExact.get(slot);
        return exact != null ? exact : slotIdentities.get(slot);
    }

    /** Slots left alone, in order. */
    public Set<Integer> freeSlots() {
        return java.util.Collections.unmodifiableSet(freeSlots);
    }

    public boolean isFree(int slot) {
        return slot == LOCKED_SLOT || freeSlots.contains(slot);
    }

    public String name() {
        return name;
    }

    /** Unmodifiable view, slot -&gt; Skyblock (or name-fallback) identity, in save order. */
    public Map<Integer, String> entries() {
        return java.util.Collections.unmodifiableMap(slotIdentities);
    }

    /** The identity this layout wants at {@code slot}, or null if the layout doesn't manage that slot. */
    public String identityAt(int slot) {
        return slotIdentities.get(slot);
    }

    public int size() {
        return slotIdentities.size();
    }

    public boolean isEmpty() {
        return slotIdentities.isEmpty();
    }
}
