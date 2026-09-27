package com.killer560.hub.invsort;

import java.util.LinkedHashMap;
import java.util.Map;

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
 */
public final class InventoryLayout {

    private final String name;
    private final Map<Integer, String> slotIdentities;

    public InventoryLayout(String name, Map<Integer, String> slotIdentities) {
        this.name = name;
        this.slotIdentities = new LinkedHashMap<>(slotIdentities);
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
