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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Enchant Colours settings - see {@link EnchantColorsFeature} for what actually gets recoloured and where
 *  the behaviour was taken from SkyHanni. Ships OFF; the override table ships pre-filled
 *  ({@link EnchantColorsDefaults}) so turning it on is immediately useful, which is the whole point of
 *  killer560's "sensible defaults out of the box". */
public class EnchantColorsTab extends BaseTab {

    private static final int ROWS_PER_PAGE = 8;

    private int page = 0;
    private String filter = "";
    private String newName = "";

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
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Recolours enchantment names in item lore so the ones that"), mc.font));
            y += 12;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7matter stand out. Skyblock only."), mc.font));
            return widgets;
        }

        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        widgets.add(SettingsButtonWidget.builder(
                onOff("Only Configured Enchantments", cfg.isOnlyConfigured()), btn -> {
                    cfg.setOnlyConfigured(!cfg.isOnlyConfigured());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, cfg.isOnlyConfigured() ? contentWidth : half, 18).build());
        if (!cfg.isOnlyConfigured()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Other Enchants", cfg.getDefaultColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Unlisted Enchant Colour",
                                cfg.getDefaultColor(), EnchantColorsDefaults.UNLISTED, argb -> {
                            cfg.setDefaultColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2, y, half, 18).build());
        }
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
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Detected from the item's own ultimate_ enchant id, so it wins"), mc.font));
            y += 12;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7over the per-enchant colour below."), mc.font));
            y += 16;
        }

        // ---------------- Per-enchant overrides ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Per-Enchant Colours", false), mc.font));
        y += 16;

        EditBox newBox = new EditBox(mc.font, contentX, y, half, 18, Component.literal("Enchantment name"));
        newBox.setMaxLength(48);
        newBox.setHint(Component.literal("Enchantment name"));
        newBox.setValue(newName);
        newBox.setResponder(text -> newName = text);
        widgets.add(newBox);
        widgets.add(SettingsButtonWidget.builder(Component.literal("§aAdd / Edit"), btn -> {
                    String name = EnchantColorsFeature.normalize(newName);
                    if (name.isEmpty()) {
                        return;
                    }
                    Integer existing = cfg.getColor(name);
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Colour for \"" + name + "\"",
                            existing == null ? EnchantColorsDefaults.TOP : existing,
                            EnchantColorsDefaults.TOP, argb -> {
                        cfg.putColor(name, argb);
                        cfg.save();
                        newName = "";
                    }));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        EditBox filterBox = new EditBox(mc.font, contentX, y, contentWidth, 18, Component.literal("Filter Enchants"));
        filterBox.setMaxLength(48);
        filterBox.setHint(Component.literal("Filter enchants..."));
        filterBox.setValue(filter);
        filterBox.setResponder(text -> {
            if (!text.equals(filter)) {
                filter = text;
                page = 0;
                requestRebuild.run();
            }
        });
        widgets.add(filterBox);
        y += 22;

        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        String query = filter.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : cfg.getColors().entrySet()) {
            if (query.isEmpty() || e.getKey().contains(query)) {
                entries.add(e);
            }
        }
        int pages = Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = page * ROWS_PER_PAGE; i < Math.min(entries.size(), (page + 1) * ROWS_PER_PAGE); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            String name = entry.getKey();
            int argb = entry.getValue();
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label(title(name), argb), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Colour for \"" + name + "\"",
                                argb, EnchantColorsDefaults.TOP, picked -> {
                            cfg.putColor(name, picked);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, contentWidth - 26, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cX"), btn -> {
                        cfg.removeColor(name);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX + contentWidth - 22, y, 22, 18).build());
            y += 22;
        }

        if (entries.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No enchantment colours configured."), mc.font));
            y += 16;
        }

        int third = (contentWidth - 16) / 3;
        widgets.add(SettingsButtonWidget.builder(Component.literal("< Prev"), btn -> {
                    page = Math.max(0, page - 1);
                    requestRebuild.run();
                }).bounds(contentX, y, third, 18).build());
        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Page " + (page + 1) + "/" + pages), btn -> {
                }).bounds(contentX + third + 8, y, third, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Next >"), btn -> {
                    page = page + 1;
                    requestRebuild.run();
                }).bounds(contentX + 2 * (third + 8), y, third, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("§eReset Colours to Defaults"), btn -> {
                    cfg.resetColorsToDefaults();
                    cfg.save();
                    page = 0;
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    /** "bane of arthropods" -&gt; "Bane Of Arthropods", purely so the list is readable; the stored key stays
     *  normalised. */
    private static String title(String normalized) {
        StringBuilder out = new StringBuilder(normalized.length());
        boolean start = true;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-';
        }
        return out.toString();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
