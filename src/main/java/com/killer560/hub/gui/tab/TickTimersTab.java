package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.ticktimers.TickTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Tick Timers settings - see {@link com.killer560.hub.ticktimers.TickTimersFeature}'s class doc for
 *  the real Odin-ported chat triggers/tick counts this is built on. */
public class TickTimersTab extends BaseTab {

    public TickTimersTab() {
        super("Tick Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff("Tick Timers", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Necron", cfg.isNecronTimer()), btn -> {
                    cfg.setNecronTimer(!cfg.isNecronTimer());
                    cfg.save();
                    btn.setMessage(onOff("Necron", cfg.isNecronTimer()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Goldor", cfg.isGoldorTimer()), btn -> {
                    cfg.setGoldorTimer(!cfg.isGoldorTimer());
                    cfg.save();
                    btn.setMessage(onOff("Goldor", cfg.isGoldorTimer()));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Storm", cfg.isStormTimer()), btn -> {
                    cfg.setStormTimer(!cfg.isStormTimer());
                    cfg.save();
                    btn.setMessage(onOff("Storm", cfg.isStormTimer()));
                }).bounds(col3, y, 108, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Ticks (vs Seconds)", cfg.isDisplayInTicks()), btn -> {
                    cfg.setDisplayInTicks(!cfg.isDisplayInTicks());
                    cfg.save();
                    btn.setMessage(onOff("Ticks (vs Seconds)", cfg.isDisplayInTicks()));
                }).bounds(col1, y, 160, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Symbol", cfg.isShowSymbol()), btn -> {
                    cfg.setShowSymbol(!cfg.isShowSymbol());
                    cfg.save();
                    btn.setMessage(onOff("Symbol", cfg.isShowSymbol()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Prefix", cfg.isShowPrefix()), btn -> {
                    cfg.setShowPrefix(!cfg.isShowPrefix());
                    cfg.save();
                    btn.setMessage(onOff("Show Prefix", cfg.isShowPrefix()));
                }).bounds(col1, y, 160, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Necron/Goldor/Storm countdowns from real boss dialogue lines."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
