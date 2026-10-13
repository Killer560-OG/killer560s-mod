package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Menu Theme section (killer560, 2026-10-12): the colour preset the mod menu draws in - Amber (the default),
 * Dark, Light or Custom - and Custom's five colours, each opened in the usual colour picker. Changing a colour while a
 * preset is in use starts Custom from that preset; Reset puts Custom back to the preset it started from. Saved in
 * {@code killer560smod-menu.json} with Favourites ({@link MenuConfig}).
 */
public class MenuThemeTab extends BaseTab {

    public MenuThemeTab() {
        super("Menu Theme");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MenuConfig cfg = MenuConfig.getInstance();
        int y = contentY;
        widgets.add(SettingsButtonWidget.cycling(themeText(), (btn, dir) -> {
                    step(dir);
                    btn.setMessage(themeText());
                    requestRebuild.run(); // the colour rows show the new theme's colours
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Custom Colours", false),
                Minecraft.getInstance().font));
        y += 16;
        int[] colours = cfg.activeColours();
        for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
            int argb = colours[slot.ordinal()];
            int preset = cfg.getTheme() == MenuTheme.CUSTOM ? cfg.getCustomBase().base()[slot.ordinal()] : argb;
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label(slot.label, argb), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), slot.label, argb, preset,
                                picked -> setColour(slot, picked)));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Custom to "
                        + cfg.getCustomBase().label), btn -> {
                    resetCustom();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static Component themeText() {
        return Component.literal("Menu Theme: §6" + MenuConfig.getInstance().getTheme().label);
    }

    /** The next (dir +1) or previous (-1) theme, Amber - Dark - Light - Custom, wrapping; saved. */
    public static void step(int dir) {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setTheme(SettingsButtonWidget.step(cfg.getTheme(), dir));
        cfg.save();
    }

    /** Sets one Custom colour (the colour picker's answer) and saves; the menu switches to Custom. */
    public static void setColour(MenuTheme.Slot slot, int argb) {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setCustomColour(slot, argb);
        cfg.save();
    }

    /** Custom's colours back to the preset it started from, saved. */
    public static void resetCustom() {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.resetCustom();
        cfg.save();
    }

    @Override
    public String cardDescription() {
        return "The menu's colours: Amber, Dark, Light, or your own Custom set.";
    }
}
