package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.splittimers.SplitTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Split Timers settings - see {@link com.killer560.hub.splittimers.SplitTimersFeature}'s class doc
 *  for the real Odin-ported boss-dialogue split points this is built on. */
public class SplitTimersTab extends BaseTab {

    public SplitTimersTab() {
        super("Split Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Split Timers", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Announce In Chat", cfg.isAnnounceInChat()), btn -> {
                    cfg.setAnnounceInChat(!cfg.isAnnounceInChat());
                    cfg.save();
                    btn.setMessage(onOff("Announce In Chat", cfg.isAnnounceInChat()));
                }).bounds(contentX, y, 220, 18).build());
        y += 24;

        // Read by DeviceTimesFeature (appends the P3 split time to each device/lever completion line) -
        // was saved/loaded but had no toggle anywhere (2026-09-15 persistence audit).
        widgets.add(SettingsButtonWidget.builder(onOff("Device/Lever Times", cfg.isAnnounceDeviceTimes()), btn -> {
                    cfg.setAnnounceDeviceTimes(!cfg.isAnnounceDeviceTimes());
                    cfg.save();
                    btn.setMessage(onOff("Device/Lever Times", cfg.isAnnounceDeviceTimes()));
                }).bounds(contentX, y, 220, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                com.killer560.hub.gui.SectionHeaders.header("M7 Phase 5 Lines", false), Minecraft.getInstance().font));
        y += 14;
        widgets.add(SettingsButtonWidget.builder(onOff("P5 Dragon Lines", cfg.isP5DragonLines()), btn -> {
                    cfg.setP5DragonLines(!cfg.isP5DragonLines());
                    cfg.save();
                    btn.setMessage(onOff("P5 Dragon Lines", cfg.isP5DragonLines()));
                }).bounds(contentX, y, 160, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("P5 Relic Lines", cfg.isP5RelicLines()), btn -> {
                    cfg.setP5RelicLines(!cfg.isP5RelicLines());
                    cfg.save();
                    btn.setMessage(onOff("P5 Relic Lines", cfg.isP5RelicLines()));
                }).bounds(contentX + 168, y, 160, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(p5PosLabel(cfg), btn -> {
                    cfg.setP5LinesRight(!cfg.isP5LinesRight());
                    cfg.save();
                    btn.setMessage(p5PosLabel(cfg));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Odin's splits: Blood Open/Clear, Portal Entry, each boss phase"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7(E-F7, e.g. Maxor/Storm/Terminals/Goldor/Necron) and Total - armed"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7on \"Starting in 1 second.\", clock starts on Mort's line."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component p5PosLabel(SplitTimersConfig cfg) {
        return Component.literal("P5 Lines Position: §6" + (cfg.isP5LinesRight() ? "Right" : "Below"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
