package com.killer560.hub.armourdye;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Custom Items menu (killer560, 2026-10-08): "It should be a command or a button there that you open. You then
 * select an item in your inventory and you can then apply a skin to it or recolor or something ... You should be able
 * to remove skins in that menu as well."
 * <p>
 * Left: his armour, inventory and hotbar (the real stacks, drawn through the overrides, so a change shows at once), and
 * under them every saved look. Click an item (or a saved look) to select it. Right: a large preview and the controls -
 * Applies To (this item by UUID / every copy by Skyblock id), Colour + Use Colour (dyeable items, which is most
 * Skyblock armour), Skin and Trim / Pattern (armour), the Look (any item model id, or "Copy Look" then
 * click another item - a head's texture comes with it), and Remove. Nothing is ever sent to the server.
 */
public class CustomItemsScreen extends Screen implements MenuThemedScreen {

    private static final int SLOT = 18;
    private static final int PANEL_W = 364;
    private static final int PANEL_H = 212;
    private static final int LIST_ROW = 11;
    private static final int LIST_ROWS = 5;
    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private final Screen parent;

    /** The selected real stack (live, from the inventory), or EMPTY when a saved look is selected on its own. */
    private ItemStack selectedStack = ItemStack.EMPTY;
    /** The key the selection edits (an id or UUID: key), or null for no selection. */
    private String selectedKey;
    private String selectedLabel = "";
    private boolean copyingLook;
    private int listScroll;
    private String status = "";
    private boolean statusBad;

    private int panelX;
    private int panelY;
    private int gridX;
    private int gridY;
    private int rightX;
    private int rightW;
    private int listY;

    private EditBox lookBox;

    public CustomItemsScreen(Screen parent) {
        super(Component.literal("Custom Items"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------------------------------- model

    private static ArmourDyeConfig cfg() {
        return ArmourDyeConfig.getInstance();
    }

    /** The entry the selection has now, or null (nothing set yet). */
    public ArmourDyeEntry entry() {
        return selectedKey == null ? null : cfg().get(selectedKey);
    }

    /** The entry, created on first change. */
    private ArmourDyeEntry editable() {
        if (selectedKey == null) {
            return null;
        }
        return cfg().getOrCreate(selectedKey, selectedLabel);
    }

    /** Selects a real item. Its existing look is found by UUID first, then by id; a new look defaults to every copy.
     *  Public for tests. */
    public void select(ItemStack stack) {
        copyingLook = false;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        selectedStack = stack;
        selectedLabel = stack.getHoverName().getString();
        String uuid = ArmourDye.uuidOf(stack);
        String id = ArmourDye.identityOf(stack);
        if (uuid != null && cfg().get(ArmourDyeConfig.uuidKey(uuid)) != null) {
            selectedKey = ArmourDyeConfig.normaliseId(ArmourDyeConfig.uuidKey(uuid));
        } else if (id != null) {
            selectedKey = ArmourDyeConfig.normaliseId(id);
        } else if (uuid != null) {
            selectedKey = ArmourDyeConfig.normaliseId(ArmourDyeConfig.uuidKey(uuid));
        } else {
            selectedKey = null;
            say("That item can't be identified, so a look can't be kept for it.", true);
        }
        refresh();
    }

    /** Selects a saved look by key (the item may not be in the inventory). Public for tests. */
    public void selectKey(String key) {
        ArmourDyeEntry e = cfg().get(key);
        if (e == null) {
            return;
        }
        copyingLook = false;
        selectedKey = e.itemId;
        selectedLabel = e.label;
        selectedStack = findInInventory(e.itemId);
        refresh();
    }

    private ItemStack findInInventory(String key) {
        Player p = Minecraft.getInstance().player;
        if (p == null) {
            return ItemStack.EMPTY;
        }
        for (ItemStack s : allStacks(p)) {
            if (s.isEmpty()) {
                continue;
            }
            String uuid = ArmourDye.uuidOf(s);
            if (uuid != null && ArmourDyeConfig.normaliseId(ArmourDyeConfig.uuidKey(uuid)).equals(key)) {
                return s;
            }
            String id = ArmourDye.identityOf(s);
            if (id != null && ArmourDyeConfig.normaliseId(id).equals(key)) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    private static List<ItemStack> allStacks(Player p) {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot s : ARMOUR) {
            out.add(p.getItemBySlot(s));
        }
        for (int i = 0; i < 36; i++) {
            out.add(p.getInventory().getItem(i));
        }
        return out;
    }

    /** Switches between this item (UUID) and every copy (id). Public for tests. */
    public void toggleAppliesTo() {
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
        refresh();
    }

    /** Sets and arms the colour. Public for tests. */
    public void setColour(int argb) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        e.color = argb;
        e.colorEnabled = true;
        cfg().save();
    }

    public void toggleUseColour() {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        e.colorEnabled = !e.colorEnabled;
        cfg().save();
        refresh();
    }

    /** Cycles the armour skin; {@code forward} false goes back one. Public for tests. */
    public void cycleSkin(boolean forward) {
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
        refresh();
    }

    private void cycleTrim(boolean material, boolean forward) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        List<String> options = material ? ArmourTrims.materials() : ArmourTrims.patterns();
        String cur = material ? e.trimMaterial : e.trimPattern;
        String next = cycle(options, cur, forward);
        if (material) {
            e.trimMaterial = next;
        } else {
            e.trimPattern = next;
        }
        cfg().save();
        refresh();
    }

    /** Sets the Look from text: an item model id ({@code diamond_sword}, {@code minecraft:diamond_sword}) or, if it
     *  is long and not an id, a head texture. Blank clears it. Public for tests. */
    public void setLook(String raw) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            e.iconModel = "";
            e.headTexture = "";
            say("Look cleared.", false);
        } else if (text.length() > 60 && !text.contains(":")) {
            e.iconModel = "";
            e.headTexture = text;
            say("Look set to a head texture.", false);
        } else {
            Identifier id = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text);
            if (id == null) {
                say("Not an item id: " + text, true);
                return;
            }
            e.iconModel = id.toString();
            e.headTexture = "";
            say("Look set to " + id + ".", false);
        }
        cfg().save();
        refresh();
    }

    /** Copies another item's real model (and head texture) onto the selection. Public for tests. */
    public void copyLookFrom(ItemStack source) {
        copyingLook = false;
        ArmourDyeEntry e = editable();
        if (e == null || source == null || source.isEmpty()) {
            return;
        }
        String head = ArmourDye.headTextureOf(source);
        Identifier model = ArmourDye.itemModelOf(source);
        e.headTexture = head == null ? "" : head;
        e.iconModel = head != null || model == null ? "" : model.toString();
        cfg().save();
        say("Look copied from " + source.getHoverName().getString() + ".", false);
        refresh();
    }

    /** Removes the selection's look entirely. Public for tests. */
    public void removeSelected() {
        if (selectedKey == null || cfg().get(selectedKey) == null) {
            say("Nothing to remove.", true);
            return;
        }
        cfg().remove(selectedKey);
        cfg().save();
        say("Removed the look from " + selectedLabel + ".", false);
        refresh();
    }

    public String selectedKey() {
        return selectedKey;
    }

    public String status() {
        return status;
    }

    private void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
    }

    private void refresh() {
        if (this.minecraft != null) {
            String typed = lookBox == null ? "" : lookBox.getValue();
            rebuildWidgets();
            if (lookBox != null) {
                lookBox.setValue(typed);
            }
        }
    }

    private static String cycle(List<String> options, String current, boolean forward) {
        if (options.isEmpty()) {
            return "";
        }
        int idx = options.indexOf(current);
        if (forward) {
            if (idx < 0) {
                return options.get(0);
            }
            return idx + 1 >= options.size() ? "" : options.get(idx + 1);
        }
        if (idx < 0) {
            return options.get(options.size() - 1);
        }
        return idx == 0 ? "" : options.get(idx - 1);
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
        panelX = (this.width - PANEL_W) / 2;
        panelY = Math.max(2, (this.height - PANEL_H) / 2);
        gridX = panelX + 8;
        gridY = panelY + 20;
        rightX = gridX + 9 * SLOT + 10;
        rightW = panelX + PANEL_W - 8 - rightX;
        listY = gridY + SLOT + 4 + 3 * SLOT + 4 + SLOT + 14;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + PANEL_W - 48, panelY + 4, 40, 14).build());

        ArmourDyeEntry e = entry();
        boolean has = selectedKey != null;
        boolean armour = ArmourDye.isArmour(selectedStack);
        int half = (rightW - 4) / 2;
        int y = gridY + 64;

        boolean canSwitch = !selectedStack.isEmpty() && ArmourDye.uuidOf(selectedStack) != null
                && ArmourDye.identityOf(selectedStack) != null;
        SettingsButtonWidget applies = SettingsButtonWidget.builder(Component.literal("Applies To: "
                        + (selectedKey != null && selectedKey.startsWith(ArmourDyeEntry.UUID_PREFIX) ? "This Item" : "Every Copy")),
                b -> toggleAppliesTo()).bounds(rightX + 52, gridY + 14, rightW - 52, 16).build();
        applies.active = canSwitch;
        addRenderableWidget(applies);

        SettingsButtonWidget colour = SettingsButtonWidget.builder(ColorSwatch.label("Colour", e == null ? 0xFFFFFFFF : e.color), b -> {
                    ArmourDyeEntry cur = entry();
                    McOpen.colourPicker(this, selectedLabel, cur == null ? 0xFFFFFFFF : cur.color, this::setColour);
                }).bounds(rightX, y, half, 18).build();
        colour.active = has;
        addRenderableWidget(colour);
        SettingsButtonWidget use = SettingsButtonWidget.builder(Component.literal("Use Colour: "
                        + (e != null && e.colorEnabled ? "§aON" : "§cOFF")), b -> toggleUseColour())
                .bounds(rightX + half + 4, y, rightW - half - 4, 18).build();
        use.active = has;
        addRenderableWidget(use);
        y += 22;

        SettingsButtonWidget skin = SettingsButtonWidget.builder(Component.literal("Skin: " + (e == null ? "None" : e.skin.label)),
                        b -> cycleSkin(true)).secondaryPress(b -> cycleSkin(false))
                .bounds(rightX, y, half, 18).build();
        skin.active = has && armour;
        addRenderableWidget(skin);
        SettingsButtonWidget trim = SettingsButtonWidget.builder(Component.literal("Trim: " + shortId(e == null ? "" : e.trimMaterial)),
                        b -> cycleTrim(true, true)).secondaryPress(b -> cycleTrim(true, false))
                .bounds(rightX + half + 4, y, rightW - half - 4, 18).build();
        trim.active = has && armour;
        addRenderableWidget(trim);
        y += 22;

        SettingsButtonWidget pattern = SettingsButtonWidget.builder(Component.literal("Pattern: " + shortId(e == null ? "" : e.trimPattern)),
                        b -> cycleTrim(false, true)).secondaryPress(b -> cycleTrim(false, false))
                .bounds(rightX, y, half, 18).build();
        pattern.active = has && armour;
        addRenderableWidget(pattern);
        SettingsButtonWidget remove = SettingsButtonWidget.builder(Component.literal("§cRemove"), b -> removeSelected())
                .bounds(rightX + half + 4, y, rightW - half - 4, 18).build();
        remove.active = e != null;
        addRenderableWidget(remove);
        y += 22;

        lookBox = new ThemedEditBox(this.font, rightX, y + 1, rightW - 40, 16, Component.literal("Look"))
                .hint(e != null && !e.iconModel.isBlank() ? shortId(e.iconModel)
                        : e != null && !e.headTexture.isBlank() ? "(head texture)" : "item id, e.g. diamond_sword");
        lookBox.setMaxLength(4096);
        lookBox.active = has;
        addRenderableWidget(lookBox);
        SettingsButtonWidget set = SettingsButtonWidget.builder(Component.literal("Set"), b -> setLook(lookBox.getValue()))
                .bounds(rightX + rightW - 36, y, 36, 18).build();
        set.active = has;
        addRenderableWidget(set);
        y += 22;

        SettingsButtonWidget copy = SettingsButtonWidget.builder(Component.literal(copyingLook ? "Pick item..." : "Copy Look"),
                b -> {
                    copyingLook = !copyingLook;
                    b.setMessage(Component.literal(copyingLook ? "Pick item..." : "Copy Look"));
                }).bounds(rightX, y, half, 18).build();
        copy.active = has;
        addRenderableWidget(copy);
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("Clear Look"), b -> setLook(""))
                .bounds(rightX + half + 4, y, rightW - half - 4, 18).build();
        clear.active = e != null && (!e.iconModel.isBlank() || !e.headTexture.isBlank());
        addRenderableWidget(clear);
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
        // In the menu's theme (sweep-b, 2026-10-13); cells, rows and buttons keep their places.
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, PANEL_W, PANEL_H);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, PANEL_W - 6, 16, "Custom Items");
        if (!cfg().isEnabledRaw()) {
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(
                    "Custom Items is OFF in its tab - looks are saved but not drawn.", PANEL_W - 80 - 54),
                    panelX + 80, panelY + 6, ScreenChrome.bad());
        }


        ItemStack hover = ItemStack.EMPTY;
        for (int i = 0; i < 40; i++) {
            int[] p = cellPos(i);
            ItemStack stack = cellStack(i);
            boolean sel = !selectedStack.isEmpty() && stack == selectedStack;
            ScreenChrome.slot(graphics, p[0], p[1], SLOT, sel, false);
            if (stack.isEmpty()) {
                continue;
            }
            graphics.item(stack, p[0] + 1, p[1] + 1);
            graphics.itemDecorations(this.font, stack, p[0] + 1, p[1] + 1);
            if (ArmourDye.entryFor(stack) != null) {
                graphics.fill(p[0] + SLOT - 4, p[1] + 1, p[0] + SLOT - 1, p[1] + 4, MenuStyle.OR); // has a look
            }
            if (mouseX >= p[0] && mouseX < p[0] + SLOT && mouseY >= p[1] && mouseY < p[1] + SLOT) {
                graphics.fill(p[0] + 1, p[1] + 1, p[0] + SLOT - 1, p[1] + SLOT - 1, 0x40FFFFFF);
                hover = stack;
            }
        }

        // Saved looks.
        List<ArmourDyeEntry> entries = cfg().getEntries();
        MenuStyle.text(graphics, "SAVED LOOKS (" + entries.size() + ")", gridX, listY - 11, MenuStyle.DIM);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, entries.size() - LIST_ROWS)));
        ScreenChrome.well(graphics, gridX, listY, 9 * SLOT, LIST_ROWS * LIST_ROW + 1);
        for (int row = 0; row < LIST_ROWS && listScroll + row < entries.size(); row++) {
            ArmourDyeEntry e = entries.get(listScroll + row);
            int ry = listY + 1 + row * LIST_ROW;
            boolean sel = e.itemId.equals(selectedKey);
            boolean over = mouseX >= gridX && mouseX < gridX + 9 * SLOT && mouseY >= ry && mouseY < ry + LIST_ROW;
            ScreenChrome.row(graphics, gridX + 1, ry, 9 * SLOT - 2, LIST_ROW, over, sel, (row) % 2 == 1);
            String text = (e.isPerItem() ? "[item] " : "") + e.label;
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(text, 9 * SLOT - 8)), gridX + 4,
                    ry + 2, !e.enabled ? MenuStyle.DIM2 : sel ? MenuStyle.OR_HOVER : MenuStyle.TXT);
        }

        // Right side: preview + name.
        String title = selectedKey == null ? "Click an item to change its look" : selectedLabel;
        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(title, rightW)), rightX, gridY,
                selectedKey == null ? MenuStyle.DIM : MenuStyle.TXT);
        ScreenChrome.well(graphics, rightX, gridY + 12, 48, 48);
        if (!selectedStack.isEmpty()) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(rightX + 4, gridY + 16);
            graphics.pose().scale(2.5f, 2.5f);
            graphics.item(selectedStack, 0, 0);
            graphics.pose().popMatrix();
        }

        MenuStyle.text(graphics, this.font.plainSubstrByWidth(status, PANEL_W - 16), panelX + 8,
                panelY + PANEL_H - 12, statusBad ? ScreenChrome.bad() : ScreenChrome.good());

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
            ItemStack stack = cellStack(cell);
            if (stack.isEmpty()) {
                return true;
            }
            if (copyingLook && selectedKey != null) {
                copyLookFrom(stack);
            } else {
                select(stack);
            }
            return true;
        }
        if (event.x() >= gridX && event.x() < gridX + 9 * SLOT && event.y() >= listY
                && event.y() < listY + LIST_ROWS * LIST_ROW) {
            int row = (int) ((event.y() - listY - 1) / LIST_ROW);
            List<ArmourDyeEntry> entries = cfg().getEntries();
            if (row >= 0 && listScroll + row < entries.size()) {
                selectKey(entries.get(listScroll + row).itemId);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= gridX && mouseX < gridX + 9 * SLOT && mouseY >= listY && mouseY < listY + LIST_ROWS * LIST_ROW) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Opens the shared colour wheel with this menu as its parent. */
    private static final class McOpen {
        static void colourPicker(Screen back, String label, int current, java.util.function.IntConsumer onChange) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreenAndShow(new ColorPickerScreen(back, label + " Colour", current, 0xFFFFFFFF, onChange));
        }
    }
    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, PANEL_W, PANEL_H};
    }

    @Override
    public String themeId() {
        return "custom_items";
    }
}
