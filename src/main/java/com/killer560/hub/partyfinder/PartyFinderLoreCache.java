package com.killer560.hub.partyfinder;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lore the SERVER sent for each Party Finder slot, kept as the server sent it.
 *
 * <p>Why this exists. Party Finder's parser reads the head's lore to find who is in a party, what class they
 * are and their personal best. Another mod on killer560's client - Devonian's Party Finder Overview - restyles
 * that same lore every tick, rewriting the lines in place on the client's own {@link ItemStack}. Reading the
 * live stack therefore reads whatever that mod last wrote, which is why this overlay went blank with it
 * installed: no members, "Missing:" listing every class, and no personal bests, with nothing wrong in this
 * mod's own logic (found 2026-09-21).
 *
 * <p>Loosening the regexes bought some of it back, but it is the wrong fight: any mod may rewrite a line in any
 * way at any time, and a parser cannot be made proof against all of them. The server's own copy cannot be
 * rewritten by anybody, because it arrives in a packet and is recorded before a single tick of client code has
 * run against it. So that is what gets parsed.
 *
 * <p>Deliberately not a general-purpose cache. It holds one container's worth of lore, replaced wholesale when a
 * new container's contents arrive, so it cannot grow and cannot serve a stale menu's text to a new menu. A slot
 * this has never seen simply falls back to the live stack, which is no worse than the old behaviour.
 */
public final class PartyFinderLoreCache {

    private static int containerId = -1;
    private static final Map<Integer, List<String>> BY_SLOT = new HashMap<>();

    private PartyFinderLoreCache() {
    }

    /** A whole container's contents arrived: forget the previous menu and record this one. */
    public static synchronized void onContent(int id, List<ItemStack> items) {
        if (id != containerId) {
            containerId = id;
            BY_SLOT.clear();
        }
        for (int slot = 0; slot < items.size(); slot++) {
            put(slot, items.get(slot));
        }
    }

    /** One slot changed. Ignored for a container this has no record of, so a stale id cannot poison a new menu. */
    public static synchronized void onSlot(int id, int slot, ItemStack stack) {
        if (id != containerId) {
            containerId = id;
            BY_SLOT.clear();
        }
        put(slot, stack);
    }

    private static void put(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            BY_SLOT.remove(slot);
            return;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            BY_SLOT.remove(slot);
            return;
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        lore.lines().forEach(line -> lines.add(line.getString()));
        BY_SLOT.put(slot, List.copyOf(lines));
    }

    /**
     * The server's lore for a slot, or null when there is none recorded.
     *
     * @param slot the slot index within the open menu; a negative slot always returns null, which is how the
     *             single-stack callers (a hovered item, with no slot of its own) opt out
     */
    public static synchronized List<String> serverLore(int slot) {
        return slot < 0 ? null : BY_SLOT.get(slot);
    }

    /** Called when a container closes, so nothing survives into the next menu. */
    public static synchronized void clear() {
        containerId = -1;
        BY_SLOT.clear();
    }
}
