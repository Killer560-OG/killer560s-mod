package com.killer560.hub.gui.tab;

import com.killer560.hub.autoclosechest.AutoCloseChestConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Close Chest settings - see {@link com.killer560.hub.autoclosechest.AutoCloseChestFeature}'s class
 *  doc for the real QUOI-ported secret-chest detection this is built on. */
public class AutoCloseChestTab extends BaseTab {

    public AutoCloseChestTab() {
        super("Auto Close Chest");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AutoCloseChestConfig cfg = AutoCloseChestConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Close Chest", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Auto Close Chest", cfg.isEnabled()));
                }).bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
