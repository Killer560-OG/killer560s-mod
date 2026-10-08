package com.killer560.hub.invsort;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.tab.CommandKeybindsTab;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Inventory Sorter's layouts menu (killer560, 2026-10-08): "a button to open a menu that has the inventories you
 * saved shown. You should create inventory loadouts through that same menu; it should be accessible by /invsort. It
 * opens a menu showing your items in your inventory, you move them, then save it as something ... You delete through
 * that menu as well."
 * <p>
 * The grid on the left is a COPY of the inventory - moving items here never clicks anything. Click an item to pick it
 * up and click another slot to swap (or drag it there). Type a name and press Save. On the right, the saved
 * layouts: click one to load it into the grid and select it, then Apply / Rename (to the typed name) / Delete / Bind
 * Key act on the selected one. Apply closes the menu and the executor sorts the real inventory.
 */
public class InventorySorterScreen extends Screen {

    private static final int SLOT = 18;
    private static final int PANEL_W = 356;
    private static final int PANEL_H = 176;
    private static final int LIST_ROW = 13;
    private static final int LIST_ROWS = 7;
    private static final int ACCENT = 0xFFCC6600;

    private final Screen parent;

    /** Inventory index 0-35 -&gt; the stack drawn there (a copy), or EMPTY. */
    private final ItemStack[] grid = new ItemStack[36];
    /** Inventory index -&gt; true when the stack is a placeholder (the saved item is not in the inventory now). */
    private final boolean[] missing = new boolean[36];
    /** Inventory index picked up (following the mouse), or -1. */
    private int held = -1;
    /** The slot a press started on, for drag-and-drop; -1 when no press is open. */
    private int pressedSlot = -1;

    private String selected;
    private int listScroll;
    private boolean capturingKey;
    private String status = "";
    private int statusColor = 0xFFAAAAAA;

    private int panelX;
    private int panelY;
    private int gridX;
    private int gridY;
    private int listX;
    private int listY;
    private int listW;

    private EditBox nameBox;
    private SettingsButtonWidget bindButton;

    public InventorySorterScreen(Screen parent) {
        super(Component.literal("Inventory Sorter"));
        this.parent = parent;
        resetToInventory();
    }

    // ------------------------------------------------------------------------------------------- model

    /** Fills the grid from the real inventory right now. Public for tests. */
    public void resetToInventory() {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = mc.player == null ? null : mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            grid[i] = inv == null ? ItemStack.EMPTY : inv.getItem(i).copy();
            missing[i] = false;
        }
        held = -1;
    }

    /** Loads a saved layout into the grid: each slot gets an item of that identity from the inventory if there is
     *  one, else a placeholder drawn from the saved icon. Public for tests. */
    public void loadLayout(InventoryLayout layout) {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = mc.player == null ? null : mc.player.getInventory();
        boolean[] used = new boolean[36];
        for (int i = 0; i < 36; i++) {
            grid[i] = ItemStack.EMPTY;
            missing[i] = false;
        }
        for (Map.Entry<Integer, String> e : layout.entries().entrySet()) {
            int slot = e.getKey();
            if (slot < 0 || slot >= 36) {
                continue;
            }
            ItemStack found = ItemStack.EMPTY;
            if (inv != null) {
                for (int i = 0; i < 36; i++) {
                    if (!used[i] && e.getValue().equalsIgnoreCase(ItemIdentity.of(inv.getItem(i)))) {
                        used[i] = true;
                        found = inv.getItem(i).copy();
                        break;
                    }
                }
            }
            if (found.isEmpty()) {
                found = placeholder(layout.iconAt(slot), e.getValue());
                missing[slot] = true;
            }
            grid[slot] = found;
        }
        held = -1;
    }

    private static ItemStack placeholder(String iconId, String identity) {
        Item item = null;
        Identifier id = iconId == null ? null : Identifier.tryParse(iconId);
        if (id != null) {
            item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        }
        ItemStack stack = new ItemStack(item == null || item == Items.AIR ? Items.PAPER : item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(identity + " (not in your inventory)"));
        return stack;
    }

    /** Swaps two grid slots. Public for tests. */
    public void swap(int a, int b) {
        if (a < 0 || b < 0 || a >= 36 || b >= 36 || a == b) {
            return;
        }
        ItemStack s = grid[a];
        grid[a] = grid[b];
        grid[b] = s;
        boolean m = missing[a];
        missing[a] = missing[b];
        missing[b] = m;
    }

    /** The grid as a layout: every slot holding an identifiable item. Public for tests. */
    public InventoryLayout toLayout(String name) {
        Map<Integer, String> slots = new LinkedHashMap<>();
        Map<Integer, String> icons = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = grid[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String identity = missing[i] ? identityOfPlaceholder(stack) : ItemIdentity.of(stack);
            if (identity == null) {
                continue;
            }
            slots.put(i, identity);
            icons.put(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return new InventoryLayout(name, slots, icons);
    }

    private static String identityOfPlaceholder(ItemStack stack) {
        String name = stack.getHoverName().getString();
        int cut = name.indexOf(" (not in your inventory)");
        return cut > 0 ? name.substring(0, cut) : null;
    }

    public ItemStack gridAt(int slot) {
        return grid[slot];
    }

    public String selectedLayout() {
        return selected;
    }

    public String status() {
        return status;
    }

    // ------------------------------------------------------------------------------------------- actions

    /** Saves the grid under the typed name. Public for tests. */
    public void saveTyped() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        String error = InventoryLayoutStore.validateName(name);
        if (error != null) {
            say(error, true);
            return;
        }
        InventoryLayout layout = toLayout(name);
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
        say("Saved " + layout.name() + " (" + layout.size() + " slots).", false);
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
        say("Loaded " + layout.name() + ".", false);
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
            cfg.save();
            say("Deleted " + name + ".", false);
        }
        selected = null;
        capturingKey = false;
        refreshBindLabel();
    }

    private void say(String text, boolean bad) {
        status = text;
        statusColor = bad ? 0xFFFF5555 : 0xFF55FF55;
    }

    private void refreshBindLabel() {
        if (bindButton == null) {
            return;
        }
        if (capturingKey) {
            bindButton.setMessage(Component.literal("Press a key..."));
        } else {
            int code = selected == null ? KeyUtil.NONE : InventorySorterConfig.getInstance().getLayoutKey(selected);
            bindButton.setMessage(Component.literal("Key: " + CommandKeybindsTab.bindName(code)));
        }
    }

    // ------------------------------------------------------------------------------------------- layout

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = Math.max(4, (this.height - PANEL_H) / 2);
        gridX = panelX + 8;
        gridY = panelY + 22;
        listX = gridX + 9 * SLOT + 12;
        listY = gridY + 10;
        listW = panelX + PANEL_W - 8 - listX;

        int below = gridY + 4 * SLOT + 4 + 6;
        String typed = nameBox == null ? (selected == null ? "" : selected) : nameBox.getValue();
        nameBox = new EditBox(this.font, gridX, below, 9 * SLOT, 16, Component.literal("Layout name"));
        nameBox.setMaxLength(InventoryLayoutStore.MAX_NAME);
        nameBox.setValue(typed);
        nameBox.setHint(Component.literal("Layout name"));
        addRenderableWidget(nameBox);

        int half = (9 * SLOT - 4) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save"), b -> saveTyped())
                .bounds(gridX, below + 20, half, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset Grid"), b -> {
                    resetToInventory();
                    say("Grid reset to your inventory.", false);
                }).bounds(gridX + half + 4, below + 20, 9 * SLOT - half - 4, 18).build());

        int actionsY = listY + LIST_ROWS * LIST_ROW + 4;
        int bw = (listW - 4) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Apply"), b -> applySelected())
                .bounds(listX, actionsY, bw, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Rename"), b -> renameSelected())
                .bounds(listX + bw + 4, actionsY, listW - bw - 4, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§cDelete"), b -> deleteSelected())
                .bounds(listX, actionsY + 22, bw, 18).build());
        bindButton = SettingsButtonWidget.builder(Component.literal("Key"), b -> {
                    if (selected == null) {
                        say("Pick a saved layout on the right first.", true);
                        return;
                    }
                    capturingKey = true;
                    refreshBindLabel();
                }).bounds(listX + bw + 4, actionsY + 22, listW - bw - 4, 18).build();
        addRenderableWidget(bindButton);
        refreshBindLabel();
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + PANEL_W - 48, panelY + 4, 40, 14).build());
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
        graphics.fill(0, 0, this.width, this.height, 0xAA000000);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xF0101010);
        graphics.outline(panelX, panelY, PANEL_W, PANEL_H, ACCENT);
        graphics.text(this.font, "Inventory Sorter - layouts", panelX + 8, panelY + 8, ACCENT, false);
        graphics.text(this.font, "Saved Layouts", listX, gridY, 0xFFDDDDDD, false);

        ItemStack hover = ItemStack.EMPTY;
        for (int i = 0; i < 36; i++) {
            int[] p = slotPos(i);
            graphics.fill(p[0], p[1], p[0] + SLOT, p[1] + SLOT, 0xFF262626);
            graphics.outline(p[0], p[1], SLOT, SLOT, 0xFF553311);
            ItemStack stack = grid[i];
            if (i == held || stack == null || stack.isEmpty()) {
                continue;
            }
            graphics.item(stack, p[0] + 1, p[1] + 1);
            graphics.itemDecorations(this.font, stack, p[0] + 1, p[1] + 1);
            if (missing[i]) {
                graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0x55FF3333);
            }
            if (mouseX >= p[0] && mouseX < p[0] + SLOT && mouseY >= p[1] && mouseY < p[1] + SLOT) {
                graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0x40FFFFFF);
                hover = stack;
            }
        }

        // Saved layouts.
        List<String> names = InventoryLayoutStore.getInstance().listNames();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, names.size() - LIST_ROWS)));
        graphics.outline(listX, listY, listW, LIST_ROWS * LIST_ROW + 1, 0xFF553311);
        if (names.isEmpty()) {
            graphics.text(this.font, "None yet - arrange, name, Save", listX + 3, listY + 3, 0xFF888888, false);
        }
        for (int row = 0; row < LIST_ROWS && listScroll + row < names.size(); row++) {
            String name = names.get(listScroll + row);
            int ry = listY + 1 + row * LIST_ROW;
            boolean sel = name.equalsIgnoreCase(selected);
            boolean over = mouseX >= listX && mouseX < listX + listW && mouseY >= ry && mouseY < ry + LIST_ROW;
            if (sel || over) {
                graphics.fill(listX + 1, ry, listX + listW - 1, ry + LIST_ROW, sel ? 0x66CC6600 : 0x33FFFFFF);
            }
            int code = InventorySorterConfig.getInstance().getLayoutKey(name);
            String key = code == KeyUtil.NONE ? "" : " [" + CommandKeybindsTab.bindName(code) + "]";
            graphics.text(this.font, this.font.plainSubstrByWidth(name + key, listW - 6), listX + 3, ry + 3,
                    sel ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }

        graphics.text(this.font, this.font.plainSubstrByWidth(status, PANEL_W - 80), panelX + 8,
                panelY + PANEL_H - 18, statusColor, false);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (held >= 0 && grid[held] != null && !grid[held].isEmpty()) {
            graphics.item(grid[held], mouseX - 8, mouseY - 8);
        } else if (!hover.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, Screen.getTooltipFromItem(this.minecraft, hover),
                    Optional.empty(), mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (capturingKey) {
            // A mouse button can be the bind too (KeyUtil's mouse codes); Escape (a key) clears it.
            bind(KeyUtil.codeForMouseButton(event.button()));
            return true;
        }
        int slot = slotAt(event.x(), event.y());
        if (slot >= 0) {
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
            if (row >= 0 && listScroll + row < names.size()) {
                select(names.get(listScroll + row));
            }
            return true;
        }
        if (held >= 0 && slotAt(event.x(), event.y()) < 0 && !isOverWidget(event.x(), event.y())) {
            held = -1; // dropped outside the grid: put it back
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean isOverWidget(double x, double y) {
        return x >= panelX && x < panelX + PANEL_W && y >= panelY && y < panelY + PANEL_H;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressedSlot >= 0) {
            int slot = slotAt(event.x(), event.y());
            if (slot >= 0 && slot != pressedSlot && held == pressedSlot) {
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
            bind(event.key() == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : event.key());
            return true;
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

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
