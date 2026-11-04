package com.killer560.hub.invsort;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.gui.tab.CommandKeybindsTab;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Inventory Sorter's layouts menu (killer560, 2026-10-08): "a button to open a menu that has the inventories you
 * saved shown. You should create inventory loadouts through that same menu; it should be accessible by /invsort. It
 * opens a menu showing your items in your inventory, you move them, then save it as something ... You delete through
 * that menu as well."
 * <p>
 * The grid on the left is a COPY of the inventory - moving items here never clicks anything. Click an item to pick it
 * up and click another slot to swap (or drag it there). RIGHT-click a slot (or press Delete over it) to make it Free:
 * left out of the layout, so whatever is in it is never claimed or moved (killer560, 2026-11-02). Type a name and press
 * Save. On the right, the saved layouts (killer560, 2026-11-01): LEFT-click one to swap to it (the menu closes and the
 * sorter runs - there is no Apply button), RIGHT-click one to load it into the grid to edit, rename, delete or set its
 * storage mode, drag one up or down to reorder them, and click the key cell at the right of a row to set that layout's
 * key. Each row shows the layout's hotbar as items (killer560, 2026-11-03: "make the saved loadouts portion bigger so
 * it is easier for me to see what i have"); the list takes the window's height. The menu sizes itself to the window.
 */
public class InventorySorterScreen extends Screen implements MenuThemedScreen {

    /** Slot size, list row height and the list's preview cell, picked in {@link #init} from the window. */
    private int SLOT = 18;
    private int PANEL_W = 356;
    private int PANEL_H = 176;
    private int LIST_ROW = 13;
    private int LIST_ROWS = 7;
    private int PREVIEW = 12;
    private static final int LIST_W = 196;
    private static final int LIST_W_MAX = 420;
    private static final int KEY_W = 50;
    /** Pixels the mouse must move with a row held before it is a drag, not a click. */
    private static final int DRAG_START = 3;
    private static final String NOT_IN_INVENTORY = " (not in your inventory)";

    private final Screen parent;

    /** Inventory index 0-35 -&gt; the stack drawn there (a copy), or EMPTY. */
    private final ItemStack[] grid = new ItemStack[36];
    /** Inventory index -&gt; true when the stack is a placeholder (the saved item is not in the inventory now). */
    private final boolean[] missing = new boolean[36];
    /** Inventory index -&gt; the exact key a placeholder stands for (InvSortMatch), or null. Moves with its stack. */
    private final String[] exactAt = new String[36];
    /** Inventory index -&gt; Free: left out of the layout. A property of the SLOT, so it stays put when items move. */
    private final boolean[] free = new boolean[36];
    /** Where each slot's item was drawn last frame ({x, y, edge}), for tests. */
    private final float[][] drawnItem = new float[36][];
    /** Inventory index picked up (following the mouse), or -1. */
    private int held = -1;
    /** A pool item picked up (following the mouse), or null. */
    private ItemStack heldPool;
    /** Bumped whenever the grid changes, so the pool is rebuilt only then. */
    private int gridRev;
    private int poolRev = -1;
    private List<ItemStack> poolCache;
    private int poolY;
    private int poolCell = 16;
    /** The slot a press started on, for drag-and-drop; -1 when no press is open. */
    private int pressedSlot = -1;

    private String selected;
    private int listScroll;
    private boolean capturingKey;
    private String status = "";
    private boolean statusBad;
    private double lastMouseX = -1;
    private double lastMouseY = -1;

    private int panelX;
    private int panelY;
    private int gridX;
    private int gridY;
    private int listX;
    private int listY;
    private int listW;
    private int statusY;

    private com.killer560.hub.gui.CommitEditBox nameBox;
    private SettingsButtonWidget bindButton;
    private SettingsButtonWidget fetchButton;
    private SettingsButtonWidget dumpButton;
    private SettingsButtonWidget loadoutButton;

    /** The list row a press started on (index into the names), its button and where; -1 when none. */
    private int pressedRow = -1;
    private int pressedButton;
    private double pressX;
    private double pressY;
    private boolean draggingRow;

    /** A row's preview: the layout's hotbar as stacks, and which of them are placeholders. */
    private record Preview(ItemStack[] stacks, boolean[] missing, int slots) {
    }

    /** Layout name -&gt; its preview, built once per change (matching every row's items each frame is wasted work). */
    private final Map<String, Preview> previews = new HashMap<>();

    public InventorySorterScreen(Screen parent) {
        super(Component.literal("Inventory Sorter"));
        this.parent = parent;
        resetToInventory();
    }

    // ------------------------------------------------------------------------------------------- model

    /** Fills the grid from the real inventory right now, every slot back in the layout. Public for tests. */
    public void resetToInventory() {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = mc.player == null ? null : mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            grid[i] = inv == null ? ItemStack.EMPTY : inv.getItem(i).copy();
            missing[i] = false;
            exactAt[i] = null;
            free[i] = i == InventoryLayout.LOCKED_SLOT;
        }
        held = -1;
        heldPool = null;
        gridRev++;
    }

    /** Loads a saved layout into the grid: each slot gets THAT item from the inventory (its exact key, or any copy of
     *  its id for an older slot) if there is one, else a placeholder drawn from the saved icon. A Free slot shows what
     *  is in it now. Public for tests. */
    public void loadLayout(InventoryLayout layout) {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = mc.player == null ? null : mc.player.getInventory();
        boolean[] used = new boolean[36];
        String[] have = new String[36];
        for (int i = 0; i < 36; i++) {
            grid[i] = ItemStack.EMPTY;
            missing[i] = false;
            exactAt[i] = null;
            free[i] = layout.isFree(i);
            have[i] = inv == null ? null : InvSortMatch.exact(inv.getItem(i));
            if (free[i]) {
                used[i] = true; // never claimed for another slot
                grid[i] = inv == null ? ItemStack.EMPTY : inv.getItem(i).copy();
            }
        }
        Map<Integer, String> wants = layout.wants();
        // Exact slots first, so an older id-only slot never takes the one copy an exact slot names.
        List<Map.Entry<Integer, String>> order = new ArrayList<>(wants.entrySet());
        order.sort((a, b) -> Boolean.compare(!InvSortMatch.isExact(a.getValue()), !InvSortMatch.isExact(b.getValue())));
        for (Map.Entry<Integer, String> e : order) {
            int slot = e.getKey();
            if (slot < 0 || slot >= 36) {
                continue;
            }
            int found = find(have, used, e.getValue(), slot);
            if (found >= 0) {
                used[found] = true;
                grid[slot] = inv.getItem(found).copy();
            } else {
                grid[slot] = placeholder(layout.iconAt(slot), layout.identityAt(slot));
                missing[slot] = true;
                exactAt[slot] = layout.exactKeys().get(slot);
            }
        }
        held = -1;
        heldPool = null;
        gridRev++;
    }

    /** An unused inventory index holding {@code want}: {@code prefer} if it does, then the very item, then any copy. */
    private static int find(String[] have, boolean[] used, String want, int prefer) {
        if (prefer >= 0 && prefer < 36 && !used[prefer] && InvSortMatch.same(have[prefer], want)) {
            return prefer;
        }
        for (int i = 0; i < 36; i++) {
            if (!used[i] && have[i] != null && have[i].equalsIgnoreCase(want)) {
                return i;
            }
        }
        for (int i = 0; i < 36; i++) {
            if (!used[i] && InvSortMatch.same(have[i], want)) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack placeholder(String iconId, String identity) {
        Item item = null;
        Identifier id = iconId == null ? null : Identifier.tryParse(iconId);
        if (id != null) {
            item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        }
        ItemStack stack = new ItemStack(item == null || item == Items.AIR ? Items.PAPER : item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(identity + NOT_IN_INVENTORY));
        return stack;
    }

    // ------------------------------------------------------------------ the pool: real items not in the grid

    /** The real inventory's items that no grid slot shows right now (killer560, 2026-11-03: "it doesnt let me move stuff
     *  around that is in my inventory but not already in a saved loadout" - a loaded layout only draws ITS items, so
     *  anything else had nowhere to be picked up from). Copies, matched by exact key; recomputed when the grid changes.
     *  Draw-only: nothing here touches the real inventory. Public for tests. */
    public List<ItemStack> pool() {
        if (poolRev == gridRev && poolCache != null) {
            return poolCache;
        }
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = mc.player == null ? null : mc.player.getInventory();
        Map<String, Integer> shown = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            if (grid[i] != null && !grid[i].isEmpty() && !missing[i]) {
                shown.merge(InvSortMatch.exact(grid[i]), 1, Integer::sum);
            }
        }
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; inv != null && i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String key = InvSortMatch.exact(s);
            Integer n = shown.get(key);
            if (n != null && n > 0) {
                shown.put(key, n - 1);
            } else {
                out.add(s.copy());
            }
        }
        poolCache = out;
        poolRev = gridRev;
        return out;
    }

    /** Puts a copy of pool item {@code poolIndex} into grid slot {@code slot} (a real item, so it saves with its exact
     *  key). Whatever was there goes back to the pool. Never a Free slot. Public for tests. */
    public boolean placeFromPool(int poolIndex, int slot) {
        List<ItemStack> pool = pool();
        if (slot < 0 || slot >= 36 || free[slot] || poolIndex < 0 || poolIndex >= pool.size()) {
            return false;
        }
        grid[slot] = pool.get(poolIndex).copy();
        missing[slot] = false;
        exactAt[slot] = null;
        gridRev++;
        return true;
    }

    /** Empties a grid slot (the item returns to the pool if it is a real one). Public for tests. */
    public void clearSlot(int slot) {
        if (slot < 0 || slot >= 36 || free[slot]) {
            return;
        }
        grid[slot] = ItemStack.EMPTY;
        missing[slot] = false;
        exactAt[slot] = null;
        if (held == slot) {
            held = -1;
        }
        gridRev++;
    }

    /** Index into {@link #pool()} of the cell under (x, y), or -1. */
    private int poolAt(double x, double y) {
        int n = pool().size();
        for (int k = 0; k < n && k < 36; k++) {
            int cx = gridX + (k % 9) * poolCell;
            int cy = poolY + (k / 9) * poolCell;
            if (x >= cx && x < cx + poolCell && y >= cy && y < cy + poolCell) {
                return k;
            }
        }
        return -1;
    }

    /** True over the pool's whole area (label and empty cells too), where a dropped grid item is put away. */
    private boolean overPoolArea(double x, double y) {
        return x >= gridX && x < gridX + 9 * SLOT && y >= poolY - 10 && y < poolY + 4 * poolCell;
    }

    /** {x, y, edge} of pool cell {@code k} (GUI units). For tests. */
    public int[] poolCellPos(int k) {
        return new int[]{gridX + (k % 9) * poolCell, poolY + (k / 9) * poolCell, poolCell};
    }

    /** The picked-up pool item, or null. For tests. */
    public ItemStack heldPoolItem() {
        return heldPool;
    }

    private boolean pressedPool;

    /** Drops the picked-up pool item on {@code slot}. */
    private void placeHeldPool(int slot) {
        ItemStack s = heldPool;
        heldPool = null;
        if (s == null || slot < 0 || slot >= 36 || free[slot]) {
            return;
        }
        grid[slot] = s;
        missing[slot] = false;
        exactAt[slot] = null;
        gridRev++;
    }

    /** Picks pool item {@code k} up (follows the mouse until dropped on a grid slot). Public for tests. */
    public void pickPool(int k) {
        List<ItemStack> pool = pool();
        if (k >= 0 && k < pool.size()) {
            heldPool = pool.get(k).copy();
            held = -1;
        }
    }

    /** Swaps two grid slots (never a Free one: it is not part of the layout). Public for tests. */
    public void swap(int a, int b) {
        if (a < 0 || b < 0 || a >= 36 || b >= 36 || a == b || free[a] || free[b]) {
            return;
        }
        ItemStack s = grid[a];
        grid[a] = grid[b];
        grid[b] = s;
        boolean m = missing[a];
        missing[a] = missing[b];
        missing[b] = m;
        String x = exactAt[a];
        exactAt[a] = exactAt[b];
        exactAt[b] = x;
        gridRev++;
    }

    /** Makes {@code slot} Free (left out of the layout) or puts it back in. Public for tests. */
    public void toggleFree(int slot) {
        if (slot == InventoryLayout.LOCKED_SLOT) {
            say("That slot is locked: it holds the Skyblock Menu, map or arrows, and the sorter never touches it.", true);
            return;
        }
        if (slot < 0 || slot >= 36) {
            return;
        }
        if (held == slot) {
            held = -1;
        }
        free[slot] = !free[slot];
        gridRev++;
        if (free[slot] && missing[slot]) {
            // A placeholder is a layout item, not something in the slot: a Free slot holds what is really there.
            Minecraft mc = Minecraft.getInstance();
            grid[slot] = mc.player == null ? ItemStack.EMPTY : mc.player.getInventory().getItem(slot).copy();
            missing[slot] = false;
            exactAt[slot] = null;
        }
        say(free[slot] ? "Slot " + slotName(slot) + " is Free: the sort leaves whatever is in it alone."
                : "Slot " + slotName(slot) + " is back in the layout.", false);
    }

    public boolean isFreeSlot(int slot) {
        return slot >= 0 && slot < 36 && free[slot];
    }

    private static String slotName(int slot) {
        return slot < 9 ? "hotbar " + (slot + 1) : String.valueOf(slot);
    }

    /** The grid as a layout: every slot holding an identifiable item, each with its exact key, and the Free slots.
     *  Public for tests. */
    public InventoryLayout toLayout(String name) {
        Map<Integer, String> slots = new LinkedHashMap<>();
        Map<Integer, String> icons = new LinkedHashMap<>();
        Map<Integer, String> exact = new LinkedHashMap<>();
        Set<Integer> freeSlots = new TreeSet<>();
        for (int i = 0; i < 36; i++) {
            if (free[i]) {
                freeSlots.add(i);
                continue;
            }
            ItemStack stack = grid[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String identity = missing[i] ? identityOfPlaceholder(stack) : com.killer560.hub.autoroutes.ItemIdentity.of(stack);
            if (identity == null) {
                continue;
            }
            slots.put(i, identity);
            icons.put(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            String key = missing[i] ? exactAt[i] : InvSortMatch.exact(stack);
            if (key != null) {
                exact.put(i, key);
            }
        }
        return new InventoryLayout(name, slots, icons, exact, freeSlots, false, null);
    }

    private static String identityOfPlaceholder(ItemStack stack) {
        String name = stack.getHoverName().getString();
        int cut = name.indexOf(NOT_IN_INVENTORY);
        return cut > 0 ? name.substring(0, cut) : null;
    }

    public ItemStack gridAt(int slot) {
        return grid[slot];
    }

    /** {x, y, edge} of the item drawn in inventory index {@code slot} last frame (GUI units), or null. For tests. */
    public float[] drawnItemBox(int slot) {
        return drawnItem[slot];
    }

    /** The edge of one grid square, frame included (GUI units). For tests. */
    public int slotSize() {
        return SLOT;
    }

    public String selectedLayout() {
        return selected;
    }

    public String status() {
        return status;
    }

    /** {listX, listY, listW, row height, rows shown, preview cell} (GUI units). For tests. */
    public int[] listGeometry() {
        return new int[]{listX, listY, listW, LIST_ROW, LIST_ROWS, PREVIEW};
    }

    // ------------------------------------------------------------------------------------------- actions

    /** Saves the grid under the typed name. A new layout starts with Fetch From Storage ON (killer560, 2026-11-03: "by
     *  default have fetch from storage on"); saving over one keeps its storage mode. Public for tests. */
    public void saveTyped() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        String error = InventoryLayoutStore.validateName(name);
        if (error != null) {
            say(error, true);
            return;
        }
        InventoryLayout layout = toLayout(name);
        InventoryLayout existing = InventoryLayoutStore.getInstance().get(name);
        layout = existing != null ? layout.withStorage(existing.fetchFromStorage(), existing.dumpPage())
                .withLoadout(existing.loadout()) : layout.withStorage(true, null);
        if (layout.isEmpty()) {
            say("Nothing to save - the grid is empty.", true);
            return;
        }
        String saveError = InventoryLayoutStore.getInstance().save(layout);
        if (saveError != null) {
            say(saveError, true);
            return;
        }
        selected = layout.name();
        refreshStorageLabels();
        say("Saved " + layout.name() + " (" + layout.size() + " slots" + (layout.freeSlots().isEmpty() ? ""
                : ", " + layout.freeSlots().size() + " free") + ").", false);
    }

    /** Selects a saved layout and loads it into the grid. Public for tests. */
    public void select(String name) {
        InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
        if (layout == null) {
            return;
        }
        selected = layout.name();
        capturingKey = false;
        loadLayout(layout);
        if (nameBox != null) {
            nameBox.setValue(layout.name());
        }
        refreshBindLabel();
        refreshStorageLabels();
        say("Loaded " + layout.name() + ".", false);
    }

    /** Swaps to a saved layout: the menu closes and the sorter runs (killer560, 2026-11-01: "click on a loadout to swap
     *  to it not that you have to click apply"). Public for tests. */
    public void applyLayout(String name) {
        InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
        if (layout == null) {
            return;
        }
        this.minecraft.setScreenAndShow(null);
        InventorySorterExecutor.applyByName(layout.name());
    }

    /** Applies the selected layout to the real inventory. Public for tests. */
    public void applySelected() {
        if (selected == null) {
            say("Pick a saved layout on the right first.", true);
            return;
        }
        String name = selected;
        this.minecraft.setScreenAndShow(null);
        InventorySorterExecutor.applyByName(name);
    }

    /** Renames the selected layout to the typed name. Public for tests. */
    public void renameSelected() {
        if (selected == null) {
            say("Pick a saved layout on the right first.", true);
            return;
        }
        String to = nameBox == null ? "" : nameBox.getValue().trim();
        String error = InventoryLayoutStore.getInstance().rename(selected, to);
        if (error != null) {
            say(error, true);
            return;
        }
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        cfg.renameLayoutKey(selected, to);
        cfg.renameInOrder(selected, to);
        cfg.save();
        say("Renamed " + selected + " to " + to + ".", false);
        selected = to;
    }

    /** Deletes the selected layout (and its keybind). Public for tests. */
    public void deleteSelected() {
        if (selected == null) {
            say("Pick a saved layout on the right first.", true);
            return;
        }
        String name = selected;
        if (InventoryLayoutStore.getInstance().delete(name)) {
            InventorySorterConfig cfg = InventorySorterConfig.getInstance();
            cfg.setLayoutKey(name, KeyUtil.NONE);
            cfg.removeFromOrder(name);
            cfg.save();
            say("Deleted " + name + ".", false);
        }
        selected = null;
        capturingKey = false;
        refreshBindLabel();
        refreshStorageLabels();
    }

    /** Moves the layout at {@code from} to {@code to} in the menu's order and saves it. Public for tests. */
    public void moveLayout(int from, int to) {
        List<String> names = new ArrayList<>(InventoryLayoutStore.getInstance().listNames());
        if (from < 0 || from >= names.size()) {
            return;
        }
        String name = names.remove(from);
        names.add(Math.max(0, Math.min(names.size(), to)), name);
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        cfg.setLayoutOrder(names);
        cfg.save();
    }

    /** Turns the selected layout's "fetch from storage" on or off. Public for tests. */
    public void toggleFetch() {
        InventoryLayout layout = selected == null ? null : InventoryLayoutStore.getInstance().get(selected);
        if (layout == null) {
            say("Right-click a saved layout first.", true);
            return;
        }
        InventoryLayoutStore.getInstance().save(layout.withStorage(!layout.fetchFromStorage(), layout.dumpPage()));
        refreshStorageLabels();
        say("Fetch From Storage " + (!layout.fetchFromStorage() ? "on" : "off") + " for " + layout.name() + ".", false);
    }

    /** Put Others In (killer560, 2026-11-02: "should open the storage and let him pick the page"): closes this menu and
     *  opens /storage; the Ender Chest page or backpack he opens from there becomes the layout's page, and this menu
     *  comes back. Public for tests. */
    public void pickDumpPage() {
        InventoryLayout layout = selected == null ? null : InventoryLayoutStore.getInstance().get(selected);
        if (layout == null) {
            say("Right-click a saved layout first.", true);
            return;
        }
        String error = InvSortPagePick.begin(layout.name());
        if (error != null) {
            say(error, true);
        }
    }

    /** Right click on Put Others In: no page. Public for tests. */
    public void clearDumpPage() {
        InventoryLayout layout = selected == null ? null : InventoryLayoutStore.getInstance().get(selected);
        if (layout == null) {
            say("Right-click a saved layout first.", true);
            return;
        }
        InventoryLayoutStore.getInstance().save(layout.withStorage(layout.fetchFromStorage(), null));
        refreshStorageLabels();
        say("Put Others In cleared for " + layout.name() + ".", false);
    }

    /** Steps the selected layout's Hypixel loadout: none, 1, 2 ... 12, wrapping (left click forward, right click back).
     *  Public for tests. */
    public void stepLoadout(int dir) {
        InventoryLayout layout = selected == null ? null : InventoryLayoutStore.getInstance().get(selected);
        if (layout == null) {
            say("Right-click a saved layout first.", true);
            return;
        }
        int next = Math.floorMod(layout.loadout() + dir, InventoryLayout.MAX_LOADOUT + 1);
        InventoryLayoutStore.getInstance().save(layout.withLoadout(next));
        refreshStorageLabels();
        say("Hypixel Loadout " + (next == 0 ? "cleared" : "set to " + next) + " for " + layout.name() + ".", false);
    }

    private void refreshStorageLabels() {
        InventoryLayout layout = selected == null ? null : InventoryLayoutStore.getInstance().get(selected);
        if (fetchButton != null) {
            fetchButton.setMessage(Component.literal("Fetch From Storage: " + (layout == null ? "-"
                    : layout.fetchFromStorage() ? "ON" : "OFF")));
            fetchButton.active = layout != null;
        }
        if (loadoutButton != null) {
            loadoutButton.setMessage(Component.literal("Hypixel Loadout: " + (layout == null ? "-"
                    : layout.loadout() == 0 ? "None" : String.valueOf(layout.loadout()))));
            loadoutButton.active = layout != null;
        }
        if (dumpButton != null) {
            dumpButton.setMessage(Component.literal("Put Others In: " + (layout == null ? "-"
                    : InvSortStorage.label(layout.dumpPage()))));
            dumpButton.active = layout != null;
        }
    }

    /** Shows {@code text} in the status line. Every action ends here, so it also drops the row previews. */
    void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
        previews.clear();
    }

    private void refreshBindLabel() {
        if (bindButton == null) {
            return;
        }
        if (capturingKey) {
            bindButton.setMessage(Component.literal("Press any key..."));
        } else {
            int code = selected == null ? KeyUtil.NONE : InventorySorterConfig.getInstance().getLayoutKey(selected);
            bindButton.setMessage(Component.literal("Key: " + CommandKeybindsTab.bindName(code)));
        }
    }

    // ------------------------------------------------------------------------------------------- previews

    /** The row preview for {@code name}: its hotbar slots 0-8 (or, for a layout with no hotbar slot, its first nine
     *  slots), each the very item from the inventory if it is there, else the saved icon. */
    private Preview preview(String name) {
        Preview cached = previews.get(name);
        if (cached != null) {
            return cached;
        }
        InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
        ItemStack[] stacks = new ItemStack[9];
        boolean[] miss = new boolean[9];
        java.util.Arrays.fill(stacks, ItemStack.EMPTY);
        if (layout != null) {
            List<Integer> shown = new ArrayList<>();
            for (int s = 0; s < 9; s++) {
                shown.add(layout.wantAt(s) != null ? s : -1);
            }
            if (shown.stream().allMatch(s -> s < 0)) {
                shown.clear();
                for (int s : new TreeSet<>(layout.entries().keySet())) {
                    if (shown.size() < 9) {
                        shown.add(s);
                    }
                }
            }
            Minecraft mc = Minecraft.getInstance();
            Inventory inv = mc.player == null ? null : mc.player.getInventory();
            String[] have = new String[36];
            for (int i = 0; i < 36 && inv != null; i++) {
                have[i] = layout.isFree(i) ? null : InvSortMatch.exact(inv.getItem(i));
            }
            boolean[] used = new boolean[36];
            for (int k = 0; k < shown.size(); k++) {
                int s = shown.get(k);
                if (s < 0) {
                    continue;
                }
                int found = inv == null ? -1 : find(have, used, layout.wantAt(s), s);
                if (found >= 0) {
                    used[found] = true;
                    stacks[k] = inv.getItem(found).copy();
                } else {
                    stacks[k] = placeholder(layout.iconAt(s), layout.identityAt(s));
                    miss[k] = true;
                }
            }
        }
        Preview p = new Preview(stacks, miss, layout == null ? 0 : layout.size());
        previews.put(name, p);
        return p;
    }

    // ------------------------------------------------------------------------------------------- layout

    @Override
    protected void init() {
        previews.clear();
        // Bigger on a real screen (killer560, 2026-11-01: "Make the menu a bit larger it is really small"): slots up to
        // 26 px, shrinking to the old 18 in a small window.
        SLOT = Math.max(18, Math.min(26, Math.min((this.height - 200) / 4, (this.width - LIST_W - 40) / 9)));
        int leftW = 9 * SLOT;
        int baseLeftH = 22 + (4 * SLOT + 4) + 6 + 16 + 4 + 18 + 6 + 10 + 6 + 14 + 4;
        poolCell = Math.max(10, Math.min(Math.min(SLOT, 20), (this.height - 8 - baseLeftH) / 4));
        // The saved layouts take every pixel left over (killer560, 2026-11-03: "make the saved loadouts portion bigger
        // so it is easier for me to see what i have"): the list is as wide as the window allows (up to 420) and as
        // tall as the window, and each row shows the layout's items.
        int room = this.width - 8 - (leftW + 12 + 16);
        // Never narrower than 196 where the window has room; a tiny window gets what is left (at least 120) so it fits.
        int listWant = Math.max(Math.min(LIST_W, Math.max(120, room)), Math.min(LIST_W_MAX, room));
        PANEL_W = leftW + 12 + listWant + 16;
        int leftH = baseLeftH + 4 * poolCell;
        PANEL_H = Math.max(leftH, Math.min(this.height - 8, 500));
        panelX = (this.width - PANEL_W) / 2;
        panelY = Math.max(4, (this.height - PANEL_H) / 2);
        gridX = panelX + 8;
        gridY = panelY + 22;
        listX = gridX + leftW + 12;
        listY = gridY + 10;
        listW = panelX + PANEL_W - 8 - listX;

        // Two storage buttons at the foot of the list column; the rows fill the rest.
        int storageY = panelY + PANEL_H - 8 - 18 - 44;
        int listH = storageY - 4 - listY;
        int byWidth = (listW - 2 - KEY_W - 2 - 8) / 9;
        int byHeight = listH / 5 - 16; // at least five rows
        PREVIEW = Math.max(10, Math.min(22, Math.min(byWidth, byHeight)));
        LIST_ROW = PREVIEW + 16;
        LIST_ROWS = Math.max(2, listH / LIST_ROW);

        int below = gridY + 4 * SLOT + 4 + 6;
        String typed = nameBox == null ? (selected == null ? "" : selected) : nameBox.getValue();
        nameBox = new ThemedEditBox(this.font, gridX, below, leftW, 16, Component.literal("Layout name"))
                .hint("Layout name");
        nameBox.setMaxLength(InventoryLayoutStore.MAX_NAME);
        nameBox.setValue(typed);
        nameBox.onEnter(this::saveTyped);
        addRenderableWidget(nameBox);

        int bw = (leftW - 12) / 4;
        int bx = gridX;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save"), b -> saveTyped())
                .bounds(bx, below + 20, bw, 18).build());
        bx += bw + 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset"), b -> {
                    resetToInventory();
                    say("Grid reset to your inventory.", false);
                }).bounds(bx, below + 20, bw, 18).build());
        bx += bw + 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Rename"), b -> renameSelected())
                .bounds(bx, below + 20, bw, 18).build());
        bx += bw + 4;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§cDelete"), b -> deleteSelected())
                .bounds(bx, below + 20, gridX + leftW - bx, 18).build());
        // The status line sits under the buttons, in the left column (nothing is drawn under a button any more:
        // killer560, 2026-11-02, the gray hint line used to show through behind Rename).
        statusY = below + 20 + 18 + 6;
        poolY = statusY + 22;

        // The selected (right-clicked) layout's storage mode, under the list.
        fetchButton = SettingsButtonWidget.builder(Component.literal("Fetch From Storage"), b -> toggleFetch())
                .bounds(listX, storageY, listW, 18).build();
        addRenderableWidget(fetchButton);
        dumpButton = SettingsButtonWidget.builder(Component.literal("Put Others In"), b -> pickDumpPage())
                .secondaryPress(b -> clearDumpPage())
                .bounds(listX, storageY + 22, listW, 18).build();
        addRenderableWidget(dumpButton);
        loadoutButton = SettingsButtonWidget.cycling(Component.literal("Hypixel Loadout"), (b, dir) -> stepLoadout(dir))
                .bounds(listX, storageY + 44, listW, 18).build();
        addRenderableWidget(loadoutButton);
        refreshStorageLabels();
        refreshBindLabel();
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + PANEL_W - 48, panelY + 4, 40, 14).build());
    }

    /** {x, y, w, h} of a list row's Edit button; the row starts at {@code ry}, its name column is {@code nameW} wide.
     *  Public for tests. */
    public int[] editRect(int ry, int nameW) {
        int w = this.font.width("Editing") + 8;
        return new int[]{listX + 1 + nameW - 3 - w, ry + 1, w, 11};
    }

    /** Inventory index under (x, y), or -1. */
    private int slotAt(double x, double y) {
        for (int i = 0; i < 36; i++) {
            int[] p = slotPos(i);
            if (x >= p[0] && x < p[0] + SLOT && y >= p[1] && y < p[1] + SLOT) {
                return i;
            }
        }
        return -1;
    }

    /** Top-left of inventory index {@code i}'s square: main storage 9-35 in three rows, the hotbar below. */
    public int[] slotPos(int i) {
        if (i < 9) {
            return new int[]{gridX + i * SLOT, gridY + 3 * SLOT + 4};
        }
        int r = (i - 9) / 9;
        int c = (i - 9) % 9;
        return new int[]{gridX + c * SLOT, gridY + r * SLOT};
    }

    // ------------------------------------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        // In the menu's theme (sweep-b, 2026-10-13); slots, list rows and buttons keep their places.
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, PANEL_W, PANEL_H);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, PANEL_W - 6, 18, "Inventory Sorter");
        MenuStyle.text(graphics, "layouts", panelX + 11 + this.font.width("Inventory Sorter") + 6, panelY + 7,
                MenuStyle.DIM2);
        MenuStyle.text(graphics, "SAVED LAYOUTS", listX, gridY, MenuStyle.DIM);
        MenuStyle.text(graphics, "KEY", listX + listW - 1 - KEY_W / 2 - this.font.width("KEY") / 2, gridY, MenuStyle.DIM);

        ItemStack hover = ItemStack.EMPTY;
        boolean hoverFree = false;
        java.util.Arrays.fill(drawnItem, null);
        for (int i = 0; i < 36; i++) {
            int[] p = slotPos(i);
            ScreenChrome.slot(graphics, p[0], p[1], SLOT, i == held, false);
            ItemStack stack = grid[i];
            boolean over = mouseX >= p[0] && mouseX < p[0] + SLOT && mouseY >= p[1] && mouseY < p[1] + SLOT;
            if (i != held && stack != null && !stack.isEmpty()) {
                // Fitted to the cell, rarity box behind it (killer560, 2026-11-02: "in /invsort the items dont fit").
                drawnItem[i] = com.killer560.hub.gui.ItemCell.draw(graphics, this.font, stack, p[0] + 1, p[1] + 1, SLOT - 2);
                if (missing[i]) {
                    graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0x55FF3333);
                }
                if (over) {
                    hover = stack;
                }
            }
            if (free[i]) {
                // Free: dimmed, with a mark, so it reads as "not part of this layout".
                graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0xB0101010);
                String word = i == InventoryLayout.LOCKED_SLOT ? "Lock" : "Free";
                String mark = this.font.width(word) <= SLOT - 3 ? word : word.substring(0, 1);
                MenuStyle.text(graphics, mark, p[0] + (SLOT - this.font.width(mark)) / 2, p[1] + (SLOT - 8) / 2,
                        MenuStyle.DIM);
                if (over) {
                    hoverFree = i != InventoryLayout.LOCKED_SLOT;
                }
            }
            if (over) {
                graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0x40FFFFFF);
            }
        }

        // Your inventory's items that the grid does not show: pick one up and drop it on a slot.
        MenuStyle.text(graphics, "YOUR INVENTORY - not in the grid", gridX, poolY - 10, MenuStyle.DIM);
        List<ItemStack> pool = pool();
        for (int k = 0; k < 36; k++) {
            int[] c = poolCellPos(k);
            ScreenChrome.slot(graphics, c[0], c[1], poolCell, false, false);
            if (k >= pool.size()) {
                continue;
            }
            com.killer560.hub.gui.ItemCell.draw(graphics, this.font, pool.get(k), c[0] + 1, c[1] + 1, poolCell - 2);
            if (mouseX >= c[0] && mouseX < c[0] + poolCell && mouseY >= c[1] && mouseY < c[1] + poolCell) {
                graphics.fill(c[0] + 1, c[1] + 1, c[0] + poolCell - 1, c[1] + poolCell - 1, 0x40FFFFFF);
                hover = pool.get(k);
            }
        }

        // Saved layouts: name and its items (left click swaps to it, right click edits it, drag reorders), key cell on
        // the right.
        if (pressedRow >= 0 && !draggingRow && pressedButton == 0
                && (Math.abs(mouseY - pressY) > DRAG_START || Math.abs(mouseX - pressX) > DRAG_START)) {
            draggingRow = true;
        }
        List<String> names = InventoryLayoutStore.getInstance().listNames();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, names.size() - LIST_ROWS)));
        ScreenChrome.well(graphics, listX, listY, listW, LIST_ROWS * LIST_ROW + 1);
        if (names.isEmpty()) {
            MenuStyle.text(graphics, "None yet - arrange, name, Save", listX + 4, listY + 4, MenuStyle.DIM2);
        }
        int dropAt = draggingRow ? dropIndex(mouseY, names.size()) : -1;
        int nameW = listW - 2 - KEY_W - 2;
        for (int row = 0; row < LIST_ROWS && listScroll + row < names.size(); row++) {
            int index = listScroll + row;
            String name = names.get(index);
            int ry = listY + 1 + row * LIST_ROW;
            boolean sel = name.equalsIgnoreCase(selected);
            boolean dragged = draggingRow && index == pressedRow;
            boolean overName = !draggingRow && mouseX >= listX && mouseX < listX + 1 + nameW && mouseY >= ry
                    && mouseY < ry + LIST_ROW;
            ScreenChrome.row(graphics, listX + 1, ry, nameW, LIST_ROW, overName, sel || dragged, row % 2 == 1);
            Preview pv = preview(name);
            // The Edit button sits where the "N slots, fetch" text was (killer560, 2026-11-03): it loads the layout into
            // the editor on the left.
            int[] eb = editRect(ry, nameW);
            int infoW = eb[2];
            boolean overEdit = !draggingRow && mouseX >= eb[0] && mouseX < eb[0] + eb[2] && mouseY >= eb[1]
                    && mouseY < eb[1] + eb[3];
            ScreenChrome.row(graphics, eb[0], eb[1], eb[2], eb[3], overEdit, sel, false);
            String editLabel = sel ? "Editing" : "Edit";
            MenuStyle.text(graphics, editLabel, eb[0] + (eb[2] - this.font.width(editLabel)) / 2, eb[1] + (eb[3] - 8) / 2 + 1,
                    sel ? MenuStyle.OR_HOVER : MenuStyle.TXT);
            int textY = ry + 3;
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(name, Math.max(20, nameW - 14 - infoW))),
                    listX + 4, textY, dragged ? MenuStyle.DIM2 : sel ? MenuStyle.OR_HOVER : MenuStyle.TXT);
            // The layout's items, in cells (the hotbar row), so it reads at a glance.
            int cy = ry + 12;
            for (int k = 0; k < 9; k++) {
                int cx = listX + 4 + k * PREVIEW;
                if (cx + PREVIEW > listX + 1 + nameW) {
                    break;
                }
                ScreenChrome.slot(graphics, cx, cy, PREVIEW, false, false);
                ItemStack st = pv.stacks()[k];
                if (st.isEmpty()) {
                    continue;
                }
                com.killer560.hub.gui.ItemCell.draw(graphics, this.font, st, cx + 1, cy + 1, PREVIEW - 2);
                if (pv.missing()[k]) {
                    graphics.fill(cx + 1, cy + 1, cx + PREVIEW - 1, cy + PREVIEW - 1, 0x55FF3333);
                }
                if (!draggingRow && mouseX >= cx && mouseX < cx + PREVIEW && mouseY >= cy && mouseY < cy + PREVIEW) {
                    hover = st;
                }
            }
            // The key cell, to the right of the layout (killer560, 2026-11-01).
            int kx = listX + 1 + nameW + 2;
            boolean overKey = !draggingRow && mouseX >= kx && mouseX < kx + KEY_W && mouseY >= ry && mouseY < ry + LIST_ROW;
            ScreenChrome.row(graphics, kx, ry, KEY_W, LIST_ROW, overKey, capturingKey && sel, row % 2 == 1);
            int code = InventorySorterConfig.getInstance().getLayoutKey(name);
            String key = capturingKey && sel ? "Press..." : code == KeyUtil.NONE ? "Set key"
                    : CommandKeybindsTab.bindName(code);
            key = this.font.plainSubstrByWidth(key, KEY_W - 6);
            MenuStyle.text(graphics, key, kx + (KEY_W - this.font.width(key)) / 2, ry + (LIST_ROW - 8) / 2,
                    code == KeyUtil.NONE && !(capturingKey && sel) ? MenuStyle.DIM2 : MenuStyle.TXT);
            if (dropAt == index && dropAt != pressedRow) {
                graphics.fill(listX + 1, ry - 1, listX + listW - 1, ry + 1, MenuStyle.OR_HOVER);
            }
        }
        if (dropAt == names.size() && names.size() - listScroll <= LIST_ROWS) {
            int ry = listY + 1 + (names.size() - listScroll) * LIST_ROW;
            graphics.fill(listX + 1, ry - 1, listX + listW - 1, ry + 1, MenuStyle.OR_HOVER);
        }
        if (names.size() > LIST_ROWS) {
            String more = (listScroll + 1) + "-" + Math.min(names.size(), listScroll + LIST_ROWS) + " of " + names.size();
            MenuStyle.text(graphics, more, listX + listW - this.font.width(more) - KEY_W - 8, gridY, MenuStyle.DIM2);
        }
        if (draggingRow && pressedRow < names.size()) {
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(names.get(pressedRow), nameW)),
                    mouseX + 6, mouseY - 4, MenuStyle.OR_HOVER);
        }

        MenuStyle.text(graphics, this.font.plainSubstrByWidth(status, 9 * SLOT), gridX, statusY,
                statusBad ? ScreenChrome.bad() : ScreenChrome.good());

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (heldPool != null && !heldPool.isEmpty()) {
            com.killer560.hub.gui.ItemCell.drawHeld(graphics, heldPool, mouseX, mouseY, SLOT - 2);
        } else if (held >= 0 && grid[held] != null && !grid[held].isEmpty()) {
            com.killer560.hub.gui.ItemCell.drawHeld(graphics, grid[held], mouseX, mouseY, SLOT - 2);
        } else if (hoverFree) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Free"));
            lines.add(Component.literal("§7Left out of this layout: the sort never takes"));
            lines.add(Component.literal("§7or moves what is in this slot. Right-click to undo."));
            graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        } else if (dumpButton != null && dumpButton.active && mouseX >= dumpButton.getX() && mouseX < dumpButton.getX() + dumpButton.getWidth()
                && mouseY >= dumpButton.getY() && mouseY < dumpButton.getY() + dumpButton.getHeight()) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Put Others In"));
            lines.add(Component.literal("§7Where the items that are not in the layout go when it is applied."));
            lines.add(Component.literal("§7Left click: pick an Ender Chest page or backpack. Right click: None."));
            lines.add(Component.literal("§7None: they are left in your inventory, in the slots the layout does not use."));
            graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        } else if (loadoutButton != null && loadoutButton.active && mouseX >= loadoutButton.getX() && mouseX < loadoutButton.getX() + loadoutButton.getWidth()
                && mouseY >= loadoutButton.getY() && mouseY < loadoutButton.getY() + loadoutButton.getHeight()) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Hypixel Loadout"));
            lines.add(Component.literal("§7Applying this layout first selects this loadout from /loadouts (page 1, 1-12),"));
            lines.add(Component.literal("§7then sorts. Left click: next. Right click: back. None: the loadout is left alone."));
            graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        } else if (!hover.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, Screen.getTooltipFromItem(this.minecraft, hover),
                    Optional.empty(), mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (capturingKey) {
            // A mouse button can be the bind too (KeyUtil's mouse codes); Escape cancels, Backspace clears.
            bind(KeyUtil.codeForMouseButton(com.killer560.hub.compat.McInput.button(event)));
            return true;
        }
        int button = com.killer560.hub.compat.McInput.button(event);
        int slot = slotAt(event.x(), event.y());
        if (heldPool != null && button == 1) {
            heldPool = null; // right click: put the picked-up item back
            pressedPool = false;
            return true;
        }
        if (slot >= 0 && heldPool != null && !free[slot]) {
            // A picked-up inventory item onto a slot: it replaces what the slot shows (which returns to the pool).
            placeHeldPool(slot);
            return true;
        }
        int pk = poolAt(event.x(), event.y());
        if (pk >= 0 && button == 0) {
            if (held >= 0) {
                clearSlot(held); // a grid item dropped on the pool is put away
                held = -1;
                pressedSlot = -1;
            } else if (heldPool != null) {
                heldPool = null;
            } else {
                pickPool(pk);
                pressedPool = true; // a drag from here onto a slot places it on release
            }
            return true;
        }
        if (overPoolArea(event.x(), event.y()) && button == 0) {
            if (held >= 0) {
                clearSlot(held);
                held = -1;
                pressedSlot = -1;
            } else {
                heldPool = null;
            }
            return true;
        }
        if (slot >= 0) {
            if (button == 1 && held < 0) {
                toggleFree(slot); // right click: Free / back in the layout
                pressedSlot = -1;
                return true;
            }
            if (free[slot]) {
                say(slot == InventoryLayout.LOCKED_SLOT ? "That slot is locked: the sorter never touches it."
                        : "That slot is Free - right-click it to put it back in the layout.", true);
                return true;
            }
            if (held >= 0) {
                swap(held, slot);
                held = -1;
                pressedSlot = -1;
            } else if (grid[slot] != null && !grid[slot].isEmpty()) {
                held = slot;
                pressedSlot = slot;
            }
            return true;
        }
        if (event.x() >= listX && event.x() < listX + listW && event.y() >= listY
                && event.y() < listY + LIST_ROWS * LIST_ROW) {
            int row = (int) ((event.y() - listY - 1) / LIST_ROW);
            List<String> names = InventoryLayoutStore.getInstance().listNames();
            if (row >= 0 && row < LIST_ROWS && listScroll + row < names.size()) {
                String name = names.get(listScroll + row);
                int[] eb = editRect(listY + 1 + row * LIST_ROW, listW - 2 - KEY_W - 2);
                if (button == 0 && event.x() >= eb[0] && event.x() < eb[0] + eb[2] && event.y() >= eb[1]
                        && event.y() < eb[1] + eb[3]) {
                    select(name); // the Edit button: load it into the editor
                    return true;
                }
                if (event.x() >= listX + listW - 1 - KEY_W) {
                    // The key cell: capture this layout's key (the same capture as before, now per row).
                    select(name);
                    capturingKey = true;
                    refreshBindLabel();
                    return true;
                }
                pressedRow = listScroll + row;
                pressedButton = button == 1 ? 1 : 0;
                pressX = event.x();
                pressY = event.y();
                draggingRow = false;
            }
            return true;
        }
        if (held >= 0 && slotAt(event.x(), event.y()) < 0 && !isOverWidget(event.x(), event.y())) {
            held = -1; // dropped outside the grid: put it back
        }
        if (heldPool != null && !isOverWidget(event.x(), event.y())) {
            heldPool = null;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressedRow >= 0) {
            if (!draggingRow && pressedButton == 0
                    && (Math.abs(event.y() - pressY) > DRAG_START || Math.abs(event.x() - pressX) > DRAG_START)) {
                draggingRow = true;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    /** Where a dragged row would land: the gap index (0..size) nearest the mouse. */
    private int dropIndex(double mouseY, int size) {
        int gap = (int) Math.round((mouseY - listY - 1) / LIST_ROW) + listScroll;
        return Math.max(0, Math.min(size, gap));
    }

    private boolean isOverWidget(double x, double y) {
        return x >= panelX && x < panelX + PANEL_W && y >= panelY && y < panelY + PANEL_H;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressedRow >= 0) {
            int row = pressedRow;
            boolean drag = draggingRow;
            int button = pressedButton;
            pressedRow = -1;
            draggingRow = false;
            List<String> names = InventoryLayoutStore.getInstance().listNames();
            if (row < names.size()) {
                if (drag) {
                    int to = dropIndex(event.y(), names.size());
                    moveLayout(row, to > row ? to - 1 : to);
                } else if (button == 1) {
                    select(names.get(row));
                } else {
                    applyLayout(names.get(row));
                }
            }
            return true;
        }
        if (pressedPool) {
            pressedPool = false;
            int to = slotAt(event.x(), event.y());
            if (to >= 0 && heldPool != null && !free[to]) {
                placeHeldPool(to); // dragged from the pool onto a slot
            }
            return true;
        }
        if (pressedSlot >= 0) {
            int slot = slotAt(event.x(), event.y());
            if (slot >= 0 && slot != pressedSlot && held == pressedSlot && !free[slot]) {
                // A drag from one slot to another: swap them.
                swap(pressedSlot, slot);
                held = -1;
            }
            pressedSlot = -1;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + LIST_ROWS * LIST_ROW) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingKey) {
            // Configs keep GLFW numbers: McInput.key, never the raw event key (an SDL scancode on 26.3, which
            // setLayoutKey's range check turned into "none" - UI suite 584, 2026-11-01).
            // Escape cancels, Backspace/Delete clears, anything else binds - the rule of every key row (2026-11-02).
            int stored = com.killer560.hub.compat.McInput.key(event);
            if (stored == com.killer560.hub.util.Keys.ESCAPE) {
                capturingKey = false;
                refreshBindLabel();
            } else {
                bind(stored == com.killer560.hub.util.Keys.BACKSPACE || stored == com.killer560.hub.util.Keys.DELETE
                        ? KeyUtil.NONE : stored);
            }
            return true;
        }
        if (com.killer560.hub.compat.McInput.key(event) == com.killer560.hub.util.Keys.DELETE
                && (nameBox == null || !nameBox.isFocused())) {
            // Delete over a grid slot: Free / back in the layout, the same as a right click.
            int slot = slotAt(lastMouseX, lastMouseY);
            if (slot >= 0) {
                toggleFree(slot);
                return true;
            }
        }
        if (com.killer560.hub.compat.McInput.key(event) == com.killer560.hub.util.Keys.BACKSPACE
                && (nameBox == null || !nameBox.isFocused())) {
            // Backspace over a grid slot empties it (the item goes back to the inventory list below the grid).
            int slot = slotAt(lastMouseX, lastMouseY);
            if (slot >= 0) {
                clearSlot(slot);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Sets (or with {@link KeyUtil#NONE} clears) the selected layout's key. Public for tests. */
    public void bind(int code) {
        capturingKey = false;
        if (selected != null) {
            InventorySorterConfig cfg = InventorySorterConfig.getInstance();
            cfg.setLayoutKey(selected, code);
            cfg.save();
            int now = cfg.getLayoutKey(selected);
            say(now == KeyUtil.NONE ? "Key cleared for " + selected + "."
                    : selected + " is on " + CommandKeybindsTab.bindName(now) + ".", false);
        }
        refreshBindLabel();
    }

    /** Shown again after Put Others In's page pick: the layout selected and the result in the status line. */
    void afterPick(String name, String message, boolean bad) {
        select(name);
        say(message, bad);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, PANEL_W, PANEL_H};
    }

    @Override
    public String themeId() {
        return "invsort";
    }
}
