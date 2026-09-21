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

        // Clear-phase splits (2026-09-16) - see SplitTimersFeature's clearPrefix()/WatcherMoveTracker.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                com.killer560.hub.gui.SectionHeaders.header("Clear Phase", false), Minecraft.getInstance().font));
        y += 14;
        widgets.add(SettingsButtonWidget.builder(onOff("Clear Splits", cfg.isClearSplits()), btn -> {
                    cfg.setClearSplits(!cfg.isClearSplits());
                    cfg.save();
                    btn.setMessage(onOff("Clear Splits", cfg.isClearSplits()));
                }).bounds(contentX, y, 160, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Watcher Move", cfg.isWatcherMoveSplit()), btn -> {
                    cfg.setWatcherMoveSplit(!cfg.isWatcherMoveSplit());
                    cfg.save();
                    btn.setMessage(onOff("Watcher Move", cfg.isWatcherMoveSplit()));
                }).bounds(contentX + 168, y, 160, 18).build());
        y += 22;

        // Core entry times (2026-09-16, killer560: "time to enter core after terms finish").
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                com.killer560.hub.gui.SectionHeaders.header("Core Entry (F7/M7)", false), Minecraft.getInstance().font));
        y += 14;
        widgets.add(SettingsButtonWidget.builder(onOff("Core Entry Times", cfg.isCoreEntryTimes()), btn -> {
                    cfg.setCoreEntryTimes(!cfg.isCoreEntryTimes());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 18).build());
        y += 22;
        if (cfg.isCoreEntryTimes()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Slowest To Chat", cfg.isCoreEntrySlowestChat()), btn -> {
                        cfg.setCoreEntrySlowestChat(!cfg.isCoreEntrySlowestChat());
                        cfg.save();
                        btn.setMessage(onOff("Slowest To Chat", cfg.isCoreEntrySlowestChat()));
                    }).bounds(contentX, y, 160, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Slowest To Party", cfg.isCoreEntrySlowestParty()), btn -> {
                        cfg.setCoreEntrySlowestParty(!cfg.isCoreEntrySlowestParty());
                        cfg.save();
                        btn.setMessage(onOff("Slowest To Party", cfg.isCoreEntrySlowestParty()));
                    }).bounds(contentX + 168, y, 160, 18).build());
            y += 22;
            // killer560, 2026-09-20: "at the very bottom of the split timers show the slowest person into
            // core and their time" - separate from the chat/party lines above, which stay chat-only.
            widgets.add(SettingsButtonWidget.builder(onOff("Slowest In HUD", cfg.isCoreEntrySlowestHud()), btn -> {
                        cfg.setCoreEntrySlowestHud(!cfg.isCoreEntrySlowestHud());
                        cfg.save();
                        btn.setMessage(onOff("Slowest In HUD", cfg.isCoreEntrySlowestHud()));
                    }).bounds(contentX, y, 220, 18).build());
            y += 22;
        }

        // 2026-09-20 killer560 change list: divider bar, Boss Entry/Boss running timers, lagless times and
        // the bottom Total-with/without-lag + Lag Lost lines - see SplitTimersFeature's class doc and
        // SplitLagClock for what "lagless" means. All new, all off by default.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                com.killer560.hub.gui.SectionHeaders.header("Split Layout", false), Minecraft.getInstance().font));
        y += 14;
        widgets.add(SettingsButtonWidget.builder(onOff("Clear/Boss Divider", cfg.isClearBossDivider()), btn -> {
                    cfg.setClearBossDivider(!cfg.isClearBossDivider());
                    cfg.save();
                    btn.setMessage(onOff("Clear/Boss Divider", cfg.isClearBossDivider()));
                }).bounds(contentX, y, 160, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Boss Entry Timer", cfg.isBossEntryTimer()), btn -> {
                    cfg.setBossEntryTimer(!cfg.isBossEntryTimer());
                    cfg.save();
                    btn.setMessage(onOff("Boss Entry Timer", cfg.isBossEntryTimer()));
                }).bounds(contentX + 168, y, 160, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("Boss Timer", cfg.isBossTimer()), btn -> {
                    cfg.setBossTimer(!cfg.isBossTimer());
                    cfg.save();
                    btn.setMessage(onOff("Boss Timer", cfg.isBossTimer()));
                }).bounds(contentX, y, 160, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Lagless Times", cfg.isLaglessTimes()), btn -> {
                    cfg.setLaglessTimes(!cfg.isLaglessTimes());
                    cfg.save();
                    btn.setMessage(onOff("Lagless Times", cfg.isLaglessTimes()));
                }).bounds(contentX + 168, y, 160, 18).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                com.killer560.hub.gui.SectionHeaders.header("Run Totals", false), Minecraft.getInstance().font));
        y += 14;
        widgets.add(SettingsButtonWidget.builder(onOff("Total With Lag", cfg.isTotalWithLag()), btn -> {
                    cfg.setTotalWithLag(!cfg.isTotalWithLag());
                    cfg.save();
                    btn.setMessage(onOff("Total With Lag", cfg.isTotalWithLag()));
                }).bounds(contentX, y, 160, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Total Without Lag", cfg.isTotalWithoutLag()), btn -> {
                    cfg.setTotalWithoutLag(!cfg.isTotalWithoutLag());
                    cfg.save();
                    btn.setMessage(onOff("Total Without Lag", cfg.isTotalWithoutLag()));
                }).bounds(contentX + 168, y, 160, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("Lag Lost", cfg.isLagLostLine()), btn -> {
                    cfg.setLagLostLine(!cfg.isLagLostLine());
                    cfg.save();
                    btn.setMessage(onOff("Lag Lost", cfg.isLagLostLine()));
                }).bounds(contentX, y, 160, 18).build());
        y += 26;

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

        return widgets;
    }

    private static Component p5PosLabel(SplitTimersConfig cfg) {
        return Component.literal("P5 Lines Position: §6" + (cfg.isP5LinesRight() ? "Right" : "Below"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
