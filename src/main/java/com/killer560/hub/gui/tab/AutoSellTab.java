package com.killer560.hub.gui.tab;

import com.killer560.hub.autosell.AutoSellConfig;
import com.killer560.hub.autosell.AutoSellFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Sell settings. The sell list / never-sell list / screen title pattern are managed through
 *  {@code /autosell} (see {@code AutoSellCommands}) rather than a GUI list editor, same reasoning as
 *  {@link InventorySorterTab}; this tab is the master switch, the safety-relevant counts and a status line. */
public class AutoSellTab extends BaseTab {

    public AutoSellTab() {
        super("Auto Sell");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AutoSellConfig cfg = AutoSellConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Sell", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        boolean running = AutoSellFeature.isRunning();
        widgets.add(SettingsButtonWidget.builder(
                        Component.literal(running ? "§cRunning... /autosell stop" : "§7Not running - /autosell start"),
                        btn -> {
                            if (AutoSellFeature.isRunning()) {
                                AutoSellFeature.requestStop();
                                requestRebuild.run();
                            }
                        }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(inertLabel(contentX, y, contentWidth,
                "Sell list: " + cfg.getSellIdentities().size() + "   Never-sell: " + cfg.getNeverSellIdentities().size()));
        y += 22;
        widgets.add(inertLabel(contentX, y, contentWidth, "Screen title pattern: " + cfg.getScreenTitlePattern()));
        y += 22;

        widgets.add(inertLabel(contentX, y, contentWidth, "/autosell add held|<item>"));
        y += 18;
        widgets.add(inertLabel(contentX, y, contentWidth, "/autosell neversell add held|<item>"));
        y += 18;
        widgets.add(inertLabel(contentX, y, contentWidth, "/autosell list   /autosell screentitle <regex>"));

        return widgets;
    }

    private static AbstractWidget inertLabel(int x, int y, int width, String text) {
        SettingsButtonWidget w = SettingsButtonWidget.builder(Component.literal(text), btn -> {
                }).bounds(x, y, width, 16).build();
        w.active = false;
        return w;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
