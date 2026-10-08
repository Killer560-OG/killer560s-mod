package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.inventorytheme.InventoryThemeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

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

        // killer560: "Add a setting to hide the effects in your inventory" - independent of the reskin
        // above (it keeps working with Inventory Theme off), so it's placed here rather than below the
        // enabled-gate return.
        widgets.add(SettingsButtonWidget.builder(hideEffectsText(cfg), btn -> {
                    cfg.setHidePotionEffects(!cfg.isHidePotionEffects());
                    cfg.save();
                    btn.setMessage(hideEffectsText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(scopeText(cfg), btn -> {
                    cfg.setHypixelOnly(!cfg.isHypixelOnly());
                    cfg.save();
                    btn.setMessage(scopeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // Amber -> Dark -> Light, the same three as the Storage Overlay (killer560, 2026-10-07). Amber is the look this
        // had before; Dark and Light swap the orange for neutral greys.
        widgets.add(SettingsButtonWidget.builder(themeText(cfg), btn -> {
                    cfg.setTheme(cfg.getTheme().next());
                    cfg.save();
                    requestRebuild.run();
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

        // Line Width: whole screen pixels, MIN_LINE_WIDTH..MAX_LINE_WIDTH - the slider spans exactly the setter's clamp.
        int lineRange = InventoryThemeConfig.MAX_LINE_WIDTH - InventoryThemeConfig.MIN_LINE_WIDTH;
        double lineNorm = (cfg.getLineWidth() - InventoryThemeConfig.MIN_LINE_WIDTH) / (double) lineRange;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, lineWidthText(cfg), lineNorm) {
            @Override
            protected void updateMessage() {
                setMessage(lineWidthText(cfg));
            }

            @Override
            protected void applyValue() {
                int snapped = InventoryThemeConfig.MIN_LINE_WIDTH + (int) Math.round(this.value * lineRange);
                cfg.setLineWidth(snapped);
                cfg.save();
                this.value = (snapped - InventoryThemeConfig.MIN_LINE_WIDTH) / (double) lineRange;
            }
        });
        y += 26;

        widgets.add(SettingsButtonWidget.builder(hotbarText(cfg), btn -> {
                    cfg.setThemeHotbar(!cfg.isThemeHotbar());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isThemeHotbar()) {
            double scaleNorm = (cfg.getHotbarScale() - InventoryThemeConfig.MIN_HOTBAR_SCALE)
                    / (InventoryThemeConfig.MAX_HOTBAR_SCALE - InventoryThemeConfig.MIN_HOTBAR_SCALE);
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, hotbarScaleText(cfg), scaleNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(hotbarScaleText(cfg));
                }

                @Override
                protected void applyValue() {
                    // Whole percent steps, so the label and the saved value always agree.
                    float raw = (float) (InventoryThemeConfig.MIN_HOTBAR_SCALE + this.value
                            * (InventoryThemeConfig.MAX_HOTBAR_SCALE - InventoryThemeConfig.MIN_HOTBAR_SCALE));
                    cfg.setHotbarScale(Math.round(raw * 100f) / 100f);
                    cfg.save();
                }
            });
            y += 26;
        }

        widgets.add(SettingsButtonWidget.builder(slotModeText(cfg), btn -> {
                    cfg.setUseCustomSlotColor(!cfg.isUseCustomSlotColor());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isUseCustomSlotColor()) {
            widgets.add(SettingsButtonWidget.builder(
                    ColorSwatch.label("Slot Color", cfg.getSlotColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Slot Color",
                                cfg.getSlotColor(), cfg.getTheme().invSlotBg, argb -> {
                            cfg.setCustomSlotColor(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 24;
        }

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
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Accent Color",
                                cfg.getAccentColor(), cfg.getTheme().invAccent, argb -> {
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

    private static Component hideEffectsText(InventoryThemeConfig cfg) {
        return Component.literal("Hide Potion Effects: " + (cfg.isHidePotionEffects() ? "§aON" : "§cOFF"));
    }

    private static Component scopeText(InventoryThemeConfig cfg) {
        return Component.literal("Applies To: " + (cfg.isHypixelOnly() ? "Hypixel/p3sim Menus Only" : "Every Container"));
    }

    private static Component opacityText(InventoryThemeConfig cfg) {
        return Component.literal(String.format("Background Opacity: %.0f%%", cfg.getBackgroundOpacity() * 100));
    }

    private static Component themeText(InventoryThemeConfig cfg) {
        PanelTheme theme = cfg.getTheme();
        String colour = theme == PanelTheme.AMBER ? "§6" : theme == PanelTheme.DARK ? "§8" : "§f";
        return Component.literal("Theme: " + colour + theme.label);
    }

    private static Component lineWidthText(InventoryThemeConfig cfg) {
        return Component.literal("Line Width: " + (cfg.getLineWidth() == 0 ? "Off" : cfg.getLineWidth() + " px"));
    }

    private static Component hotbarText(InventoryThemeConfig cfg) {
        return Component.literal("Theme Hotbar: " + (cfg.isThemeHotbar() ? "§aON" : "§cOFF"));
    }

    private static Component hotbarScaleText(InventoryThemeConfig cfg) {
        return Component.literal(String.format("Hotbar Scale: %.0f%%", cfg.getHotbarScale() * 100));
    }

    private static Component slotModeText(InventoryThemeConfig cfg) {
        // Not "Slot Color:" - that is the picker's key below (the tooltip key is the label cut at the first ':').
        return Component.literal("Slot Color Source: " + (cfg.isUseCustomSlotColor() ? "Custom" : "Theme"));
    }

    private static Component accentModeText(InventoryThemeConfig cfg) {
        // Deliberately NOT prefixed "Accent Color:" - the swatch button below already uses that exact
        // key (see ColorSwatch.label("Accent Color", ...)); the tooltip key is the label cut at the
        // first ':', so two different widgets both starting "Accent Color:" would collide onto the
        // same tooltip entry.
        return Component.literal("Accent Source: " + (cfg.isUseCustomAccent() ? "Custom" : "Theme"));
    }
}
