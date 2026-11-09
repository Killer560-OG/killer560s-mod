package com.killer560.hub.gui.tab;

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

/** Carry Tracker (see {@link CarryTracker}): the switch, the names being carried with their run counts, and the latest runs. */
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
            if (cfg.add(name.getValue())) {
                cfg.save();
                requestRebuild.run();
            }
        }).bounds(contentX + 136, y, 90, 20).build();
        widgets.add(add);
        name.pressOnEnter(add);
        y += 26;
        for (String n : cfg.getTracked()) {
            widgets.add(new StringWidget(contentX, y, contentWidth - 80, 20,
                    Component.literal("\u00a7f" + n + " \u00a77- \u00a7e" + CarryTracker.count(n) + " runs"), font));
            widgets.add(SettingsButtonWidget.builder(Component.literal("Remove"), btn -> {
                cfg.remove(n);
                cfg.save();
                requestRebuild.run();
            }).bounds(contentX + contentWidth - 74, y, 70, 20).build());
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
