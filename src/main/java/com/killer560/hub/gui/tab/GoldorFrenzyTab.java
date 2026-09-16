package com.killer560.hub.gui.tab;

import com.killer560.hub.goldorfrenzy.GoldorFrenzyConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Goldor Frenzy Timer settings - see {@link com.killer560.hub.goldorfrenzy.GoldorFrenzyFeature}'s class doc
 *  for the real Devonian-ported chat triggers and tick counts, and for how this overlaps with Tick Timers'
 *  own Goldor line. */
public class GoldorFrenzyTab extends BaseTab {

    public GoldorFrenzyTab() {
        super("Goldor Frenzy");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        GoldorFrenzyConfig cfg = GoldorFrenzyConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;
        int col1 = contentX;
        int col2 = contentX + 116;

        widgets.add(SettingsButtonWidget.builder(onOff("Goldor Frenzy Timer", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 232, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Countdown to Goldor's 3s P3 damage tick."), mc.font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Pre-Goldor", cfg.isShowPreGoldor()), btn -> {
                    cfg.setShowPreGoldor(!cfg.isShowPreGoldor());
                    cfg.save();
                    btn.setMessage(onOff("Pre-Goldor", cfg.isShowPreGoldor()));
                }).bounds(col1, y, 108, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Show Total", cfg.isShowTotal()), btn -> {
                    cfg.setShowTotal(!cfg.isShowTotal());
                    cfg.save();
                    btn.setMessage(onOff("Show Total", cfg.isShowTotal()));
                }).bounds(col2, y, 116, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Ticks (vs Seconds)", cfg.isDisplayInTicks()), btn -> {
                    cfg.setDisplayInTicks(!cfg.isDisplayInTicks());
                    cfg.save();
                    btn.setMessage(onOff("Ticks (vs Seconds)", cfg.isDisplayInTicks()));
                }).bounds(col1, y, 108, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Show Prefix", cfg.isShowPrefix()), btn -> {
                    cfg.setShowPrefix(!cfg.isShowPrefix());
                    cfg.save();
                    btn.setMessage(onOff("Show Prefix", cfg.isShowPrefix()));
                }).bounds(col2, y, 116, 18).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Counts server ticks from Goldor's own arrival line."), mc.font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Show Total: time since P3 started instead of the countdown."), mc.font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§eTick Timers' \"Goldor\" line is the same 3s countdown - enabling"), mc.font));
        y += 11;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§eboth shows the same number twice."), mc.font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
