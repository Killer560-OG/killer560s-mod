package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Themes tab's Main Menu section (killer560, 2026-10-15: "have toggle options for it to match, customize it, or have
 * a different main menu than mod menu"). Style picks Match Menu Theme / Separate Theme / Custom; the theme row shows for
 * Separate and Custom, the look rows for Custom only. The master switch stays Display Options' Themed Main Menu.
 */
public final class MainMenuSettings {

    private MainMenuSettings() {
    }

    /** Adds the section at {@code y}; returns the y under it. */
    public static int build(List<AbstractWidget> widgets, int x, int y, int w, Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        MainMenuThemeConfig cfg = MainMenuThemeConfig.getInstance();
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Main Menu", false), font));
        y += 16;
        if (!cfg.isEnabled()) {
            widgets.add(new StringWidget(x, y, w, 12,
                    Component.literal("§7Themed Main Menu is off (Display Options): the main menu is Minecraft's."), font));
            y += 16;
        }
        widgets.add(SettingsButtonWidget.cycling(styleText(), (btn, dir) -> {
                    MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
                    c.setStyle(SettingsButtonWidget.step(c.getStyle(), dir));
                    c.save();
                    btn.setMessage(styleText());
                    requestRebuild.run(); // the theme and look rows come and go with the style
                }).bounds(x, y, w, 20).build());
        y += 24;
        if (cfg.getStyle() == MainMenuThemeConfig.Style.MATCH) {
            return y;
        }
        widgets.add(SettingsButtonWidget.cycling(themeText(), (btn, dir) -> {
                    MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
                    c.setTheme(SettingsButtonWidget.step(c.getTheme(), dir));
                    c.save();
                    btn.setMessage(themeText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        if (cfg.getStyle() != MainMenuThemeConfig.Style.CUSTOM) {
            return y;
        }
        widgets.add(SettingsButtonWidget.cycling(sceneText(), (btn, dir) -> {
                    MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
                    c.setScene(SettingsButtonWidget.step(c.getScene(), dir));
                    c.save();
                    btn.setMessage(sceneText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.cycling(particlesText(), (btn, dir) -> {
                    stepParticles(dir);
                    btn.setMessage(particlesText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.cycling(speedText(), (btn, dir) -> {
                    MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
                    int[] speeds = MainMenuThemeConfig.SPEEDS;
                    int i = 0;
                    for (int k = 0; k < speeds.length; k++) {
                        if (speeds[k] == c.getSpeed()) {
                            i = k;
                        }
                    }
                    c.setSpeed(speeds[SettingsButtonWidget.step(i, dir, speeds.length)]);
                    c.save();
                    btn.setMessage(speedText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(splashText(), btn -> {
                    MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
                    c.setSplash(!c.isSplash());
                    c.save();
                    btn.setMessage(splashText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(panelText(), btn -> {
                    MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
                    c.setPanel(!c.isPanel());
                    c.save();
                    btn.setMessage(panelText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        return y;
    }

    /** Off, Low, Normal, High, wrapping. Off is the old "particles" switch, so the master toggle still controls it. */
    public static void stepParticles(int dir) {
        MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
        int n = MainMenuThemeConfig.Density.values().length + 1;
        int i = c.isParticles() ? c.getDensity().ordinal() + 1 : 0;
        int next = SettingsButtonWidget.step(i, dir, n);
        if (next == 0) {
            c.setParticles(false);
        } else {
            c.setParticles(true);
            c.setDensity(MainMenuThemeConfig.Density.values()[next - 1]);
        }
        c.save();
    }

    static Component styleText() {
        return Component.literal("Main Menu Style: " + MainMenuThemeConfig.getInstance().getStyle().label);
    }

    static Component themeText() {
        MenuTheme t = MainMenuThemeConfig.getInstance().getTheme();
        return Component.literal("Main Menu Theme: " + t.label);
    }

    static Component sceneText() {
        MainMenuThemeConfig.Scene s = MainMenuThemeConfig.getInstance().getScene();
        String shown = s == MainMenuThemeConfig.Scene.AUTO
                ? "Theme Default (" + MainMenuLook.sceneFor(MainMenuThemeConfig.getInstance().getTheme()).label + ")"
                : s.label;
        return Component.literal("Background: " + shown);
    }

    static Component particlesText() {
        MainMenuThemeConfig c = MainMenuThemeConfig.getInstance();
        return Component.literal("Particles: " + (c.isParticles() ? c.getDensity().label : "Off"));
    }

    static Component speedText() {
        return Component.literal("Animation Speed: " + MainMenuThemeConfig.getInstance().getSpeed() + "%");
    }

    static Component splashText() {
        return Component.literal("Splash Text: " + (MainMenuThemeConfig.getInstance().isSplash() ? "§aON" : "§cOFF"));
    }

    static Component panelText() {
        return Component.literal("Button Panel: " + (MainMenuThemeConfig.getInstance().isPanel() ? "§aON" : "§cOFF"));
    }
}
