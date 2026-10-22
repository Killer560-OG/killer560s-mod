package com.killer560.hub.armourdye;

import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Custom Color page (2026-10-18): the selected item in a large preview, with its look controls beside it - the
 * colour, the skin (armour), the Hypixel item skin, and the trim and pattern dropdowns. Every change saves at once and
 * the preview redraws with it. Back returns to the main Custom Items page.
 */
public final class CustomColorScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_W = CustomItemsScreen.MAX_W;
    private static final int PANEL_H = CustomItemsScreen.MAX_H;
    private static final int ROW_STEP = 26;

    private final CustomItemsScreen main;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int controlX;
    private int controlW;

    public CustomColorScreen(CustomItemsScreen main) {
        super(Component.literal("Custom Colour"));
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
        int half = (controlW - 4) / 2;
        int rest = controlW - half - 4;
        int x2 = controlX + half + 4;

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

        // Row 1: colour, and its on/off.
        SettingsButtonWidget colour = SettingsButtonWidget.builder(
                        ColorSwatch.label("Colour", e == null ? 0xFFFFFFFF : e.color),
                        b -> {
                            ArmourDyeEntry cur = main.entry();
                            CustomItemsScreen.colourPicker(this, main.selectedLabel(),
                                    cur == null ? 0xFFFFFFFF : cur.color, main::setColour);
                        }).bounds(controlX, row(1), half, 20).build();
        colour.active = has;
        addRenderableWidget(colour);
        SettingsButtonWidget use = SettingsButtonWidget.builder(Component.literal("Use Colour: "
                        + (e != null && e.colorEnabled ? "§aON" : "§cOFF")), b -> {
                    main.toggleUseColour();
                    rebuildWidgets();
                }).bounds(x2, row(1), rest, 20).build();
        use.active = has;
        addRenderableWidget(use);

        // Row 2: armour skin (armour only), and the Hypixel item skin list.
        SettingsButtonWidget skin = SettingsButtonWidget.cycling(
                        Component.literal("Skin: " + (e == null ? "None" : e.skin.label)),
                        (b, dir) -> {
                            main.cycleSkin(dir > 0);
                            rebuildWidgets();
                        }).bounds(controlX, row(2), half, 20).build();
        skin.active = has && armour;
        addRenderableWidget(skin);
        SettingsButtonWidget hypixelSkin = SettingsButtonWidget.builder(Component.literal("Hypixel Skin..."),
                        b -> minecraft.setScreenAndShow(new HypixelSkinScreen(this, main.selectedLabel(), item -> {
                            main.applyHypixelSkin(item);
                        })))
                .bounds(x2, row(2), rest, 20).build();
        hypixelSkin.active = has;
        addRenderableWidget(hypixelSkin);

        // Row 3: trim and pattern dropdowns. Each opens a list of its choices, the current one marked.
        SettingsButtonWidget trim = SettingsButtonWidget.builder(
                        Component.literal("Trim: " + shortId(e == null ? "" : e.trimMaterial)),
                        b -> openChoices(true))
                .bounds(controlX, row(3), half, 20).build();
        trim.active = has && armour;
        addRenderableWidget(trim);
        SettingsButtonWidget pattern = SettingsButtonWidget.builder(
                        Component.literal("Pattern: " + shortId(e == null ? "" : e.trimPattern)),
                        b -> openChoices(false))
                .bounds(x2, row(3), rest, 20).build();
        pattern.active = has && armour;
        addRenderableWidget(pattern);

        // Row 4: every Hypixel dye (HypixelDyes, the hex each dye's lore prints); picking one sets the colour.
        SettingsButtonWidget dye = SettingsButtonWidget.builder(Component.literal("Dye..."),
                        b -> minecraft.setScreenAndShow(new DyePickerScreen(this, main::setColour)))
                .bounds(controlX, row(4), controlW, 20).build();
        dye.active = has;
        addRenderableWidget(dye);
    }

    private void openChoices(boolean material) {
        ArmourDyeEntry e = main.entry();
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(material ? ArmourTrims.materials() : ArmourTrims.patterns());
        String cur = e == null ? "" : (material ? e.trimMaterial : e.trimPattern);
        minecraft.setScreenAndShow(new ChoiceListScreen(this, material ? "Trim" : "Pattern", options, cur,
                value -> main.setTrimChoice(material, value)));
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "§8Off";
        }
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Custom Colour");

        // The large preview: the item with its look, always (this page is for changing the look).
        ItemStack stack = main.selectedStack();
        String title = main.selectedKey() == null ? "No item selected" : main.selectedLabel();
        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(title, 130)), panelX + 12,
                panelY + 24, main.selectedKey() == null ? MenuStyle.DIM : MenuStyle.TXT);
        ScreenChrome.well(graphics, panelX + 12, panelY + 40, 124, 124);
        if (!stack.isEmpty()) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(panelX + 24, panelY + 52);
            graphics.pose().scale(6f, 6f);
            graphics.item(stack, 0, 0);
            graphics.pose().popMatrix();
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        MenuStyle.text(graphics, this.font.plainSubstrByWidth(main.status(), panelW - 24), panelX + 12,
                panelY + panelH - 14, ScreenChrome.good());
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
