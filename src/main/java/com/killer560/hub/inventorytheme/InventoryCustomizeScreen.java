package com.killer560.hub.inventorytheme;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Inventory Theme's Customize screen (killer560, 2026-10-18: "if you have it on custom there will be a bar that says
 * customize where you click it and it opens a new menu for you to fully customize how you want it all to look"). Opened
 * from the Customize row that the Inventory Theme tab shows while its Theme is Custom. It holds everything the Custom look
 * is made of: the Base colours (Amber, Dark or Light), and the slot and accent colours, each following the Base or a
 * colour you pick. Every change is saved at once and drawn at once in the next inventory you open.
 */
public final class InventoryCustomizeScreen extends Screen implements MenuThemedScreen {

    private final Screen parent;
    private int[] themedBounds = new int[4];

    public InventoryCustomizeScreen(Screen parent) {
        super(Component.literal("Customise Inventory Theme"));
        this.parent = parent;
    }

    @Override
    public int[] themedPanelBounds() {
        return themedBounds.clone();
    }

    @Override
    protected void init() {
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        int width = 220;
        int x = this.width / 2 - width / 2;
        int y = this.height / 2 - 70;

        this.addRenderableWidget(SettingsButtonWidget.cycling(baseText(cfg), (btn, dir) -> {
                    cfg.setTheme(SettingsButtonWidget.step(cfg.getTheme(), dir));
                    cfg.save();
                    this.rebuildWidgets();
                }).bounds(x, y, width, 20).build());
        y += 26;

        this.addRenderableWidget(SettingsButtonWidget.cycling(slotModeText(cfg), (btn, dir) -> {
                    cfg.setUseCustomSlotColor(!cfg.isUseCustomSlotColor());
                    cfg.save();
                    this.rebuildWidgets();
                }).bounds(x, y, width, 20).build());
        y += 24;
        if (cfg.isUseCustomSlotColor()) {
            this.addRenderableWidget(SettingsButtonWidget.builder(ColorSwatch.label("Slot Colour", cfg.getSlotColor()), btn ->
                            McCompat.setScreen(Minecraft.getInstance(), new ColorPickerScreen(this, "Slot Colour",
                                    cfg.getSlotColor(), cfg.getTheme().invSlotBg, argb -> {
                                cfg.setCustomSlotColor(argb);
                                cfg.save();
                            }))).bounds(x, y, width, 20).build());
            y += 24;
        }

        this.addRenderableWidget(SettingsButtonWidget.cycling(accentModeText(cfg), (btn, dir) -> {
                    cfg.setUseCustomAccent(!cfg.isUseCustomAccent());
                    cfg.save();
                    this.rebuildWidgets();
                }).bounds(x, y, width, 20).build());
        y += 24;
        if (cfg.isUseCustomAccent()) {
            this.addRenderableWidget(SettingsButtonWidget.builder(ColorSwatch.label("Accent Colour", cfg.getAccentColor()), btn ->
                            McCompat.setScreen(Minecraft.getInstance(), new ColorPickerScreen(this, "Accent Colour",
                                    cfg.getAccentColor(), cfg.getTheme().invAccent, argb -> {
                                cfg.setCustomAccentColor(argb);
                                cfg.save();
                            }))).bounds(x, y, width, 20).build());
            y += 24;
        }

        y += 8;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(x, y, width, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        int px = this.width / 2 - 130;
        int py = this.height / 2 - 104;
        themedBounds = new int[]{px, py, 260, 214};
        ScreenChrome.frame(graphics, px, py, 260, 214);
        ScreenChrome.headerBar(graphics, px, py, 260);
        ScreenChrome.titleCentred(graphics, "Customise Inventory Theme", this.width / 2, py + 10, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component baseText(InventoryThemeConfig cfg) {
        PanelTheme theme = cfg.getTheme();
        String colour = theme == PanelTheme.AMBER ? "§6" : theme == PanelTheme.DARK ? "§8" : "§f";
        return Component.literal("Base: " + colour + theme.label);
    }

    private static Component slotModeText(InventoryThemeConfig cfg) {
        // Not "Slot Color:" - that is the picker's key below (the tooltip key is the label cut at the first ':').
        return Component.literal("Slot Colour Source: " + (cfg.isUseCustomSlotColor() ? "Custom" : "Base"));
    }

    private static Component accentModeText(InventoryThemeConfig cfg) {
        // Deliberately NOT prefixed "Accent Color:" - the swatch button uses that exact key.
        return Component.literal("Accent Source: " + (cfg.isUseCustomAccent() ? "Custom" : "Base"));
    }
}
