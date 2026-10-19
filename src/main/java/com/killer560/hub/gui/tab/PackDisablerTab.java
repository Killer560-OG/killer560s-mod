package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.packdisabler.PackDisabler;
import com.killer560.hub.packdisabler.PackDisablerConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Pack Disabler (2026-10-07): SkyBlock items drawn with their pre-resource-pack look. Default OFF. Its own tab under
 *  Display (killer560, 2026-10-18: "make pack disabler its own setting not inside of display options"). */
public class PackDisablerTab extends BaseTab {

    public PackDisablerTab() {
        super("Pack Disabler");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        widgets.add(SettingsButtonWidget.builder(packDisablerText(), btn -> {
                    PackDisablerConfig cfg = PackDisablerConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(packDisablerText());
                }).bounds(contentX, contentY, 220, 20).build());
        return widgets;
    }

    private static Component packDisablerText() {
        boolean on = PackDisablerConfig.getInstance().isEnabled();
        String state = !on ? "§cOFF" : PackDisabler.noammInstalled() ? "§eON (idle: PackDisabler mod installed)" : "§aON";
        return Component.literal("Pack Disabler: " + state);
    }
}
