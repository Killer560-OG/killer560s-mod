package com.killer560.hub.armourdye;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * The Custom Items menu (killer560, 2026-10-08; main page redesigned 2026-10-18). Left: a search box and the grid of
 * the player's own items (armour, inventory, hotbar). Right: the selected item's preview and four buttons:
 * <ul>
 *   <li><b>Custom Item</b> - {@link CustomItemPickerScreen}: search Minecraft and Hypixel items and apply one's look.</li>
 *   <li><b>Custom Color</b> - {@link CustomColorScreen}: a large preview with the colour, skin, trim and pattern controls.</li>
 *   <li><b>Clear Changes</b> - removes every change from the selected item.</li>
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
    /** True: the preview draws the clear item. Reset on every new selection. Any change turns it off. */
    private boolean showClear = true;
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

    // ------------------------------------------------------------------------------------------- model

    static ArmourDyeConfig cfg() {
        return ArmourDyeConfig.getInstance();
    }

    /** The entry the selection has now, or null (nothing set yet). */
    public ArmourDyeEntry entry() {
        return selectedKey == null ? null : cfg().get(selectedKey);
    }

    /** The entry, created on first change. */
    ArmourDyeEntry editable() {
        if (selectedKey == null) {
            return null;
        }
        return cfg().getOrCreate(selectedKey, selectedLabel);
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

    /** True when the preview draws the clear item: Show Clear chosen, or nothing applied to the selection. */
    boolean clearShown() {
        ArmourDyeEntry e = entry();
        return showClear || e == null || e.isEmpty() || !e.enabled;
    }

    /** Any change to the look switches the preview to showing it. */
    void showLook() {
        showClear = false;
    }

    /** Selects a real item. Its existing look is found by UUID first, then by id; a new look defaults to every copy. */
    public void select(ItemStack stack) {
        copyingLook = false;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        selectedStack = stack;
        selectedLabel = stack.getHoverName().getString();
        showClear = true;
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
        if (uuid != null) {
            return ArmourDyeConfig.normaliseId(ArmourDyeConfig.uuidKey(uuid));
        }
        return null;
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

    /** Sets and arms the colour. */
    void setColour(int argb) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        e.color = argb;
        e.colorEnabled = true;
        cfg().save();
        showLook();
    }

    void toggleUseColour() {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        e.colorEnabled = !e.colorEnabled;
        cfg().save();
        showLook();
    }

    /** Cycles the armour skin; {@code forward} false goes back one. */
    void cycleSkin(boolean forward) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        ArmourSkin next = forward ? e.skin.next() : e.skin.previous();
        if (next == ArmourSkin.CUSTOM) {
            next = forward ? next.next() : next.previous(); // a raw asset id is a hand-edited file thing
        }
        e.skin = next;
        cfg().save();
        showLook();
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
        showLook();
        say((material ? "Trim" : "Pattern") + " set to " + (value.isEmpty() ? "none" : value) + ".", false);
    }

    /** Sets a head texture from the Hypixel item catalog (its real item skin) as the Look. */
    void applyHypixelSkin(SkyblockItemEntry item) {
        ArmourDyeEntry e = editable();
        if (e == null || item == null || item.skinValue() == null) {
            return;
        }
        e.headTexture = item.skinValue().trim();
        e.iconModel = "";
        cfg().save();
        showLook();
        say("Skin set to " + item.name() + ".", false);
    }

    /** Applies another item's model and head texture as the selection's Look (the Custom Item page). */
    void copyLookFrom(ItemStack source) {
        ArmourDyeEntry e = editable();
        if (e == null || source == null || source.isEmpty()) {
            return;
        }
        String head = ArmourDye.headTextureOf(source);
        Identifier model = ArmourDye.itemModelOf(source);
        e.headTexture = head == null ? "" : head;
        e.iconModel = head != null || model == null ? "" : model.toString();
        cfg().save();
        showLook();
        say("Look copied from " + source.getHoverName().getString() + ".", false);
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

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "§8Off";
        }
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    // ------------------------------------------------------------------------------------------- layout

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

        // Preview switch, under the preview.
        SettingsButtonWidget preview = SettingsButtonWidget.builder(
                        Component.literal(showClear ? "Show With Look" : "Show Clear Item"),
                        b -> {
                            showClear = !showClear;
                            refresh();
                        }).bounds(rightX, row(0), rightW, BTN_H).build();
        preview.active = !selectedStack.isEmpty();
        addRenderableWidget(preview);

        // The four buttons.
        SettingsButtonWidget customItem = SettingsButtonWidget.builder(Component.literal("Custom Item"),
                        b -> minecraft.setScreenAndShow(new CustomItemPickerScreen(this)))
                .bounds(rightX, row(1), rightW, BTN_H).build();
        customItem.active = has;
        addRenderableWidget(customItem);

        SettingsButtonWidget customColor = SettingsButtonWidget.builder(Component.literal("Custom Colour"),
                        b -> minecraft.setScreenAndShow(new CustomColorScreen(this)))
                .bounds(rightX, row(2), rightW, BTN_H).build();
        customColor.active = has;
        addRenderableWidget(customColor);

        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("Clear Changes"), b -> {
                    clearChanges();
                    refresh();
                }).bounds(rightX, row(3), rightW, BTN_H).build();
        clear.active = e != null;
        addRenderableWidget(clear);

        SettingsButtonWidget copy = SettingsButtonWidget.builder(
                        Component.literal(copyingLook ? "Pick item to copy to..." : "Copy Look"),
                        b -> {
                            copyingLook = !copyingLook;
                            refresh();
                        }).bounds(rightX, row(4), rightW, BTN_H).build();
        copy.active = has;
        addRenderableWidget(copy);
    }

    /** Y of right-column button {@code k}. Row 0 is the preview switch. */
    private int row(int k) {
        return panelY + 100 + k * BTN_STEP;
    }

    /** Top-left of grid cell {@code index}: 0-3 armour (head first), 4-30 main storage (inventory 9-35), 31-39 hotbar. */
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
        if (index < 31) {
            return p.getInventory().getItem(index - 4 + 9);
        }
        return p.getInventory().getItem(index - 31);
    }

    /** The stack in a cell when it passes the search, else EMPTY. */
    private ItemStack shownStack(int index) {
        ItemStack stack = cellStack(index);
        if (stack.isEmpty() || !matchesQuery(stack.getHoverName().getString(), query)) {
            return ItemStack.EMPTY;
        }
        return stack;
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

    // ------------------------------------------------------------------------------------------- drawing

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
            graphics.item(stack, p[0] + 3, p[1] + 3);
            graphics.itemDecorations(this.font, stack, p[0] + 3, p[1] + 3);
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
            if (clearShown()) {
                ArmourDye.withoutOverrides(() -> graphics.item(selectedStack, 0, 0));
            } else {
                graphics.item(selectedStack, 0, 0);
            }
            graphics.pose().popMatrix();
        }

        // Left column under the grid: the status line.
        MenuStyle.text(graphics, this.font.plainSubstrByWidth(status, GRID_W), gridX,
                gridY + 5 * SLOT + 14, statusBad ? ScreenChrome.bad() : ScreenChrome.good());

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (!hover.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, Screen.getTooltipFromItem(this.minecraft, hover),
                    Optional.empty(), mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------------------------------- input

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

    /** Opens the shared colour wheel; the choice is applied to the selection and the wheel returns to {@code back}. */
    static void colourPicker(Screen back, String label, int current, java.util.function.IntConsumer onChange) {
        Minecraft.getInstance().setScreenAndShow(new ColorPickerScreen(back, label + " Colour", current, 0xFFFFFFFF, onChange));
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
