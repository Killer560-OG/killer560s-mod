package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modlist.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mods Screen (killer560, 2026-11-04): the built-in mod list. Its Mods buttons (Themed Main Menu, pause menu) always
 * exist, with no switch (2026-11-09). Open Mods Screen shows it from here.
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
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Mods Screen"), b -> {
            Minecraft mc = Minecraft.getInstance();
            McCompat.setScreen(mc, new ModsScreen(McCompat.screen(mc)));
        }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }
}
