package com.killer560.hub.gui.tab;

import com.killer560.hub.carrytracker.CarryCommands;
import com.killer560.hub.carrytracker.CarryTracker;
import com.killer560.hub.carrytracker.CarryTrackerConfig;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.runsummary.RunRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Carry Tracker (see {@link CarryTracker}): the switch, adding clients (box, party, /carry), their runs targets and counts, and the latest runs. */
public class CarryTrackerTab extends BaseTab {

    public CarryTrackerTab() {
        super("Carry Tracker");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CarryTrackerConfig cfg = CarryTrackerConfig.getInstance();
        if (masterClosed(widgets, "Carry Tracker", cfg::getEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth)) {
            return widgets;
        }
        var font = Minecraft.getInstance().font;
        if (!isSearchScan()) {
            CarryTracker.warm(requestRebuild);
        }
        int y = contentY + 26;
        CommitEditBox name = new CommitEditBox(font, contentX, y, 130, 20, Component.literal("Player name"));
        name.setMaxLength(16);
        widgets.add(name);
        SettingsButtonWidget add = SettingsButtonWidget.builder(Component.literal("Add Player"), btn -> {
            if (CarryCommands.addClient(name.getValue(), 0)) {
                requestRebuild.run();
            }
        }).bounds(contentX + 136, y, 90, 20).build();
        widgets.add(add);
        name.pressOnEnter(add);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Add Party"), btn -> {
            if (CarryCommands.addParty() > 0) {
                requestRebuild.run();
            }
        }).bounds(contentX + 232, y, 90, 20).build());
        y += 26;
        widgets.add(normSlider(contentX, y, contentWidth, 20, () -> "Default Runs: " + cfg.getDefaultRuns(),
                (cfg.getDefaultRuns() - CarryTrackerConfig.MIN_RUNS) / (double) (CarryTrackerConfig.MAX_RUNS - CarryTrackerConfig.MIN_RUNS),
                v -> cfg.setDefaultRuns((int) Math.round(CarryTrackerConfig.MIN_RUNS + v * (CarryTrackerConfig.MAX_RUNS - CarryTrackerConfig.MIN_RUNS))),
                cfg::save));
        y += 24;
        widgets.add(onOffButton("Remove When Done", cfg::isRemoveWhenDone, cfg::setRemoveWhenDone, cfg::save, null, contentX, y, contentWidth, 20));
        y += 26;
        for (CarryTrackerConfig.Client c : cfg.getTracked()) {
            String n = c.name();
            long done = CarryTracker.count(n);
            widgets.add(new StringWidget(contentX, y, contentWidth - 100, 20, Component.literal("§f" + n + " §7- "
                    + (done >= c.runs() ? "§a" : "§e") + done + "/" + c.runs() + " runs"), font));
            widgets.add(SettingsButtonWidget.builder(Component.literal("-"), btn -> {
                cfg.setRuns(n, cfg.getRuns(n) - 1);
                cfg.save();
                requestRebuild.run();
            }).bounds(contentX + contentWidth - 96, y, 22, 20).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("+"), btn -> {
                cfg.setRuns(n, cfg.getRuns(n) + 1);
                cfg.save();
                requestRebuild.run();
            }).bounds(contentX + contentWidth - 72, y, 22, 20).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("X"), btn -> {
                cfg.remove(n);
                cfg.save();
                CarryTracker.reset(n);
                requestRebuild.run();
            }).bounds(contentX + contentWidth - 46, y, 22, 20).build());
            y += 22;
        }
        for (CarryTracker.Entry e : CarryTracker.recent(5)) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 14, Component.literal("\u00a77" + e.player() + " \u00a78- \u00a77" + e.floor()
                    + " in " + RunRecord.formatTime(e.totalMs()) + (e.score() >= 0 ? ", score " + e.score() : "")), font));
            y += 14;
        }
        return widgets;
    }
}
