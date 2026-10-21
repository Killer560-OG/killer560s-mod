package com.killer560.hub.menuthemes;

import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.mainmenu.MainMenuLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Menu Theme tab's Theme Extras section (killer560, 2026-10-21): what each theme brings besides its colours. "Extras
 * For" picks the theme being set up (it starts on the menu's own theme); every row below it acts on that theme. Built by
 * {@code MenuThemeTab}; every row reads {@link ThemeExtrasConfig#getInstance()} at press time, so a profile load that
 * replaced the config object is honoured.
 */
public final class ThemeExtrasSettings {

    /** The theme the rows edit, or null for the menu's own theme. Per session, not saved. */
    private static MenuTheme editing;

    private ThemeExtrasSettings() {
    }

    /** The theme the section is editing now. */
    public static MenuTheme editing() {
        return editing != null ? editing : MenuConfig.getInstance().getTheme();
    }

    public static void setEditing(MenuTheme theme) {
        editing = theme;
    }

    /** Adds the section at {@code y}; returns the y under it. */
    public static int build(List<AbstractWidget> widgets, int x, int y, int w, Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Extras", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(editingText(), (btn, dir) -> {
                    editing = SettingsButtonWidget.step(editing(), dir);
                    btn.setMessage(editingText());
                    requestRebuild.run(); // every row below names the new theme's choices
                }).bounds(x, y, w, 20).build());
        y += 24;

        // ---- Theme Music ----
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Music", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(musicOnText(), btn -> {
                    ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
                    c.setMusicOn(!c.isMusicOn());
                    c.save();
                    btn.setMessage(musicOnText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.cycling(musicText(), (btn, dir) -> {
                    stepMusic(dir);
                    btn.setMessage(musicText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        int stops = (ThemeExtrasConfig.MAX_VOLUME - ThemeExtrasConfig.MIN_VOLUME) / ThemeExtrasConfig.VOLUME_STEP;
        widgets.add(new ThemedSliderButton(x, y, w, 20, volumeText(),
                ThemeExtrasConfig.getInstance().getMusicVolume() / (double) ThemeExtrasConfig.MAX_VOLUME) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
                c.setMusicVolume((int) Math.round(this.value * ThemeExtrasConfig.MAX_VOLUME));
                c.save();
            }
        }.steps(stops));
        y += 24;
        widgets.add(new StringWidget(x, y, w, 12, Component.literal("§7Plays on the main menu, which shows "
                + MainMenuLook.current().theme().label + " now."), font));
        y += 16;

        // ---- Theme Font ----
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Font", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(fontText(), (btn, dir) -> {
                    ThemeFonts.step(editing(), dir);
                    btn.setMessage(fontText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(new StringWidget(x, y, w, 12, Component.literal("§7Used while the mod menu's theme is "
                + editing().label + "; Font Applies To and Font Size stay as set above."), font));
        y += 16;
        return y;
    }

    static Component fontText() {
        return Component.literal("Theme Font: " + ThemeFonts.label(ThemeExtrasConfig.getInstance().getFont(editing())));
    }

    /** The theme's next (+1) or previous (-1) music choice, saved. */
    public static void stepMusic(int dir) {
        ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
        MenuTheme t = editing();
        c.setMusic(t, ThemeMusic.step(c.getMusic(t), dir));
        c.save();
    }

    static Component editingText() {
        return Component.literal("Extras For: §6" + editing().label);
    }

    static Component musicOnText() {
        return Component.literal("Theme Music: " + (ThemeExtrasConfig.getInstance().isMusicOn() ? "§aON" : "§cOFF"));
    }

    static Component musicText() {
        MenuTheme t = editing();
        return Component.literal("Music: " + ThemeMusic.label(t, ThemeExtrasConfig.getInstance().getMusic(t)));
    }

    static Component volumeText() {
        return Component.literal("Music Volume: " + ThemeExtrasConfig.getInstance().getMusicVolume() + "%");
    }
}
