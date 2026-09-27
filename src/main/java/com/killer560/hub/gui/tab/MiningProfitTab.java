package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mining.profit.MiningProfitConfig;
import com.killer560.hub.mining.profit.MiningProfitTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mining Profit/Hour tracker (killer560, verbatim: "Profit per hour tracker") - see
 * {@link MiningProfitTracker}'s class doc for exactly how items-gained and their coin value are measured.
 * Observational only (never clicks/moves), so not cheat-gated. Defaults OFF like every new Mining (WIP)
 * feature.
 */
public class MiningProfitTab extends BaseTab {

    public MiningProfitTab() {
        super("Profit Per Hour");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        MiningProfitConfig cfg = MiningProfitConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Profit Per Hour Tracker", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                    Component.literal("§7Tracks items gained (main inventory + armor + off-hand) and their live"
                            + " Bazaar/AH value while active."), font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Mining Islands Only", cfg.isMiningIslandsOnly()), btn -> {
                    cfg.setMiningIslandsOnly(!cfg.isMiningIslandsOnly());
                    cfg.save();
                    btn.setMessage(onOff("Mining Islands Only", cfg.isMiningIslandsOnly()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> requestRebuild.run())
                .bounds(colBX, y, colW, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Session Totals", false), font));
        y += 16;

        double perHour = MiningProfitTracker.getCoinsPerHour();
        widgets.add(new StringWidget(contentX, y, colW, 10, Component.literal("§7Coins/Hour: §6"
                + com.killer560.hub.mining.profit.MiningItemPricer.shortNumber(perHour)), font));
        widgets.add(new StringWidget(colBX, y, colW, 10, Component.literal("§7Active Time: §f"
                + MiningProfitTracker.formatDuration(MiningProfitTracker.getActiveMs())), font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, colW, 10, Component.literal("§7Total Value: §6"
                + com.killer560.hub.mining.profit.MiningItemPricer.shortNumber(MiningProfitTracker.getTotalValueCoins())
                + " coins"), font));
        widgets.add(new StringWidget(colBX, y, colW, 10, Component.literal("§7Unpriced Gains: §f"
                + MiningProfitTracker.getUnpricedGains()), font));
        y += 18;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Top Items", false), font));
        y += 14;
        List<String> top = MiningProfitTracker.topItemLines(6);
        if (top.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§7Nothing gained yet."), font));
            y += 13;
        } else {
            for (String line : top) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§6- §f" + line), font));
                y += 13;
            }
        }
        y += 8;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Totals"), btn -> {
                    MiningProfitTracker.reset();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
