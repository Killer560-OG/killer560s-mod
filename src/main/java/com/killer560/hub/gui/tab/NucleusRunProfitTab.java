package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mining.nucleus.NucleusRunProfitConfig;
import com.killer560.hub.mining.nucleus.NucleusRunProfitTracker;
import com.killer560.hub.mining.profit.MiningItemPricer;
import com.killer560.hub.mining.profit.MiningProfitTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Nucleus Run Profit tracker (killer560, verbatim: "nuc run profit tracker") - see
 * {@link NucleusRunProfitTracker}'s class doc for exactly how a "run" is detected (Hypixel's own Crystal
 * Nucleus loot-bundle chat message, verified against the public SkyHanni mod's real detector) and how
 * per-item value is priced. Observational only, not cheat-gated. Defaults OFF.
 */
public class NucleusRunProfitTab extends BaseTab {

    public NucleusRunProfitTab() {
        super("Nucleus Run Profit");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        NucleusRunProfitConfig cfg = NucleusRunProfitConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Nucleus Run Profit Tracker", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                    Component.literal("§7Counts completed Crystal Nucleus runs (from Hypixel's own loot-bundle"
                            + " chat message) and their live coin value, only while you're in Crystal Hollows."), font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh"), btn -> requestRebuild.run())
                .bounds(contentX, y, colW, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Session Totals", false), font));
        y += 16;
        widgets.add(new StringWidget(contentX, y, colW, 10, Component.literal("§7Runs Completed: §f"
                + NucleusRunProfitTracker.getRunsCompleted()), font));
        widgets.add(new StringWidget(colBX, y, colW, 10, Component.literal("§7Active Time: §f"
                + MiningProfitTracker.formatDuration(NucleusRunProfitTracker.getActiveMs())), font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, colW, 10, Component.literal("§7Coins/Run: §6"
                + MiningItemPricer.shortNumber(NucleusRunProfitTracker.getCoinsPerRun())), font));
        widgets.add(new StringWidget(colBX, y, colW, 10, Component.literal("§7Coins/Hour: §6"
                + MiningItemPricer.shortNumber(NucleusRunProfitTracker.getCoinsPerHour())), font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§7Total Value: §6"
                + MiningItemPricer.shortNumber(NucleusRunProfitTracker.getTotalValueCoins()) + " coins"), font));
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                Component.literal("§7Per-item value is best-effort (Jungle Key / Precursor Apparatus / robot-part"
                        + " costs are NOT subtracted); the run count itself is exact."), font));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Totals"), btn -> {
                    NucleusRunProfitTracker.reset();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
