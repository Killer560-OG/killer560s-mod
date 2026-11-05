package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modlist.ModListConfig;
import com.killer560.hub.modlist.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mods Screen (killer560, 2026-11-04): the built-in mod list, and its two buttons as independent switches (the card stands
 * for both, {@link #masterToggles}): Mods on the Themed Main Menu and Mods on the pause menu, both ON by default. Open Mods
 * Screen shows it from here.
 */
public class ModsScreenTab extends BaseTab {

    public ModsScreenTab() {
        super("Mods Screen");
    }

    @Override
    public String cardDescription() {
        return "Every mod you have loaded.";
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Main Menu Mods Button", "Pause Menu Mods Button");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ModListConfig cfg = ModListConfig.getInstance();
        int y = contentY;
        widgets.add(onOffButton("Main Menu Mods Button", cfg::isMainMenuButton, cfg::setMainMenuButton, cfg::save,
                null, contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(onOffButton("Pause Menu Mods Button", cfg::isPauseButton, cfg::setPauseButton, cfg::save,
                null, contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Mods Screen"), b -> {
            Minecraft mc = Minecraft.getInstance();
            McCompat.setScreen(mc, new ModsScreen(McCompat.screen(mc)));
        }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }
}
