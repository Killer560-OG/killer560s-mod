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

    /** The card is ON while any of its features is, OFF only when all are (killer560, 2026-10-14): the theme and Hide Potion Effects are separate features. */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Inventory Theme", "Hide Potion Effects");
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

        widgets.add(SettingsButtonWidget.cycling(scopeText(cfg), (btn, dir) -> {
                    cfg.setHypixelOnly(!cfg.isHypixelOnly());
                    cfg.save();
                    btn.setMessage(scopeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // killer560, 2026-10-12: the vanilla-shaped reskin in Default (vanilla's colours), Dark or Mod Theme; Custom draws it
        // in the Theme / Slot Color / Accent settings below.
        widgets.add(SettingsButtonWidget.cycling(presetText(cfg), (btn, dir) -> {
                    cfg.setPreset(SettingsButtonWidget.step(cfg.getPreset(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (cfg.getPreset() == InventoryThemeConfig.Preset.CUSTOM) {
            // Amber -> Dark -> Light, the same three as the Storage Overlay; only the Custom preset uses it.
            widgets.add(SettingsButtonWidget.cycling(themeText(cfg), (btn, dir) -> {
                        cfg.setTheme(SettingsButtonWidget.step(cfg.getTheme(), dir));
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 26;
        }


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

        widgets.add(SettingsButtonWidget.cycling(slotModeText(cfg), (btn, dir) -> {
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

        widgets.add(SettingsButtonWidget.cycling(accentModeText(cfg), (btn, dir) -> {
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

    private static Component themeText(InventoryThemeConfig cfg) {
        PanelTheme theme = cfg.getTheme();
        String colour = theme == PanelTheme.AMBER ? "§6" : theme == PanelTheme.DARK ? "§8" : "§f";
        return Component.literal("Theme: " + colour + theme.label);
    }

    private static Component presetText(InventoryThemeConfig cfg) {
        return Component.literal("Preset: " + cfg.getPreset().label);
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
