package com.killer560.hub.gui.tab;

import com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Bazaar Advisor settings - legit, read-only: prints what to buy and how much each time a Bazaar menu opens. */
public class BazaarAdvisorTab extends BaseTab {

    public BazaarAdvisorTab() {
        super("Bazaar Advisor");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Bazaar Advisor", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Bazaar Pick", cfg.isBazaarMode()), btn -> {
                    cfg.setBazaarMode(!cfg.isBazaarMode());
                    cfg.save();
                    btn.setMessage(onOff("Bazaar Pick", cfg.isBazaarMode()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("NPC Pick", cfg.isNpcMode()), btn -> {
                    cfg.setNpcMode(!cfg.isNpcMode());
                    cfg.save();
                    btn.setMessage(onOff("NPC Pick", cfg.isNpcMode()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
