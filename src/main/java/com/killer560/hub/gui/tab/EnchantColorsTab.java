package com.killer560.hub.gui.tab;

import com.killer560.hub.enchantcolors.EnchantColorsConfig;
import com.killer560.hub.enchantcolors.EnchantColorsDefaults;
import com.killer560.hub.enchantcolors.EnchantColorsFeature;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Enchant Colours settings - see {@link EnchantColorsFeature} for what actually gets recoloured and where
 *  the behaviour was taken from SkyHanni. Ships OFF.
 *  <p>
 *  2026-09-20 rewrite: killer560 wanted tier-based colours ("should follow skyhanni where the color is based
 *  off of tier not enchant"), so the old per-enchant override list (add/edit/remove, a filterable page of
 *  colour swatches) is gone - replaced with one picker per SkyHanni tier, which is also just five widgets
 *  instead of a whole paginated editor. */
public class EnchantColorsTab extends BaseTab {

    public EnchantColorsTab() {
        super("Enchant Colours");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        EnchantColorsConfig cfg = EnchantColorsConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Enchant Colours", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        // The mixin config is required:false so a signature change can never stop the game booting - but
        // that also means it could quietly do nothing, which is exactly how killer560 found this feature
        // completely dead in-game (2026-09-20). Say so rather than let him wonder why lore never changed.
        if (!EnchantColorsFeature.renderHookSeen()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                    "§8Render hook hasn't fired yet - hover an item. If this stays, the mixin didn't apply."), mc.font));
            y += 14;
        }

        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        widgets.add(SettingsButtonWidget.builder(
                onOff("Only Known Enchantments", cfg.isOnlyKnownEnchants()), btn -> {
                    cfg.setOnlyKnownEnchants(!cfg.isOnlyKnownEnchants());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, cfg.isOnlyKnownEnchants() ? contentWidth : half, 18).build());
        if (!cfg.isOnlyKnownEnchants()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Unknown Enchants", cfg.getUnknownColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Unknown Enchant Colour",
                                cfg.getUnknownColor(), EnchantColorsDefaults.UNKNOWN, argb -> {
                            cfg.setUnknownColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2, y, half, 18).build());
        }
        y += 26;

        // ---------------- Tier colours ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Tier Colours", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Poor", cfg.getPoorColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Poor Enchant Colour",
                            cfg.getPoorColor(), EnchantColorsDefaults.POOR, argb -> {
                        cfg.setPoorColor(argb);
                        cfg.save();
                    }));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Good", cfg.getGoodColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Good Enchant Colour",
                            cfg.getGoodColor(), EnchantColorsDefaults.GOOD, argb -> {
                        cfg.setGoodColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Great", cfg.getGreatColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Great Enchant Colour",
                            cfg.getGreatColor(), EnchantColorsDefaults.GREAT, argb -> {
                        cfg.setGreatColor(argb);
                        cfg.save();
                    }));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Perfect", cfg.getPerfectColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Perfect Enchant Colour",
                            cfg.getPerfectColor(), EnchantColorsDefaults.PERFECT, argb -> {
                        cfg.setPerfectColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Bold Perfect", cfg.isPerfectBold()), btn -> {
                    cfg.setPerfectBold(!cfg.isPerfectBold());
                    cfg.save();
                    btn.setMessage(onOff("Bold Perfect", cfg.isPerfectBold()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        // ---------------- Ultimate enchants ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Ultimate Enchants", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Ultimate Override", cfg.isUltimateEnabled()), btn -> {
                    cfg.setUltimateEnabled(!cfg.isUltimateEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isUltimateEnabled()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Ultimate Colour", cfg.getUltimateColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Ultimate Enchant Colour",
                                cfg.getUltimateColor(), EnchantColorsDefaults.ULTIMATE, argb -> {
                            cfg.setUltimateColor(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Bold", cfg.isUltimateBold()), btn -> {
                        cfg.setUltimateBold(!cfg.isUltimateBold());
                        cfg.save();
                        btn.setMessage(onOff("Bold", cfg.isUltimateBold()));
                    }).bounds(col2, y, half, 18).build());
            y += 22;
        }

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
