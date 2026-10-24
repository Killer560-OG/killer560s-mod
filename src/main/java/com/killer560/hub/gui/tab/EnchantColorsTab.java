package com.killer560.hub.gui.tab;

import com.killer560.hub.enchantcolors.EnchantColorsConfig;
import com.killer560.hub.enchantcolors.EnchantColorsDefaults;
import com.killer560.hub.enchantcolors.EnchantColorsFeature;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/** Enchant Colors settings - see {@link EnchantColorsFeature} for what actually gets recolored and where
 *  the behavior was taken from SkyHanni. Ships OFF.
 *  <p>
 *  2026-09-20 rewrite: killer560 wanted tier-based colors ("should follow skyhanni where the color is based
 *  off of tier not enchant"), so the old per-enchant override list (add/edit/remove, a filterable page of
 *  color swatches) is gone - replaced with one picker per SkyHanni tier, which is also just five widgets
 *  instead of a whole paginated editor.
 *  <p>
 *  2026-09-27 correction: the tier default colors were actually wrong (see {@code EnchantColorsDefaults}'
 *  class doc) - fixed, and a "Perfect Chroma" toggle added since SkyHanni's REAL Perfect default is an
 *  animated rainbow, not a fixed swatch. The static "Perfect" picker only shows while that toggle is off. */
public class EnchantColorsTab extends BaseTab {

    public EnchantColorsTab() {
        super("Enchant Colors");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        EnchantColorsConfig cfg = EnchantColorsConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Enchant Colors", cfg.isEnabledRaw()), btn -> {
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
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Unknown Enchant Color",
                                cfg.getUnknownColor(), EnchantColorsDefaults.UNKNOWN, argb -> {
                            cfg.setUnknownColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2, y, half, 18).build());
        }
        y += 26;

        // ---------------- Tier colors ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Tier Colors", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Poor", cfg.getPoorColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Poor Enchant Color",
                            cfg.getPoorColor(), EnchantColorsDefaults.POOR, argb -> {
                        cfg.setPoorColor(argb);
                        cfg.save();
                    }));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Good", cfg.getGoodColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Good Enchant Color",
                            cfg.getGoodColor(), EnchantColorsDefaults.GOOD, argb -> {
                        cfg.setGoodColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Great", cfg.getGreatColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Great Enchant Color",
                            cfg.getGreatColor(), EnchantColorsDefaults.GREAT, argb -> {
                        cfg.setGreatColor(argb);
                        cfg.save();
                    }));
                }).bounds(contentX, y, half, 18).build());
        if (!cfg.isPerfectChroma()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Perfect", cfg.getPerfectColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Perfect Enchant Color",
                                cfg.getPerfectColor(), EnchantColorsDefaults.PERFECT, argb -> {
                            cfg.setPerfectColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2, y, half, 18).build());
        }
        y += 22;

        // killer560, 2026-09-27: "make the enchant colours the exact same as skyhanni's" - SkyHanni's real
        // Perfect default is an animated rainbow, not the fixed swatch above (see EnchantColorsDefaults'
        // class doc), so that's the default here too. The static "Perfect" swatch only reappears once this
        // is turned off, same pattern as "Only Known Enchantments" / "Unknown Enchants" above.
        widgets.add(SettingsButtonWidget.builder(onOff("Perfect Chroma", cfg.isPerfectChroma()), btn -> {
                    cfg.setPerfectChroma(!cfg.isPerfectChroma());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // killer560, 2026-10-13: the chroma waterfalls diagonally across the whole tooltip (util/ChromaWave);
        // these set how fast the bands sweep and how wide they are. Only while Perfect Chroma is on.
        if (cfg.isPerfectChroma()) {
            widgets.add(slider(contentX, y, half, "Chroma Speed", EnchantColorsConfig.MIN_CHROMA_SPEED,
                    EnchantColorsConfig.MAX_CHROMA_SPEED, 0.1f, cfg.getChromaSpeed(), v -> {
                        cfg.setChromaSpeed(v);
                        ThemedSliderButton.saveOnRelease(() -> cfg.save());
                    }));
            widgets.add(slider(col2, y, half, "Chroma Size", EnchantColorsConfig.MIN_CHROMA_SIZE,
                    EnchantColorsConfig.MAX_CHROMA_SIZE, 0.05f, cfg.getChromaSize(), v -> {
                        cfg.setChromaSize(v);
                        ThemedSliderButton.saveOnRelease(() -> cfg.save());
                    }));
            y += 22;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Bold Perfect", cfg.isPerfectBold()), btn -> {
                    cfg.setPerfectBold(!cfg.isPerfectBold());
                    cfg.save();
                    btn.setMessage(onOff("Bold Perfect", cfg.isPerfectBold()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // killer560, 2026-09-27: "something like gk5 being a t7" - a Giant Killer VII (real max, see
        // EnchantColorsDefaults) is Perfect only because this is on; off falls back to never calling
        // anything above Good "Perfect" at all, the old "clamped to the table's top color" behavior, kept
        // as an escape hatch rather than removed.
        widgets.add(SettingsButtonWidget.builder(onOff("True Max Detection", cfg.isTrueMaxDetection()), btn -> {
                    cfg.setTrueMaxDetection(!cfg.isTrueMaxDetection());
                    cfg.save();
                    btn.setMessage(onOff("True Max Detection", cfg.isTrueMaxDetection()));
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
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Ultimate Color", cfg.getUltimateColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Ultimate Enchant Color",
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

    /** A themed slider over [min, max] snapped to {@code step}, labeled "Label: 1.0x". */
    private static AbstractWidget slider(int x, int y, int width, String label, float min, float max, float step,
                                         float current, java.util.function.Consumer<Float> set) {
        double start = (Math.max(min, Math.min(max, current)) - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, sliderLabel(label, current), start) {
            private float shown = current;

            @Override
            protected void updateMessage() {
                setMessage(sliderLabel(label, shown));
            }

            @Override
            protected void applyValue() {
                float raw = (float) (min + this.value * (max - min));
                shown = Math.max(min, Math.min(max, Math.round(raw / step) * step));
                set.accept(shown);
            }
        };
    }

    private static Component sliderLabel(String label, float v) {
        return Component.literal(label + ": " + String.format(java.util.Locale.ROOT, "%.2f", v) + "x");
    }
}
