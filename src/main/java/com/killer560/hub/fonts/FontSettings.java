package com.killer560.hub.fonts;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ExternalOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Themes tab's Font section (killer560, 2026-10-15): which font, where it applies, its size, and the import folder.
 * Built by {@code MenuThemeTab}; every row acts on {@link FontConfig#getInstance()} at press time, so a profile load
 * that replaced the config object is honoured.
 */
public final class FontSettings {

    private FontSettings() {
    }

    /** Adds the section at {@code y}; returns the y under it. */
    public static int build(List<AbstractWidget> widgets, int x, int y, int w, Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Font", false), font));
        y += 16;

        widgets.add(SettingsButtonWidget.cycling(fontText(), (btn, dir) -> {
                    stepFont(dir);
                    btn.setMessage(fontText());
                    requestRebuild.run(); // the status line under the section names the new font
                }).bounds(x, y, w, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.cycling(scopeText(), (btn, dir) -> {
                    FontConfig cfg = FontConfig.getInstance();
                    cfg.setScope(SettingsButtonWidget.step(cfg.getScope(), dir));
                    cfg.save();
                    btn.setMessage(scopeText());
                }).bounds(x, y, w, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.cycling(sizeText(), (btn, dir) -> {
                    stepSize(dir);
                    btn.setMessage(sizeText());
                }).bounds(x, y, w, 20).build());
        y += 24;

        int half = (w - 4) / 2;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Fonts Folder"), btn -> {
                    try {
                        ExternalOpen.path(FontChoices.importFolder());
                    } catch (Exception ignored) {
                    }
                }).bounds(x, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reload Fonts"), btn -> {
                    CustomFonts.reloadNow(Minecraft.getInstance());
                    requestRebuild.run();
                }).bounds(x + w - half, y, half, 18).build());
        y += 22;

        widgets.add(new StringWidget(x, y, w, 12, Component.literal(status()), font));
        y += 16;
        return y;
    }

    static Component fontText() {
        return Component.literal("Font: " + FontChoices.label(FontConfig.getInstance().getFont()));
    }

    static Component scopeText() {
        return Component.literal("Font Applies To: " + FontConfig.getInstance().getScope().label);
    }

    static Component sizeText() {
        return Component.literal("Font Size: " + FontConfig.getInstance().getSize() + "%");
    }

    /** The next (+1) or previous (-1) font, wrapping, saved. Re-reads the import folder first, so a font dropped in is
     *  there to pick without pressing Reload. */
    public static void stepFont(int dir) {
        FontChoices.rescan();
        List<FontChoices.Choice> all = FontChoices.all();
        FontConfig cfg = FontConfig.getInstance();
        int i = 0;
        for (int k = 0; k < all.size(); k++) {
            if (all.get(k).id().equals(cfg.getFont())) {
                i = k;
                break;
            }
        }
        cfg.setFont(all.get(SettingsButtonWidget.step(i, dir, all.size())).id());
        cfg.save();
    }

    public static void stepSize(int dir) {
        FontConfig cfg = FontConfig.getInstance();
        int s = cfg.getSize() + dir * FontConfig.SIZE_STEP;
        if (s > FontConfig.MAX_SIZE) {
            s = FontConfig.MIN_SIZE;
        } else if (s < FontConfig.MIN_SIZE) {
            s = FontConfig.MAX_SIZE;
        }
        cfg.setSize(s);
        cfg.save();
    }

    private static String status() {
        String err = CustomFonts.lastError();
        if (err != null) {
            return "§c" + err;
        }
        int n = FontChoices.imported().size();
        return "§7" + (n == 0 ? "No imported fonts - put .ttf or .otf files in the fonts folder."
                : n + " imported font" + (n == 1 ? "" : "s") + " in the fonts folder.");
    }
}
