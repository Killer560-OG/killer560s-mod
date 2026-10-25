package com.killer560.hub.gui.tab;

import com.killer560.hub.fullbright.FullbrightConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mainmenu.MainMenuThemeConfig;
import com.killer560.hub.window.ConfineMouseConfig;
import com.killer560.hub.window.WindowModeConfig;
import com.killer560.hub.window.WindowModeFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Window/rendering settings - split out of Home (2026-09-08) per killer560's request that Home only
 *  hold the HUD editor, Join Discord, and Check for Updates. */
public class DisplayOptionsTab extends BaseTab {


    public DisplayOptionsTab() {
        super("Display Options");
    }

    /** Several independent switches and no master one: the menu card shows no switch (Option A menu, 2026-10-12). */
    @Override
    public com.killer560.hub.gui.SettingsButtonWidget masterToggle(
            java.util.List<net.minecraft.client.gui.components.AbstractWidget> built) {
        return null;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(borderlessText(), btn -> {
                    WindowModeFeature.toggle();
                    btn.setMessage(borderlessText());
                }).bounds(contentX, y, 220, 20).build());
        y += 22;

        // Confine Mouse In Menus (2026-10-24): only acts in borderless fullscreen; its default follows that switch.
        widgets.add(SettingsButtonWidget.builder(confineMouseText(), btn -> {
                    ConfineMouseConfig cfg = ConfineMouseConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(confineMouseText());
                }).bounds(contentX, y, 220, 20).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(fullbrightText(), btn -> {
                    FullbrightConfig cfg = FullbrightConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(fullbrightText());
                }).bounds(contentX, y, 220, 20).build());
        y += 22;

        // Black+orange title screen (mainmenu package) - default ON; OFF restores the vanilla main menu.
        widgets.add(SettingsButtonWidget.builder(themedMainMenuText(), btn -> {
                    MainMenuThemeConfig cfg = MainMenuThemeConfig.getInstance();
                    boolean on = !cfg.isEnabled();
                    // One switch for the whole theme (killer560, 2026-09-15): the embers and the other-menu
                    // theming no longer have their own toggles, so they follow this one.
                    cfg.setEnabled(on);
                    cfg.setParticles(on);
                    cfg.setOtherMenus(on);
                    cfg.save();
                    btn.setMessage(themedMainMenuText());
                }).bounds(contentX, y, 220, 20).build());
        y += 22;

        // The title screen's Swap Accounts screens (and their proxy / session login pages) have their own Theme
        // (2026-10-13; Theme Default follows the menu).
        widgets.add(com.killer560.hub.gui.ScreenThemes.button(com.killer560.hub.accounts.gui.AccountSwitcherScreen.THEME_ID, "Swap Accounts Theme",
                contentX, y, 220, 20));
        y += 22;

        return widgets;
    }

    private static Component borderlessText() {
        return Component.literal("Borderless Fullscreen: "
                + (WindowModeConfig.getInstance().isBorderlessFullscreenEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component confineMouseText() {
        return Component.literal("Confine Mouse In Menus: "
                + (ConfineMouseConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component themedMainMenuText() {
        return Component.literal("Themed Main Menu: "
                + (MainMenuThemeConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }



    private static Component fullbrightText() {
        return Component.literal("Fullbright: "
                + (FullbrightConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

}
