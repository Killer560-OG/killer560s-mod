package com.killer560.hub.invsort;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;

import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The layout's storage mode (killer560, 2026-11-01: "allow a mode for each layout where it can go through your storage
 * and whatnot and get items that you dont have in your inventory and put them there, and where you can select a chest
 * to put items that you have in your inventory that were not in that saved loadout").
 * <p>
 * Runs before the sort, one storage page at a time, the same way {@code storagesearch/StorageScanAll} visits pages: send
 * {@code /enderchest N} or {@code /backpack N}, wait for that page's own title, let its contents arrive, shift-click,
 * close. First the dump page (if the layout has one): every inventory stack the layout does not use is shift-clicked into
 * it (never the SkyBlock Menu; Item Protect does not keep an item out of storage). Then, for every item the layout
 * wants that is not in the inventory, the pages the Storage Overlay has seen it in. The cache only chooses WHICH page to
 * open: what is clicked is read from the live page, so a stale cache costs a visit, never a wrong click. Slots 0-8 of a
 * page are Hypixel's chrome and are never clicked.
 * <p>
 * Shift-click is the one click that moves a stack between the page and the inventory: the alternative (pick up, click
 * a slot in the other half) is two clicks for the same move, and neither half's slot order matters here because the
 * sort that follows puts every stack where it belongs.
 */
final class InvSortStorage {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    static final String SKYBLOCK_MENU = "SKYBLOCK_MENU";
    private static final int OPEN_TIMEOUT_TICKS = 60;
    /** Ticks after the page's title shows before the first click: its items arrive in the packets after it. */
    private static final int SETTLE_TICKS = 6;
    private static final int AFTER_CLOSE_TICKS = 6;

    enum Result { RUNNING, DONE, FAILED }

    private record Visit(String page, boolean dump, Map<String, Integer> fetch) {
    }

    private enum Phase { SEND, WAIT_OPEN, SETTLE, CLICK, AFTER_CLOSE }

    private final InventoryLayout layout;
    private final List<Visit> visits = new ArrayList<>();
    private Visit visit;
    /** Inventory slots already shift-clicked on this page: a late full-state packet from the server can show a moved
     *  stack back for a tick, and clicking it again sends a second click on an empty slot (26.2 run c262, 2026-11-01). */
    private final java.util.Set<Integer> clickedThisPage = new java.util.HashSet<>();
    /** Page slots already shift-clicked out on this page, for the same reason. */
    private final java.util.Set<Integer> clickedPageSlots = new java.util.HashSet<>();
    private Phase phase = Phase.SEND;
    private int ticks;
    private String failure;
    private int fetched;
    private int dumped;
    private int clicks;
    /** Identities still missing after every known page was planned (told once at the end). */
    private final Map<String, Integer> notFound = new LinkedHashMap<>();

    InvSortStorage(InventoryLayout layout) {
        this.layout = layout;
    }

    String failure() {
        return failure;
    }

    int fetched() {
        return fetched;
    }

    int dumped() {
        return dumped;
    }

    /** True when the dump page ran out of room with items still to put away (told once, with the count). */
    boolean dumpFull() {
        return dumpFull;
    }

    private boolean dumpFull;

    int clicks() {
        return clicks;
    }

    Map<String, Integer> notFound() {
        return notFound;
    }

    /** Builds the page visits from the live inventory and the Storage Overlay's cache. */
    void plan(Minecraft client) {
        visits.clear();
        notFound.clear();
        Inventory inv = client.player.getInventory();
        // What the layout wants that the inventory does not have, matched by exact item (InvSortMatch): with two
        // runebooks, the one the layout was saved with (killer560, 2026-11-02: it fetched the wrong one).
        // By ITEM COUNT (invsortgfs-1116): the layout's saved amount per slot minus what the inventory holds, so several
        // partial storage stacks are combined and an inventory that holds only part of an item still visits storage.
        Map<String, Integer> missing = layout.fetchFromStorage()
                ? new LinkedHashMap<>(InvSortSacks.lacking(layout, inv)) : new LinkedHashMap<>();
        boolean dump = layout.dumpPage() != null && !dumpSlots(client).isEmpty();
        // Which pages hold the missing items: the dump page first (it is visited anyway), then Ender Chest pages, then
        // backpacks, in number order.
        Map<String, Map<String, Integer>> byPage = new LinkedHashMap<>();
        if (!missing.isEmpty()) {
            String prefix = StorageOverlayFeature.accountProfilePrefix();
            StorageOverlayCache cache = StorageOverlayCache.getInstance();
            List<String> pages = new ArrayList<>();
            for (String k : cache.knownKeysFor(prefix)) {
                String local = k.substring(k.lastIndexOf('|') + 1);
                if (InventoryLayoutStore.DUMP_PAGE.matcher(local).matches()) {
                    pages.add(local);
                }
            }
            pages.sort((a, b) -> Integer.compare(order(a), order(b)));
            if (layout.dumpPage() != null && pages.remove(layout.dumpPage())) {
                pages.add(0, layout.dumpPage());
            }
            for (String page : pages) {
                List<ItemStack> items = cache.peek(prefix + "|" + page);
                if (items == null) {
                    continue;
                }
                for (ItemStack stack : items) {
                    String want = pick(missing, InvSortMatch.exact(stack));
                    if (want == null) {
                        continue;
                    }
                    int take = Math.min(stack.getCount(), missing.get(want));
                    byPage.computeIfAbsent(page, p -> new LinkedHashMap<>()).merge(want, take, Integer::sum);
                    missing.merge(want, -take, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : missing.entrySet()) {
                if (e.getValue() > 0) {
                    notFound.merge(InvSortMatch.id(e.getKey()), e.getValue(), Integer::sum);
                }
            }
        }
        if (dump) {
            visits.add(new Visit(layout.dumpPage(), true, byPage.getOrDefault(layout.dumpPage(), new LinkedHashMap<>())));
            byPage.remove(layout.dumpPage());
        }
        for (Map.Entry<String, Map<String, Integer>> e : byPage.entrySet()) {
            visits.add(new Visit(e.getKey(), false, e.getValue()));
        }
        LOGGER.info("[InvSort] Storage plan for {}: {} page visit(s) {}, not in any known page {}", layout.name(),
                visits.size(), visits.stream().map(v -> v.page() + (v.dump() ? "(dump)" : "") + v.fetch()).toList(),
                notFound);
    }

    boolean hasWork() {
        return !visits.isEmpty();
    }

    /**
     * The want in {@code needs} (want key -&gt; copies still needed) that the stack with exact key {@code have} fills, or
     * null: the want naming that very item first, else an older id-only want for it.
     */
    static String pick(Map<String, Integer> needs, String have) {
        if (have == null) {
            return null;
        }
        String loose = null;
        for (Map.Entry<String, Integer> e : needs.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (e.getKey().equalsIgnoreCase(have)) {
                return e.getKey();
            }
            if (loose == null && InvSortMatch.same(have, e.getKey())) {
                loose = e.getKey();
            }
        }
        return loose;
    }

    /**
     * Which inventory slots the layout's wants claim: a slot already holding what it wants keeps it, then every other
     * want takes a free copy (the very item before any copy of its id). Free slots are never claimed, so what is in one
     * neither counts as had nor is put away.
     * @param unmet filled with the wants nothing in the inventory meets (may be null)
     */
    static boolean[] claim(Inventory inv, InventoryLayout layout, List<String> unmet) {
        boolean[] claimed = new boolean[36];
        String[] have = new String[36];
        for (int i = 0; i < 36; i++) {
            have[i] = layout.isFree(i) ? null : InvSortMatch.exact(inv.getItem(i));
        }
        List<String> rest = new ArrayList<>();
        for (Map.Entry<Integer, String> e : layout.wants().entrySet()) {
            int s = e.getKey();
            if (s >= 0 && s < 36 && InvSortMatch.same(have[s], e.getValue())) {
                claimed[s] = true;
            } else {
                rest.add(e.getValue());
            }
        }
        // Exact wants first, so an id-only want never takes the one copy an exact want needs.
        rest.sort((a, b) -> Boolean.compare(!InvSortMatch.isExact(a), !InvSortMatch.isExact(b)));
        for (String w : rest) {
            int found = -1;
            for (int i = 0; i < 36 && found < 0; i++) {
                if (!claimed[i] && have[i] != null && have[i].equalsIgnoreCase(w)) {
                    found = i;
                }
            }
            for (int i = 0; i < 36 && found < 0; i++) {
                if (!claimed[i] && InvSortMatch.same(have[i], w)) {
                    found = i;
                }
            }
            if (found >= 0) {
                claimed[found] = true;
            } else if (unmet != null) {
                unmet.add(w);
            }
        }
        return claimed;
    }

    private static int order(String local) {
        int us = local.indexOf('_');
        int num;
        try {
            num = Integer.parseInt(local.substring(us + 1));
        } catch (NumberFormatException e) {
            num = 99;
        }
        return (local.startsWith("enderchest_") ? 0 : 100) + num;
    }

    /** Inventory slots holding a stack the layout does not use (beyond the copies it does use), that may be moved. */
    private List<Integer> dumpSlots(Minecraft client) {
        Inventory inv = client.player.getInventory();
        boolean[] kept = claim(inv, layout, null);
        Map<String, Integer> lack = InvSortSacks.lacking(layout, inv);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            String id = ItemIdentity.of(stack);
            // Item Protect / Lock In Place do not keep an item out of storage here (killer560, 2026-11-03: "The item lock
            // shouldnt apply to this"): storage is not losing it, and the sorter's own clicks never go through slotClicked.
            if (kept[i] || layout.isFree(i) || stack.isEmpty() || id == null || SKYBLOCK_MENU.equalsIgnoreCase(id)
                    || lack.keySet().stream().anyMatch(k -> InvSortMatch.matches(stack, k))) {
                continue; // still short of that item: a second partial stack is kept, not put away and fetched again
            }
            out.add(i);
        }
        return out;
    }

    /** One client tick (START). {@code mayClick} is the executor's pacing and still-input verdict for this tick. */
    Result tick(Minecraft client, boolean mayClick) {
        if (visit == null) {
            if (visits.isEmpty()) {
                return Result.DONE;
            }
            visit = visits.remove(0);
            clickedThisPage.clear();
            clickedPageSlots.clear();
            phase = Phase.SEND;
            ticks = 0;
        }
        ticks++;
        Screen screen = McCompat.screen(client);
        switch (phase) {
            case SEND -> {
                if (screen != null) {
                    return fail("close the open menu first");
                }
                String command = command(visit.page());
                client.player.connection.sendCommand(command);
                ModChat.log(InventorySorterCommands.FEATURE, ModChat.text("Opening /" + command + " for " + layout.name()));
                LOGGER.info("[InvSort] opening /{} (dump={}, fetch={})", command, visit.dump(), visit.fetch());
                phase = Phase.WAIT_OPEN;
                ticks = 0;
            }
            case WAIT_OPEN -> {
                if (screen instanceof AbstractContainerScreen<?> acs && isPage(acs, visit.page())) {
                    phase = Phase.SETTLE;
                    ticks = 0;
                } else if (ticks > OPEN_TIMEOUT_TICKS) {
                    return fail("/" + command(visit.page()) + " did not open (is that page unlocked?)");
                }
            }
            case SETTLE -> {
                if (!(screen instanceof AbstractContainerScreen<?> acs) || !isPage(acs, visit.page())) {
                    return fail("the storage page closed");
                }
                if (ticks >= SETTLE_TICKS) {
                    phase = Phase.CLICK;
                    ticks = 0;
                }
            }
            case CLICK -> {
                if (!(screen instanceof AbstractContainerScreen<?> acs) || !isPage(acs, visit.page())) {
                    return fail("the storage page closed");
                }
                if (!mayClick) {
                    return Result.RUNNING;
                }
                int[] next = nextClick(client, acs.getMenu());
                if (next == null) {
                    client.player.closeContainer();
                    phase = Phase.AFTER_CLOSE;
                    ticks = 0;
                    return Result.RUNNING;
                }
                if (!ActionGate.tryAct(ActionGate.Actor.INVENTORY_SORTER, acs)) {
                    return Result.RUNNING;
                }
                if (next.length > 2) { // a cursor click of an excess split
                    client.gameMode.handleContainerInput(acs.getMenu().containerId, next[0], next[2], ContainerInput.PICKUP,
                            client.player);
                    clicks++;
                    pending.poll();
                    InventorySorterExecutor.paced();
                    return Result.RUNNING;
                }
                client.gameMode.handleContainerInput(acs.getMenu().containerId, next[0], 0, ContainerInput.QUICK_MOVE,
                        client.player);
                clicks++;
                if (next[1] == 1) {
                    dumped++;
                } else {
                    fetched++;
                }
                InventorySorterExecutor.paced();
            }
            case AFTER_CLOSE -> {
                if (ticks >= AFTER_CLOSE_TICKS && McCompat.screen(client) == null) {
                    visit = null;
                }
            }
            default -> {
            }
        }
        return Result.RUNNING;
    }

    /** {menu slot index, 1 = dump / 0 = fetch}, or null when this page is done. */
    private int[] nextClick(Minecraft client, AbstractContainerMenu menu) {
        int pageSlots = Math.max(0, menu.slots.size() - 36);
        Inventory inv = client.player.getInventory();
        if (!pending.isEmpty()) {
            return pending.peek();
        }
        if (visit.dump()) {
            boolean room = false;
            for (int i = 9; i < pageSlots; i++) {
                if (menu.slots.get(i).getItem().isEmpty()) {
                    room = true;
                    break;
                }
            }
            if (room) {
                for (int s : dumpSlots(client)) {
                    Slot slot = clickedThisPage.contains(s) ? null : playerSlot(menu, inv, s);
                    if (slot != null) {
                        clickedThisPage.add(s);
                        return new int[]{slot.index, 1};
                    }
                }
            } else if (!dumpFull) {
                int left = dumpSlots(client).size();
                if (left > 0) {
                    dumpFull = true;
                    ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("The page " + label(layout.dumpPage())
                            + " is full: "), ModChat.text(left + " item" + (left == 1 ? "" : "s")
                            + " could not be put away and stay in your inventory."));
                }
            }
        }
        if (visit.fetch().values().stream().anyMatch(v -> v > 0) && hasEmptySlot(inv)) {
            int best = -1;
            String bestWant = null;
            int bestKey = 0;
            for (int i = 9; i < pageSlots; i++) {
                if (clickedPageSlots.contains(i)) {
                    continue; // already taken: a late packet can show it back for a tick
                }
                // The live page decides, by exact item: the cache only chose which page to open. A stack that fits the
                // rest of the wanted count is taken first (largest first), else the smallest one that overshoots it.
                ItemStack st = menu.slots.get(i).getItem();
                String want = pick(visit.fetch(), InvSortMatch.exact(st));
                if (want != null) {
                    int key = st.getCount() <= visit.fetch().get(want) ? 1000 + st.getCount() : 1000 - st.getCount();
                    if (best < 0 || key > bestKey) {
                        best = i;
                        bestWant = want;
                        bestKey = key;
                    }
                }
            }
            if (best >= 0) {
                int c = menu.slots.get(best).getItem().getCount();
                int r = visit.fetch().get(bestWant);
                clickedPageSlots.add(best);
                visit.fetch().merge(bestWant, -Math.min(c, r), Integer::sum);
                if (c > r && queueExcess(menu, inv, best, c, r)) {
                    fetched++;
                    return pending.peek();
                }
                return new int[]{best, 0};
            }
        }
        for (Map.Entry<String, Integer> e : visit.fetch().entrySet()) {
            if (e.getValue() > 0) {
                notFound.merge(InvSortMatch.id(e.getKey()), e.getValue(), Integer::sum);
                e.setValue(0);
            }
        }
        return null;
    }

    /** Clicks still to send for a stack that holds more than wanted: {menu slot, 0, mouse button}, all PICKUP. */
    private final java.util.ArrayDeque<int[]> pending = new java.util.ArrayDeque<>();

    /**
     * Take exactly {@code r} of the {@code c} in page slot {@code p} and leave the excess on the page (invsortgfs-1116).
     * With the stack on the cursor, a right click on an empty inventory slot sets one down: r of them, then the rest goes
     * back to the page slot (r + 2 clicks); when the excess is the smaller number it goes back one by one instead
     * (excess + 4 clicks: all into the inventory slot, pick it up again, excess right clicks on the page slot, the rest back).
     * @return false when no inventory slot is empty to work in (the caller shift-clicks it all)
     */
    private boolean queueExcess(AbstractContainerMenu menu, Inventory inv, int p, int c, int r) {
        int d = -1;
        for (int i = 9; i < 44 && d < 0; i++) {
            int inv9 = i % 36; // 9..35 first, then the hotbar 0..7 (8 is the locked slot)
            if (inv9 != 8 && inv.getItem(inv9).isEmpty() && playerSlot(menu, inv, inv9) != null) {
                d = playerSlot(menu, inv, inv9).index;
            }
        }
        if (d < 0) {
            return false;
        }
        int excess = c - r;
        pending.add(new int[]{p, 0, 0});
        if (r + 2 <= excess + 4) {
            for (int k = 0; k < r; k++) {
                pending.add(new int[]{d, 0, 1});
            }
            pending.add(new int[]{p, 0, 0});
        } else {
            pending.add(new int[]{d, 0, 0});
            pending.add(new int[]{d, 0, 0});
            for (int k = 0; k < excess; k++) {
                pending.add(new int[]{p, 0, 1});
            }
            pending.add(new int[]{d, 0, 0});
        }
        LOGGER.info("[InvSort] taking {} of {} from page slot {}: {} click(s), the excess stays on the page", r, c, p,
                pending.size());
        return true;
    }

    private static boolean hasEmptySlot(Inventory inv) {
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Slot playerSlot(AbstractContainerMenu menu, Inventory inv, int invIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container == inv && slot.getContainerSlot() == invIndex) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isPage(AbstractContainerScreen<?> screen, String page) {
        String key = StorageOverlayFeature.storageKeyForTitle(screen.getTitle().getString());
        return key != null && key.endsWith("|" + page);
    }

    static String command(String page) {
        return page.startsWith("enderchest_") ? "enderchest " + page.substring("enderchest_".length())
                : "backpack " + page.substring("backpack_".length());
    }

    /** "Ender Chest #3" / "Backpack #5". */
    static String label(String page) {
        if (page == null) {
            return "None";
        }
        return page.startsWith("enderchest_") ? "Ender Chest #" + page.substring("enderchest_".length())
                : "Backpack #" + page.substring("backpack_".length());
    }

    private Result fail(String why) {
        failure = why;
        return Result.FAILED;
    }
}
