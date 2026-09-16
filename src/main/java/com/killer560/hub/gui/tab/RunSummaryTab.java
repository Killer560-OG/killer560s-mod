package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.runsummary.RunHistoryStore;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.runsummary.RunSummaryConfig;
import com.killer560.hub.runsummary.RunSummaryFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dungeon Run Summary settings + the history browser.
 * <p>
 * Same two-views-one-tab shape {@link FastLeapTab} uses (2026-09-15, killer560's "left half is the title,
 * right half is an edit button... pressing it opens all the settings for that one"): the LIST view is the
 * settings plus one row per stored run (floor/time/score/date on the left, a "View" button on the right),
 * and the DETAIL view is "&lt; Back" plus that one run's full summary. Which one is showing is plain tab
 * state ({@link #viewing}) plus {@code requestRebuild}, deliberately NOT a separate {@code Screen}, so the
 * mod menu's search box, scrolling and tab chrome all keep working.
 * <p>
 * Legit feature - orange headers ({@link SectionHeaders#header(String, boolean)} with {@code false}).
 * Read-only: nothing here clicks, moves or sends anything.
 */
public class RunSummaryTab extends BaseTab {

    private static final int HEADER_H = 14;
    private static final int ROW = 18;
    private static final int GAP = 4;
    private static final int BACK_W = 76;
    private static final int VIEW_W = 52;
    /** How many rows the list shows at once - the rest stay in the file and in the JSON. */
    private static final int MAX_LIST_ROWS = 40;

    /** null = the settings + history list; otherwise the run whose full summary is open. */
    private RunRecord viewing = null;

    public RunSummaryTab() {
        super("Run Summary");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        if (viewing != null) {
            return buildDetail(viewing, contentX, contentY, contentWidth, requestRebuild);
        }
        return buildList(contentX, contentY, contentWidth, requestRebuild);
    }

    // ---------------------------------------------------------------- list view

    private List<AbstractWidget> buildList(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        RunSummaryConfig cfg = RunSummaryConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Run Summary", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(label(contentX, y, contentWidth, "§7Records one entry per finished dungeon run - splits, devices,"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7secrets, crypts, deaths, both scores, class and party - then"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7keeps them so you can open any past run again. Nothing is sent."));
        y += 16;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(header(contentX, y, contentWidth, "Options"));
        y += HEADER_H;

        widgets.add(SettingsButtonWidget.builder(onOff("Chat Summary", cfg.isChatSummary()), btn -> {
                    cfg.setChatSummary(!cfg.isChatSummary());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, half(contentWidth), ROW).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Announce Personal Bests", cfg.isAnnouncePersonalBests()), btn -> {
                    cfg.setAnnouncePersonalBests(!cfg.isAnnouncePersonalBests());
                    cfg.save();
                    btn.setMessage(onOff("Announce Personal Bests", cfg.isAnnouncePersonalBests()));
                }).bounds(contentX + half(contentWidth) + GAP, y, half(contentWidth), ROW).build());
        y += ROW + GAP;

        if (cfg.isChatSummary()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Don't Repeat Split Timers", cfg.isAvoidDuplicateChat()), btn -> {
                        cfg.setAvoidDuplicateChat(!cfg.isAvoidDuplicateChat());
                        cfg.save();
                        btn.setMessage(onOff("Don't Repeat Split Timers", cfg.isAvoidDuplicateChat()));
                    }).bounds(contentX, y, contentWidth, ROW).build());
            y += ROW + GAP;
            widgets.add(label(contentX, y, contentWidth,
                    "§7While Split Timers is announcing in chat, the phase lines are left"));
            y += 12;
            widgets.add(label(contentX, y, contentWidth, "§7out of this summary so the two never post the same thing."));
            y += 14;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Record Device/Terminal Times", cfg.isRecordDeviceTimes()), btn -> {
                    cfg.setRecordDeviceTimes(!cfg.isRecordDeviceTimes());
                    cfg.save();
                    btn.setMessage(onOff("Record Device/Terminal Times", cfg.isRecordDeviceTimes()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + GAP;

        int range = RunSummaryConfig.MAX_RUNS - RunSummaryConfig.MIN_RUNS;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, keepText(),
                (cfg.getMaxRuns() - RunSummaryConfig.MIN_RUNS) / (double) range) {
            @Override
            protected void updateMessage() {
                setMessage(keepText());
            }

            @Override
            protected void applyValue() {
                int runs = (int) Math.round((RunSummaryConfig.MIN_RUNS + this.value * range) / 5.0) * 5;
                RunSummaryConfig c = RunSummaryConfig.getInstance();
                c.setMaxRuns(runs);
                c.save();
                RunHistoryStore.applyMaxRuns();
            }
        });
        y += 24;

        widgets.add(header(contentX, y, contentWidth, "History"));
        y += HEADER_H;

        List<RunRecord> runs = RunHistoryStore.runs();
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Last Run"), btn -> {
                    RunRecord latest = RunHistoryStore.latest();
                    if (latest != null) {
                        viewing = latest;
                        requestRebuild.run();
                    }
                }).bounds(contentX, y, half(contentWidth), ROW).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("§cClear History"), btn -> {
                    RunHistoryStore.clear();
                    viewing = null;
                    requestRebuild.run();
                }).bounds(contentX + half(contentWidth) + GAP, y, half(contentWidth), ROW).build());
        y += ROW + GAP + 2;

        widgets.add(label(contentX, y, contentWidth, statusText(runs.size())));
        y += 14;

        String bests = bestsText();
        if (bests != null) {
            widgets.add(label(contentX, y, contentWidth, bests));
            y += 14;
        }

        if (runs.isEmpty()) {
            widgets.add(label(contentX, y, contentWidth, "§7No runs stored yet - finish a dungeon with this on."));
            return widgets;
        }

        int listWidth = Math.max(1, contentWidth - VIEW_W - GAP);
        int shown = Math.min(runs.size(), MAX_LIST_ROWS);
        for (int i = 0; i < shown; i++) {
            RunRecord run = runs.get(i);
            widgets.add(label(contentX, y + 5, listWidth, RunSummaryFeature.listLine(run)));
            widgets.add(SettingsButtonWidget.builder(Component.literal("View"), btn -> {
                        viewing = run;
                        requestRebuild.run();
                    }).bounds(contentX + listWidth + GAP, y, VIEW_W, ROW).build());
            y += ROW + GAP;
        }
        if (runs.size() > shown) {
            widgets.add(label(contentX, y, contentWidth,
                    "§7... and " + (runs.size() - shown) + " older run(s) in history.json"));
        }
        return widgets;
    }

    // ---------------------------------------------------------------- one run's summary

    private List<AbstractWidget> buildDetail(RunRecord run, int contentX, int contentY, int contentWidth,
                                             Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
            viewing = null;
            requestRebuild.run();
        }).bounds(contentX, y, Math.min(BACK_W, Math.max(1, contentWidth)), ROW).build());
        y += ROW + GAP + 2;

        widgets.add(header(contentX, y, contentWidth, run.floorLabel() + " Run"));
        y += HEADER_H;

        for (String line : RunSummaryFeature.summaryLines(run)) {
            if (line.isEmpty()) {
                y += 6;
                continue;
            }
            widgets.add(label(contentX, y, contentWidth, line));
            y += 12;
        }
        return widgets;
    }

    // ---------------------------------------------------------------- helpers

    private static String statusText(int stored) {
        if (RunSummaryFeature.isTrackingRun()) {
            return "§6Recording the current run...  §7(" + stored + " stored)";
        }
        return "§7Stored runs: §f" + stored + " §7of " + RunSummaryConfig.getInstance().getMaxRuns();
    }

    /** "Best: F7 6m 12.30s / 305" per floor, at most a few floors so the line stays readable. */
    private static String bestsText() {
        Map<String, long[]> bests = RunHistoryStore.bestsByFloor();
        if (bests.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("§7Bests: ");
        int shown = 0;
        for (Map.Entry<String, long[]> entry : bests.entrySet()) {
            if (shown++ >= 4) {
                sb.append("§8...");
                break;
            }
            if (shown > 1) {
                sb.append("§8, ");
            }
            long time = entry.getValue()[0];
            long score = entry.getValue()[1];
            sb.append("§6").append(entry.getKey()).append(" §f")
                    .append(time > 0 ? RunRecord.formatTime(time) : "-")
                    .append("§7/").append(score >= 0 ? String.valueOf(score) : "-");
        }
        return sb.toString();
    }

    private static Component keepText() {
        return Component.literal("Runs Kept: " + RunSummaryConfig.getInstance().getMaxRuns());
    }

    private static int half(int contentWidth) {
        return Math.max(1, (contentWidth - GAP) / 2);
    }

    private static StringWidget header(int x, int y, int width, String title) {
        return new StringWidget(x, y, width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font);
    }

    private static StringWidget label(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal(text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
