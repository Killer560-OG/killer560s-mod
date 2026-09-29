package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.roomsim.RoomLibraryScreen;
import com.killer560.hub.roomsim.RoomRecorderConfig;
import com.killer560.hub.roomsim.RoomRecorderFeature;
import com.killer560.hub.util.KeyUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Room Recorder - the F7 loop that fills the sim's room library.
 *
 * <p>killer560 (2026-09-28): "make it an actual setting in its own tab for now." Until now the only way in was
 * {@code /killer560 roomrecorder}, which is fine once you know it and invisible if you do not.
 *
 * <p>The toggle reads {@link RoomRecorderFeature#isRunning()} rather than a saved boolean, and that is the point
 * rather than an oversight: the recorder is a RUNNING PROCESS, not a preference. It stops itself on any keypress,
 * on leaving the Map Logger instance and on a five-puzzle run, so a switch that remembered "on" across those
 * would sit there claiming to be on while nothing was happening. What IS remembered is the two things that are
 * genuinely settings - the five-puzzle pause and the resume key.
 *
 * <p>The tab also says plainly when the recorder cannot run here. It only works in the Map Logger instance, and
 * a greyed-out switch with a reason beats a switch that appears to work and then refuses in chat.
 */
public class RoomRecorderTab extends BaseTab {

    public RoomRecorderTab() {
        super("Room Recorder");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        RoomRecorderConfig cfg = RoomRecorderConfig.getInstance();
        boolean here = RoomRecorderFeature.inRecorderInstance();
        int y = contentY;

        if (!here) {
            w.add(SettingsButtonWidget.builder(
                            Component.literal("§7Only runs in the Map Logger instance"), btn -> {
                            })
                    .bounds(contentX, y, contentWidth, 20).build());
            y += 24;
        }

        // The live state, not a stored one - see the class note.
        w.add(SettingsButtonWidget.builder(runLabel(), btn -> {
                    if (!RoomRecorderFeature.inRecorderInstance()) {
                        return;
                    }
                    if (RoomRecorderFeature.isRunning()) {
                        RoomRecorderFeature.stop("settings");
                    } else {
                        RoomRecorderFeature.start();
                    }
                    btn.setMessage(runLabel());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        w.add(SettingsButtonWidget.builder(onOff("Pause On 5-Puzzle Runs", cfg.isPauseOnFivePuzzles()), btn -> {
                    cfg.setPauseOnFivePuzzles(!cfg.isPauseOnFivePuzzles());
                    cfg.save();
                    btn.setMessage(onOff("Pause On 5-Puzzle Runs", cfg.isPauseOnFivePuzzles()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        w.add(SettingsButtonWidget.builder(
                        Component.literal("Pause / Resume Key: §6" + KeyUtil.bindDisplayName(cfg.getResumeKeyCode())),
                        btn -> Minecraft.getInstance().setScreen(
                                new RoomLibraryScreen(Minecraft.getInstance().screen)))
                .bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        w.add(SettingsButtonWidget.builder(
                        Component.literal("Room Library  §7(" + RoomLibrary.completeCount() + " captured, "
                                + RoomLibrary.incomplete().size() + " unfinished)"),
                        btn -> Minecraft.getInstance().setScreen(
                                new RoomLibraryScreen(Minecraft.getInstance().screen)))
                .bounds(contentX, y, contentWidth, 20).build());
        return w;
    }

    private static Component runLabel() {
        if (RoomRecorderFeature.isPaused()) {
            return Component.literal("Recording: §ePAUSED §7(press the resume key)");
        }
        return Component.literal("Recording: " + (RoomRecorderFeature.isRunning() ? "§aON" : "§cOFF"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
