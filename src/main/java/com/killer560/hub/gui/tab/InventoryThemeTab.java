package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Custom Inventory Overlay settings - killer560's item 5.8, "Custom inventory overlay in the mod's
 *  theme." See {@link com.killer560.hub.inventorytheme.InventoryThemeFeature}'s class doc for exactly
 *  which vanilla screens/draws this re-skins. In-panel explanatory text is intentionally left out per
 *  the mod-wide "no in-panel paragraphs" rule - hover each control for its tooltip. */
public class InventoryThemeTab extends BaseTab {

    public InventoryThemeTab() {
        super("Inventory Theme");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(enabledText(cfg), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(scopeText(cfg), btn -> {
                    cfg.setHypixelOnly(!cfg.isHypixelOnly());
                    cfg.save();
                    btn.setMessage(scopeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        double opacityNorm = (cfg.getBackgroundOpacity() - InventoryThemeConfig.MIN_OPACITY)
                / (InventoryThemeConfig.MAX_OPACITY - InventoryThemeConfig.MIN_OPACITY);
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, opacityText(cfg), opacityNorm) {
            @Override
            protected void updateMessage() {
                setMessage(opacityText(cfg));
            }

            @Override
            protected void applyValue() {
                float newOpacity = (float) (InventoryThemeConfig.MIN_OPACITY
                        + this.value * (InventoryThemeConfig.MAX_OPACITY - InventoryThemeConfig.MIN_OPACITY));
                cfg.setBackgroundOpacity(newOpacity);
                cfg.save();
            }
        });
        y += 26;

        widgets.add(SettingsButtonWidget.builder(accentModeText(cfg), btn -> {
                    cfg.setUseCustomAccent(!cfg.isUseCustomAccent());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isUseCustomAccent()) {
            widgets.add(SettingsButtonWidget.builder(
                    ColorSwatch.label("Accent Color", cfg.getAccentColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Accent Color",
                                cfg.getAccentColor(), InventoryThemeConfig.THEME_ACCENT, argb -> {
                            cfg.setCustomAccentColor(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 24;
        }

        return widgets;
    }

    private static Component enabledText(InventoryThemeConfig cfg) {
        return Component.literal("Inventory Theme: " + (cfg.isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component scopeText(InventoryThemeConfig cfg) {
        return Component.literal("Applies To: " + (cfg.isHypixelOnly() ? "Hypixel/p3sim Menus Only" : "Every Container"));
    }

    private static Component opacityText(InventoryThemeConfig cfg) {
        return Component.literal(String.format("Background Opacity: %.0f%%", cfg.getBackgroundOpacity() * 100));
    }

    private static Component accentModeText(InventoryThemeConfig cfg) {
        // Deliberately NOT prefixed "Accent Color:" - the swatch button below already uses that exact
        // key (see ColorSwatch.label("Accent Color", ...)); the tooltip key is the label cut at the
        // first ':', so two different widgets both starting "Accent Color:" would collide onto the
        // same tooltip entry.
        return Component.literal("Accent Source: " + (cfg.isUseCustomAccent() ? "Custom" : "Mod Theme"));
    }
}
