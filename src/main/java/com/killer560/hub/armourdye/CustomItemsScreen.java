package com.killer560.hub.armourdye;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * The Custom Items menu (killer560, 2026-10-08; main page redesigned 2026-10-18). Left: a search box and the grid of
 * the player's own items (armor, inventory, hotbar). Right: the selected item's preview, always drawn with its look
 * (killer560, 2026-11-01: the Show With Look switch is gone), and four buttons:
 * <ul>
 *   <li><b>Customize Custom Item</b> - {@link CustomItemPickerScreen}: search Minecraft and Hypixel items and apply one's
 *       look.</li>
 *   <li><b>Customize Custom Color</b> - {@link CustomColorScreen}: a large preview with one row per change (color, skin,
 *       trim color, trim pattern), each with its own clear button.</li>
 *   <li><b>Clear Changes</b> - removes every change from the selected item (at the bottom, 2026-11-01).</li>
 *   <li><b>Copy Look</b> - click Copy Look, then an item in the grid: that item takes the selected item's changes.</li>
 * </ul>
 * The child pages share this screen's selection through the public methods below, and every change they make is saved
 * at once. Nothing is ever sent to the server.
 */
public class CustomItemsScreen extends Screen implements MenuThemedScreen {

    static final int SLOT = 22;
    static final int GRID_W = 9 * SLOT;
    static final int MAX_W = 460;
    static final int MAX_H = 300;
    static final int BTN_H = 20;
    private static final int LEFT_PAD = 8;
    private static final int COL_GAP = 12;
    private static final int BTN_STEP = 26;
    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private final Screen parent;

    /** The selected real stack (live, from the inventory), or EMPTY when nothing is selected. */
    private ItemStack selectedStack = ItemStack.EMPTY;
    /** The key the selection edits (an id or UUID: key), or null for no selection. */
    private String selectedKey;
    private String selectedLabel = "";
    private boolean copyingLook;
    /** The search text, kept across rebuilds. */
    private String query = "";
    private String status = "";
    private boolean statusBad;

    private int panelW;
    private int panelH;
    private int panelX;
    private int panelY;
    private int gridX;
    private int gridY;
    private int rightX;
    private int rightW;

    private EditBox searchBox;

    public CustomItemsScreen(Screen parent) {
        super(Component.literal("Custom Items"));
        this.parent = parent;
    }

    static ArmourDyeConfig cfg() {
        return ArmourDyeConfig.getInstance();
    }

    /** The entry the selection has now, or null (nothing set yet). */
    public ArmourDyeEntry entry() {
        return selectedKey == null ? null : cfg().get(selectedKey);
    }

    /** The entry, created on first change. */
    ArmourDyeEntry editable() {
        return selectedKey == null ? null : cfg().getOrCreate(selectedKey, selectedLabel);
    }

    /** The live stack the selection is on, or EMPTY. */
    public ItemStack selectedStack() {
        return selectedStack;
    }

    public String selectedKey() {
        return selectedKey;
    }

    public String selectedLabel() {
        return selectedLabel;
    }

    /** Selects a real item. Its existing look is found by UUID first, then by id; a new look defaults to every copy. */
    public void select(ItemStack stack) {
        copyingLook = false;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        selectedStack = stack;
        selectedLabel = stack.getHoverName().getString();
        String key = keyFor(stack);
        if (key == null) {
            selectedKey = null;
            say("That item can't be identified, so a look can't be kept for it.", true);
            return;
        }
        selectedKey = key;
    }

    /** The key a stack's look is kept under: its UUID when that has a look, else its Skyblock id, else its UUID. */
    static String keyFor(ItemStack stack) {
        String uuid = ArmourDye.uuidOf(stack);
        String id = ArmourDye.identityOf(stack);
        if (uuid != null && cfg().get(ArmourDyeConfig.uuidKey(uuid)) != null) {
            return ArmourDyeConfig.normaliseId(ArmourDyeConfig.uuidKey(uuid));
        }
        if (id != null) {
            return ArmourDyeConfig.normaliseId(id);
        }
        return uuid != null ? ArmourDyeConfig.normaliseId(ArmourDyeConfig.uuidKey(uuid)) : null;
    }

    /** Switches between this item (UUID) and every copy (id). */
    void toggleAppliesTo() {
        if (selectedStack.isEmpty() || selectedKey == null) {
            return;
        }
        String uuid = ArmourDye.uuidOf(selectedStack);
        String id = ArmourDye.identityOf(selectedStack);
        if (uuid == null || id == null) {
            say("This item has no " + (uuid == null ? "UUID" : "id") + " - it can only be keyed one way.", true);
            return;
        }
        boolean perItem = selectedKey.startsWith(ArmourDyeEntry.UUID_PREFIX);
        String to = ArmourDyeConfig.normaliseId(perItem ? id : ArmourDyeConfig.uuidKey(uuid));
        ArmourDyeEntry e = entry();
        if (e != null) {
            cfg().rekey(selectedKey, e.copyAs(to));
            cfg().save();
        }
        selectedKey = to;
        say(perItem ? "Now every copy of this item." : "Now only this exact item.", false);
    }

    /** Sets and arms the color. */
    void setColour(int argb) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        e.color = argb;
        e.colorEnabled = true;
        cfg().save();
    }

    /** Reset Color (it replaced Use Color, killer560 2026-11-01): the item's own color again. Also what Clear Dye does. */
    void resetColour() {
        ArmourDyeEntry e = entry();
        if (e == null) {
            return;
        }
        e.colorEnabled = false;
        e.color = 0xFFFFFFFF;
        saveOrDrop(e);
    }

    /**
     * Clear Skin: the Hypixel skin goes, and so does an armor-type skin an older version set (the Skin row no longer
     * offers those - killer560, 2026-11-01: "If they want to make a leather chestplate diamond they will use the custom
     * item thing").
     */
    void clearSkin() {
        ArmourDyeEntry e = entry();
        if (e == null) {
            return;
        }
        if (!e.skinName.isBlank()) {
            e.headTexture = "";
        }
        e.skinName = "";
        e.skin = ArmourSkin.NONE;
        e.skinAsset = "";
        saveOrDrop(e);
    }

    /** Clear on the Customize Custom Item page: the picked item's look comes off (its model and head texture). */
    void clearCustomItem() {
        ArmourDyeEntry e = entry();
        if (e == null) {
            return;
        }
        if (e.skinName.isBlank()) {
            e.headTexture = "";
        }
        e.iconModel = "";
        e.customItem = "";
        saveOrDrop(e);
    }

    /** Clear Trim Color ({@code material}) / Clear Trim Pattern. */
    void clearTrim(boolean material) {
        ArmourDyeEntry e = entry();
        if (e == null) {
            return;
        }
        if (material) {
            e.trimMaterial = "";
        } else {
            e.trimPattern = "";
        }
        saveOrDrop(e);
    }

    /** Saves after a clear; an entry left with nothing in it is removed, so the grid's "has a look" mark goes too. */
    private void saveOrDrop(ArmourDyeEntry e) {
        if (e.isEmpty() && e.customItem.isBlank() && e.skinName.isBlank() && e.trimMaterial.isBlank()
                && e.trimPattern.isBlank()) {
            cfg().remove(selectedKey);
        }
        cfg().save();
    }

    /** Sets the trim material or pattern picked from a list (blank = none). */
    void setTrimChoice(boolean material, String value) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        if (material) {
            e.trimMaterial = value;
        } else {
            e.trimPattern = value;
        }
        cfg().save();
    }

    /** Sets a Hypixel item skin ({@link ItemSkins}) as the item's head texture: the Skin row. */
    void applyHypixelSkin(ItemSkins.Skin skin) {
        ArmourDyeEntry e = editable();
        if (e == null || skin == null || skin.texture() == null) {
            return;
        }
        e.headTexture = skin.texture().trim();
        e.iconModel = "";
        e.skinName = skin.name();
        e.customItem = "";
        cfg().save();
    }

    /**
     * Applies another item's model and head texture as the selection's Look (the Customize Custom Item page).
     * {@code pickedKey} ({@code minecraft:<id>} / {@code hypixel:<id>}) is kept so the page shows the pick again on reopen.
     */
    void copyLookFrom(ItemStack source, String pickedKey) {
        ArmourDyeEntry e = editable();
        if (e == null || source == null || source.isEmpty()) {
            return;
        }
        String head = ArmourDye.headTextureOf(source);
        Identifier model = ArmourDye.itemModelOf(source);
        e.headTexture = head == null ? "" : head;
        e.iconModel = head != null || model == null ? "" : model.toString();
        e.customItem = pickedKey == null ? "" : pickedKey;
        e.skinName = "";
        cfg().save();
    }

    /** Removes every change from the selected item (its whole saved entry). */
    void clearChanges() {
        if (selectedKey == null || cfg().get(selectedKey) == null) {
            say("Nothing to clear.", true);
            return;
        }
        cfg().remove(selectedKey);
        cfg().save();
    }

    /** Gives {@code target} every change the selected item has (the Copy Look button). */
    void copyChangesTo(ItemStack target) {
        ArmourDyeEntry src = entry();
        if (src == null) {
            say("The selected item has no changes to copy.", true);
            return;
        }
        String key = keyFor(target);
        if (key == null) {
            say("That item can't be identified, so it can't take a look.", true);
            return;
        }
        cfg().rekey(key, src.copyAs(key));
        cfg().save();
        say("Changes copied to " + target.getHoverName().getString() + ".", false);
    }

    void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
    }

    String status() {
        return status;
    }

    /** Rebuilds this screen's widgets, keeping the search text. */
    void refresh() {
        if (this.minecraft != null) {
            rebuildWidgets();
        }
    }

    /**
     * Search: true when the item name contains ANY word of the query, case-insensitive. A blank query matches all.
     * Shared by the grid filter and the Custom Item page.
     */
    static boolean matchesQuery(String name, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty() && lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_W, this.width - 8);
        panelH = Math.min(MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        gridX = panelX + LEFT_PAD;
        gridY = panelY + 48;
        rightX = gridX + GRID_W + COL_GAP;
        rightW = Math.max(60, panelX + panelW - 8 - rightX);

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + panelW - 64, panelY + 4, 56, 16).build());

        searchBox = new ThemedEditBox(this.font, gridX, panelY + 24, GRID_W, 18, Component.literal("Search"))
                .hint("search items by name");
        searchBox.setMaxLength(64);
        searchBox.setResponder(text -> query = text);
        searchBox.setValue(query);
        addRenderableWidget(searchBox);

        boolean has = selectedKey != null;
        ArmourDyeEntry e = entry();

        // The two pages, under the preview.
        SettingsButtonWidget customItem = SettingsButtonWidget.builder(Component.literal("Customize Custom Item"),
                        b -> minecraft.setScreenAndShow(new CustomItemPickerScreen(this)))
                .bounds(rightX, row(0), rightW, BTN_H).build();
        customItem.active = has;
        addRenderableWidget(customItem);

        SettingsButtonWidget customColor = SettingsButtonWidget.builder(Component.literal("Customize Custom Color"),
                        b -> minecraft.setScreenAndShow(new CustomColorScreen(this)))
                .bounds(rightX, row(1), rightW, BTN_H).build();
        customColor.active = has;
        addRenderableWidget(customColor);

        // Clear Changes and Copy Look at the bottom of the panel (killer560, 2026-11-01).
        int bottom = panelY + panelH - 8 - BTN_H;
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("Clear Changes"), b -> {
                    clearChanges();
                    refresh();
                }).bounds(rightX, bottom - BTN_STEP, rightW, BTN_H).build();
        clear.active = e != null;
        addRenderableWidget(clear);

        SettingsButtonWidget copy = SettingsButtonWidget.builder(
                        Component.literal(copyingLook ? "Pick item to copy to..." : "Copy Look"),
                        b -> {
                            copyingLook = !copyingLook;
                            refresh();
                        }).bounds(rightX, bottom, rightW, BTN_H).build();
        copy.active = has;
        addRenderableWidget(copy);
    }

    /** Y of right-column button {@code k}, under the preview. */
    private int row(int k) {
        return panelY + 100 + k * BTN_STEP;
    }

    /** Top-left of grid cell {@code index}: 0-3 armor (head first), 4-30 main storage (inventory 9-35), 31-39 hotbar. */
    public int[] cellPos(int index) {
        if (index < 4) {
            return new int[]{gridX + index * SLOT, gridY};
        }
        if (index < 31) {
            int r = (index - 4) / 9;
            int c = (index - 4) % 9;
            return new int[]{gridX + c * SLOT, gridY + SLOT + 4 + r * SLOT};
        }
        return new int[]{gridX + (index - 31) * SLOT, gridY + SLOT + 4 + 3 * SLOT + 4};
    }

    private ItemStack cellStack(int index) {
        Player p = Minecraft.getInstance().player;
        if (p == null) {
            return ItemStack.EMPTY;
        }
        if (index < 4) {
            return p.getItemBySlot(ARMOUR[index]);
        }
        return index < 31 ? p.getInventory().getItem(index - 4 + 9) : p.getInventory().getItem(index - 31);
    }

    /** The stack in a cell when it passes the search, else EMPTY. */
    private ItemStack shownStack(int index) {
        ItemStack stack = cellStack(index);
        return stack.isEmpty() || !matchesQuery(stack.getHoverName().getString(), query) ? ItemStack.EMPTY : stack;
    }

    private int cellAt(double x, double y) {
        for (int i = 0; i < 40; i++) {
            int[] p = cellPos(i);
            if (x >= p[0] && x < p[0] + SLOT && y >= p[1] && y < p[1] + SLOT) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Custom Items");
        if (!cfg().isEnabledRaw()) {
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(
                    "Custom Items is OFF in its tab - looks are saved but not drawn.", panelW - 80 - 72),
                    panelX + 80, panelY + 6, ScreenChrome.bad());
        }

        ItemStack hover = ItemStack.EMPTY;
        for (int i = 0; i < 40; i++) {
            int[] p = cellPos(i);
            ItemStack stack = shownStack(i);
            boolean sel = !selectedStack.isEmpty() && stack == selectedStack;
            ScreenChrome.slot(graphics, p[0], p[1], SLOT, sel, false);
            if (stack.isEmpty()) {
                continue;
            }
            com.killer560.hub.gui.ItemCell.draw(graphics, this.font, stack, p[0] + 1, p[1] + 1, SLOT - 2); // fitted, rarity box behind
            if (ArmourDye.entryFor(stack) != null) {
                graphics.fill(p[0] + SLOT - 4, p[1] + 1, p[0] + SLOT - 1, p[1] + 4, MenuStyle.OR); // has a look
            }
            if (mouseX >= p[0] && mouseX < p[0] + SLOT && mouseY >= p[1] && mouseY < p[1] + SLOT) {
                graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0x40FFFFFF);
                hover = stack;
            }
        }

        // Right side: title, the preview, and its state.
        String title = selectedKey == null ? "Click an item to change its look" : selectedLabel;
        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(title, rightW)), rightX, panelY + 24,
                selectedKey == null ? MenuStyle.DIM : MenuStyle.TXT);
        ScreenChrome.well(graphics, rightX, panelY + 40, 52, 52);
        if (!selectedStack.isEmpty()) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(rightX + 6, panelY + 46);
            graphics.pose().scale(2.75f, 2.75f);
            graphics.item(selectedStack, 0, 0);
            graphics.pose().popMatrix();
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (!hover.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, Screen.getTooltipFromItem(this.minecraft, hover),
                    Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int cell = cellAt(event.x(), event.y());
        if (cell >= 0) {
            ItemStack stack = shownStack(cell);
            if (stack.isEmpty()) {
                return true;
            }
            if (copyingLook && selectedKey != null) {
                copyingLook = false;
                copyChangesTo(stack);
            } else {
                select(stack);
            }
            refresh();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Opens the shared color wheel; the choice is applied to the selection and the wheel returns to {@code back}. */
    static void colourPicker(Screen back, String label, int current, java.util.function.IntConsumer onChange) {
        Minecraft.getInstance().setScreenAndShow(new ColorPickerScreen(back, label + " Color", current, 0xFFFFFFFF, onChange));
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public String themeId() {
        return "custom_items";
    }
}
