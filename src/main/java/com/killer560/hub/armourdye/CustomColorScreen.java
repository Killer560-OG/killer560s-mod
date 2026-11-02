package com.killer560.hub.armourdye;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Customize Custom Color page (2026-10-18; rows redone 2026-11-01). Left: the selected item in a large preview - an
 * armor piece is worn by an armor stand, so a trim pattern shows (an inventory icon draws only the trim's color, which is
 * why "this only ever shows one trim pattern no matter which one I select"). Right, one row per change, top to bottom,
 * each with its clear on the right (killer560, 2026-11-01: "make the things I can change on that menu run vertically and on
 * the right side they have a clear ___ option"):
 * <ul>
 *   <li>Applies To - this item or every copy.</li>
 *   <li>Color | Reset Color. The color picker carries a Hypixel Dyes button (the dye list moved under Color).</li>
 *   <li>Skin | Clear Skin - Hypixel item skins only ({@link ItemSkins}); "Skin: N/A" when the item has none.</li>
 *   <li>Trim Color | Clear Trim Color, Trim Pattern | Clear Trim Pattern (armor only).</li>
 * </ul>
 * Every change saves at once and the preview redraws with it. Back returns to the main Custom Items page.
 */
public final class CustomColorScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_W = CustomItemsScreen.MAX_W;
    private static final int PANEL_H = CustomItemsScreen.MAX_H;
    private static final int ROW_STEP = 26;
    private static final int CLEAR_W = 110;

    private final CustomItemsScreen main;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int controlX;
    private int controlW;
    /** The armor stand that wears the previewed piece; built on first draw, never added to the world. */
    private ArmorStand stand;
    private ItemStack standWears = ItemStack.EMPTY;

    public CustomColorScreen(CustomItemsScreen main) {
        super(Component.literal("Customize Custom Color"));
        this.main = main;
    }

    private int row(int k) {
        return panelY + 36 + k * ROW_STEP;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = Math.min(PANEL_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        controlX = panelX + 150;
        controlW = panelX + panelW - 8 - controlX;
        int mainW = controlW - CLEAR_W - 4;
        int clearX = controlX + mainW + 4;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 7 - 48, panelY + 3, 48, 14).build());

        ArmourDyeEntry e = main.entry();
        boolean has = main.selectedKey() != null;
        boolean armour = ArmourDye.isArmour(main.selectedStack());

        // Row 0: whose look this is - this item, or every copy of it.
        boolean canSwitch = !main.selectedStack().isEmpty() && ArmourDye.uuidOf(main.selectedStack()) != null
                && ArmourDye.identityOf(main.selectedStack()) != null;
        SettingsButtonWidget applies = SettingsButtonWidget.builder(Component.literal("Applies To: "
                        + (has && main.selectedKey().startsWith(ArmourDyeEntry.UUID_PREFIX) ? "This Item" : "Every Copy")),
                b -> {
                    main.toggleAppliesTo();
                    rebuildWidgets();
                }).bounds(controlX, row(0), controlW, 20).build();
        applies.active = canSwitch;
        addRenderableWidget(applies);

        // Row 1: Color (its picker has the Hypixel Dyes list) | Reset Color.
        boolean coloured = e != null && e.colorEnabled;
        SettingsButtonWidget colour = SettingsButtonWidget.builder(
                        coloured ? ColorSwatch.label("Color", e.color) : Component.literal("Color: Off"),
                        b -> openColourPicker())
                .bounds(controlX, row(1), mainW, 20).build();
        colour.active = has;
        addRenderableWidget(colour);
        SettingsButtonWidget reset = SettingsButtonWidget.builder(Component.literal("Reset Color"), b -> {
                    main.resetColour();
                    rebuildWidgets();
                }).bounds(clearX, row(1), CLEAR_W, 20).build();
        reset.active = coloured;
        addRenderableWidget(reset);

        // Row 2: Skin (Hypixel item skins only) | Clear Skin.
        List<ItemSkins.Skin> skins = ItemSkins.forItem(main.selectedStack());
        String skinText = e != null && !e.skinName.isBlank() ? e.skinName : skins.isEmpty() ? "N/A" : "None";
        SettingsButtonWidget skin = SettingsButtonWidget.builder(Component.literal("Skin: " + skinText),
                        b -> minecraft.setScreenAndShow(new HypixelSkinScreen(this, skins,
                                e == null ? "" : e.skinName, main::applyHypixelSkin)))
                .bounds(controlX, row(2), mainW, 20).build();
        skin.active = has && !skins.isEmpty();
        addRenderableWidget(skin);
        SettingsButtonWidget clearSkin = SettingsButtonWidget.builder(Component.literal("Clear Skin"), b -> {
                    main.clearSkin();
                    rebuildWidgets();
                }).bounds(clearX, row(2), CLEAR_W, 20).build();
        clearSkin.active = e != null && (!e.skinName.isBlank() || e.skin != ArmourSkin.NONE);
        addRenderableWidget(clearSkin);

        // Rows 3 and 4: Trim Color and Trim Pattern (armor only), each a dropdown, each with its clear.
        addTrimRow(true, "Trim Color", e == null ? "" : e.trimMaterial, row(3), mainW, clearX, has && armour);
        addTrimRow(false, "Trim Pattern", e == null ? "" : e.trimPattern, row(4), mainW, clearX, has && armour);
    }

    private void addTrimRow(boolean material, String label, String value, int y, int mainW, int clearX, boolean enabled) {
        SettingsButtonWidget pick = SettingsButtonWidget.builder(
                        Component.literal(label + ": " + ChoiceListScreen.displayName(value)), b -> openChoices(material))
                .bounds(controlX, y, mainW, 20).build();
        pick.active = enabled;
        addRenderableWidget(pick);
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("Clear " + label), b -> {
                    main.clearTrim(material);
                    rebuildWidgets();
                }).bounds(clearX, y, CLEAR_W, 20).build();
        clear.active = enabled && value != null && !value.isBlank();
        addRenderableWidget(clear);
    }

    /** The color picker, with a Hypixel Dyes button that opens the dye list; a picked dye lands back in the picker. */
    private void openColourPicker() {
        ArmourDyeEntry cur = main.entry();
        int start = cur == null || !cur.colorEnabled ? 0xFFFFFFFF : cur.color;
        ColorPickerScreen picker = new ColorPickerScreen(this, main.selectedLabel() + " Color", start, 0xFFFFFFFF,
                main::setColour);
        picker.withExtraButton("Hypixel Dyes...", p -> Minecraft.getInstance().setScreenAndShow(
                new DyePickerScreen(p, p::setColour, () -> main.resetColour())));
        minecraft.setScreenAndShow(picker);
    }

    private void openChoices(boolean material) {
        ArmourDyeEntry e = main.entry();
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(material ? ArmourTrims.materials() : ArmourTrims.patterns());
        String cur = e == null ? "" : (material ? e.trimMaterial : e.trimPattern);
        minecraft.setScreenAndShow(new ChoiceListScreen(this, material ? "Trim Color" : "Trim Pattern", options, cur,
                value -> main.setTrimChoice(material, value)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Customize Custom Color");

        // The large preview: the item with its look, always (this page is for changing the look).
        ItemStack stack = main.selectedStack();
        String title = main.selectedKey() == null ? "No item selected" : main.selectedLabel();
        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(title, 130)), panelX + 12,
                panelY + 24, main.selectedKey() == null ? MenuStyle.DIM : MenuStyle.TXT);
        ScreenChrome.well(graphics, panelX + 12, panelY + 40, 124, 124);
        if (!stack.isEmpty()) {
            ArmorStand wearer = ArmourDye.isArmour(stack) ? standWearing(stack) : null;
            if (wearer != null) {
                InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, panelX + 13, panelY + 41, panelX + 135,
                        panelY + 163, 46, 0.0625f, mouseX, mouseY, wearer);
                graphics.item(stack, panelX + 116, panelY + 144); // the icon too, in the corner
            } else {
                graphics.pose().pushMatrix();
                graphics.pose().translate(panelX + 24, panelY + 52);
                graphics.pose().scale(6f, 6f);
                graphics.item(stack, 0, 0);
                graphics.pose().popMatrix();
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** An armor stand (never added to the world) wearing a copy of {@code stack}, or null without a world. The copy keeps
     *  the stack's id and uuid, so its look is the selection's. */
    private ArmorStand standWearing(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        EquipmentSlot slot = ArmourDye.slotOf(stack);
        if (slot == null) {
            return null;
        }
        try {
            if (stand == null || stand.level() != mc.level) {
                stand = new ArmorStand(mc.level, 0, 0, 0);
                stand.setNoBasePlate(true);
                standWears = ItemStack.EMPTY;
            }
            if (standWears != stack) {
                for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                        EquipmentSlot.FEET}) {
                    stand.setItemSlot(s, s == slot ? stack.copy() : ItemStack.EMPTY);
                }
                standWears = stack;
            }
            return stand;
        } catch (RuntimeException ex) {
            return null; // a preview must never break the menu; the icon is drawn instead
        }
    }

    /** True when the preview is drawn on an armor stand (the selected item is armor). Public for the testkit. */
    public boolean previewOnStand() {
        return stand != null && ArmourDye.isArmour(main.selectedStack()) && standWears == main.selectedStack();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(main);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
