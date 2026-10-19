package com.killer560.hub.armourdye;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Custom Items menu (killer560, 2026-10-08): select an item in your inventory and change its colour, skin, trim,
 * pattern or look. Nothing is ever sent to the server.
 * <p>
 * Left: a search box (2026-10-18: filters the item grid by name, any word typed matches), then his armour, inventory
 * and hotbar (the real stacks, drawn through the overrides). Right: the preview and the controls. Selecting an item
 * shows its CLEAR look (no override drawn) until the Preview button switches to the look it has. The saved-looks list
 * was removed from the menu on 2026-10-18 (killer560: "remove the saved looks"); a look already saved still applies
 * to its item, and it can still be removed from the item's controls.
 */
public class CustomItemsScreen extends Screen implements MenuThemedScreen {

    private static final int SLOT = 22;
    private static final int GRID_W = 9 * SLOT;
    private static final int MAX_W = 460;
    private static final int MAX_H = 300;
    private static final int LEFT_PAD = 8;
    private static final int COL_GAP = 12;
    private static final int ROW_H = 24;
    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private final Screen parent;

    /** The selected real stack (live, from the inventory), or EMPTY when nothing is selected. */
    private ItemStack selectedStack = ItemStack.EMPTY;
    /** The key the selection edits (an id or UUID: key), or null for no selection. */
    private String selectedKey;
    private String selectedLabel = "";
    private boolean copyingLook;
    /** True: the preview draws the clear item (no override). Reset to true on every new selection. */
    private boolean showClear = true;
    /** The search text, kept across rebuilds. Any word typed matches an item whose name contains it. */
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

    private EditBox lookBox;
    private EditBox searchBox;

    public CustomItemsScreen(Screen parent) {
        super(Component.literal("Custom Items"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------------------------------- model

    private static ArmourDyeConfig cfg() {
        return ArmourDyeConfig.getInstance();
    }

    /** The preview switches to showing the look whenever a setting changes. */
    private void showLook() {
        showClear = false;
    }

    /** True when the preview draws the item clear: Show Clear chosen, or nothing applied to the selection. */
    private boolean clearShown() {
        ArmourDyeEntry e = entry();
        return showClear || e == null || e.isEmpty() || !e.enabled;
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
     *  The preview starts on the clear item. Public for tests. */
    public void select(ItemStack stack) {
        copyingLook = false;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        selectedStack = stack;
        selectedLabel = stack.getHoverName().getString();
        showClear = true;
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
        showLook();
    }

    public void toggleUseColour() {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        e.colorEnabled = !e.colorEnabled;
        cfg().save();
        showLook();
        refresh();
    }

    /** Steps through the Hypixel leather colours (from the catalog) and arms the colour. */
    private void stepHypixelDye(int dir) {
        ArmourDyeEntry e = editable();
        if (e == null) {
            return;
        }
        int cur = hypixelDyeIndex(e.color);
        int idx = cur < 0
                ? (dir > 0 ? 0 : HypixelDyes.COLORS.length - 1)
                : SettingsButtonWidget.step(cur, dir, HypixelDyes.COLORS.length);
        e.color = 0xFF000000 | HypixelDyes.COLORS[idx];
        e.colorEnabled = true;
        cfg().save();
        showLook();
        say("Dye: " + HypixelDyes.NAMES[idx] + ".", false);
        refresh();
    }

    /** The index of a Hypixel dye colour matching this ARGB, or -1. */
    static int hypixelDyeIndex(int argb) {
        int rgb = argb & 0xFFFFFF;
        for (int i = 0; i < HypixelDyes.COLORS.length; i++) {
            if (HypixelDyes.COLORS[i] == rgb) {
                return i;
            }
        }
        return -1;
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
        showLook();
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
        showLook();
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
        showLook();
        refresh();
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
        showLook();
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

    /**
     * Search: true when the item name contains ANY word of the query, case-insensitive. A blank query matches all.
     * Package-visible for the item-grid filter and its tests.
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
                .bounds(panelX + panelW - 52, panelY + 3, 44, 16).build());

        searchBox = new ThemedEditBox(this.font, gridX, panelY + 24, GRID_W, 18, Component.literal("Search"))
                .hint("search items by name");
        searchBox.setMaxLength(64);
        searchBox.setResponder(text -> query = text);
        searchBox.setValue(query);
        addRenderableWidget(searchBox);

        ArmourDyeEntry e = entry();
        boolean has = selectedKey != null;
        boolean armour = ArmourDye.isArmour(selectedStack);
        int half = (rightW - 4) / 2;
        int rest = rightW - half - 4;
        int x2 = rightX + half + 4;

        boolean canSwitch = !selectedStack.isEmpty() && ArmourDye.uuidOf(selectedStack) != null
                && ArmourDye.identityOf(selectedStack) != null;
        SettingsButtonWidget applies = SettingsButtonWidget.builder(Component.literal("Applies To: "
                        + (selectedKey != null && selectedKey.startsWith(ArmourDyeEntry.UUID_PREFIX) ? "This Item" : "Every Copy")),
                b -> toggleAppliesTo()).bounds(rightX, row(0), rightW, BTN_H).build();
        applies.active = canSwitch;
        addRenderableWidget(applies);

        // Row 1: colour picker and the on/off for it.
        SettingsButtonWidget colour = SettingsButtonWidget.builder(ColorSwatch.label("Color", e == null ? 0xFFFFFFFF : e.color), b -> {
                    ArmourDyeEntry cur = entry();
                    McOpen.colourPicker(this, selectedLabel, cur == null ? 0xFFFFFFFF : cur.color, this::setColour);
                }).bounds(rightX, row(1), half, BTN_H).build();
        colour.active = has;
        addRenderableWidget(colour);
        SettingsButtonWidget use = SettingsButtonWidget.builder(Component.literal("Use Color: "
                        + (e != null && e.colorEnabled ? "§aON" : "§cOFF")), b -> toggleUseColour())
                .bounds(x2, row(1), rest, BTN_H).build();
        use.active = has;
        addRenderableWidget(use);

        // Row 2: Hypixel leather colour dropdown (cycles the catalog colours).
        int dye = e == null ? -1 : hypixelDyeIndex(e.color);
        SettingsButtonWidget hypixelDye = SettingsButtonWidget.cycling(
                        Component.literal("Hypixel Dye: " + (dye < 0 ? "pick" : HypixelDyes.NAMES[dye])),
                        (b, dir) -> stepHypixelDye(dir))
                .bounds(rightX, row(2), rightW, BTN_H).build();
        hypixelDye.active = has;
        addRenderableWidget(hypixelDye);

        // Row 3: armour skin dropdown, and the Hypixel item skin picker.
        SettingsButtonWidget skin = SettingsButtonWidget.cycling(
                        Component.literal("Skin: " + (e == null ? "None" : e.skin.label)),
                        (b, dir) -> cycleSkin(dir > 0))
                .bounds(rightX, row(3), half, BTN_H).build();
        skin.active = has && armour;
        addRenderableWidget(skin);
        SettingsButtonWidget hypixelSkin = SettingsButtonWidget.builder(Component.literal("Hypixel Skin..."),
                        b -> minecraft.setScreenAndShow(new HypixelSkinScreen(this, this::applyHypixelSkin)))
                .bounds(x2, row(3), rest, BTN_H).build();
        hypixelSkin.active = has;
        addRenderableWidget(hypixelSkin);

        // Row 4: trim and pattern dropdowns.
        SettingsButtonWidget trim = SettingsButtonWidget.cycling(
                        Component.literal("Trim: " + shortId(e == null ? "" : e.trimMaterial)),
                        (b, dir) -> cycleTrim(true, dir > 0))
                .bounds(rightX, row(4), half, BTN_H).build();
        trim.active = has && armour;
        addRenderableWidget(trim);
        SettingsButtonWidget pattern = SettingsButtonWidget.cycling(
                        Component.literal("Pattern: " + shortId(e == null ? "" : e.trimPattern)),
                        (b, dir) -> cycleTrim(false, dir > 0))
                .bounds(x2, row(4), rest, BTN_H).build();
        pattern.active = has && armour;
        addRenderableWidget(pattern);

        // Row 5: free-text Look and its Set button.
        lookBox = new ThemedEditBox(this.font, rightX, row(5) + 2, rightW - 40, 16, Component.literal("Look"))
                .hint(e != null && !e.iconModel.isBlank() ? shortId(e.iconModel)
                        : e != null && !e.headTexture.isBlank() ? "(head texture)" : "item id, e.g. diamond_sword");
        lookBox.setMaxLength(4096);
        lookBox.active = has;
        addRenderableWidget(lookBox);
        SettingsButtonWidget set = SettingsButtonWidget.builder(Component.literal("Set"), b -> setLook(lookBox.getValue()))
                .bounds(rightX + rightW - 36, row(5), 36, BTN_H).build();
        set.active = has;
        addRenderableWidget(set);

        // Row 6: copy or clear a Look.
        SettingsButtonWidget copy = SettingsButtonWidget.builder(Component.literal(copyingLook ? "Pick item..." : "Copy Look"),
                b -> {
                    copyingLook = !copyingLook;
                    b.setMessage(Component.literal(copyingLook ? "Pick item..." : "Copy Look"));
                }).bounds(rightX, row(6), half, BTN_H).build();
        copy.active = has;
        addRenderableWidget(copy);
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("Clear Look"), b -> setLook(""))
                .bounds(x2, row(6), rest, BTN_H).build();
        clear.active = e != null && (!e.iconModel.isBlank() || !e.headTexture.isBlank());
        addRenderableWidget(clear);

        // Row 7: preview switch, and removing the look.
        SettingsButtonWidget preview = SettingsButtonWidget.builder(
                        Component.literal(showClear ? "Show With Look" : "Show Clear Item"),
                        b -> {
                            showClear = !showClear;
                            refresh();
                        }).bounds(rightX, row(7), half, BTN_H).build();
        preview.active = !selectedStack.isEmpty();
        addRenderableWidget(preview);
        SettingsButtonWidget remove = SettingsButtonWidget.builder(Component.literal("§cRemove Look"), b -> removeSelected())
                .bounds(x2, row(7), rest, BTN_H).build();
        remove.active = e != null;
        addRenderableWidget(remove);
    }

    /** Y of right-column control {@code k}. */
    private int row(int k) {
        return panelY + 100 + k * ROW_H;
    }

    private static final int BTN_H = 20;

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

    /** The stack in a cell when it passes the search, else EMPTY (a non-matching cell is drawn and clicked as empty). */
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
                    "Custom Items is OFF in its tab - looks are saved but not drawn.", panelW - 80 - 54),
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

        // Right side: title, preview (clear or with the look), then the controls.
        String title = selectedKey == null ? "Click an item to change its look" : selectedLabel;
        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(title, rightW)), rightX, panelY + 24,
                selectedKey == null ? MenuStyle.DIM : MenuStyle.TXT);
        if (selectedKey != null) {
            MenuStyle.text(graphics, clearShown() ? "Showing the clear item" : "Showing the look",
                    rightX, panelY + 34, MenuStyle.DIM);
        }
        ScreenChrome.well(graphics, rightX, panelY + 44, 52, 52);
        if (!selectedStack.isEmpty()) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(rightX + 6, panelY + 50);
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
                copyLookFrom(stack);
            } else {
                select(stack);
            }
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

    /** Opens the shared colour wheel with this menu as its parent. */
    private static final class McOpen {
        static void colourPicker(Screen back, String label, int current, java.util.function.IntConsumer onChange) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreenAndShow(new ColorPickerScreen(back, label + " Color", current, 0xFFFFFFFF, onChange));
        }
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
