package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.sessionrecorder.SessionRecorder;
import com.killer560.hub.sessionrecorder.SessionRecorderConfig;
import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SessionRecorderSink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Misc's Session Recorder card (2026-10-19): the switch, where the files go, and whether this instance blocks it. */
public class SessionRecorderTab extends BaseTab {

    public SessionRecorderTab() {
        super("Session Recorder");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        SessionRecorderConfig cfg = SessionRecorderConfig.getInstance();
        boolean blocked = SessionRecorder.blockedHere();
        int y = contentY;

        w.add(SettingsButtonWidget.builder(onOff(cfg.isEnabled() && !blocked), btn -> {
                    if (SessionRecorder.blockedHere()) {
                        cfg.setEnabled(false);
                    } else {
                        cfg.setEnabled(!cfg.isEnabled());
                    }
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        String status;
        if (blocked) {
            status = "§cDisabled in this instance (" + SessionRecorder.DISABLED_MARKER + ")";
        } else if (SessionRecorderSink.active()) {
            Path file = SessionRecorderSink.file();
            status = "§aRecording §7to " + (file == null ? "?" : file.getFileName());
        } else if (cfg.isEnabled() && SessionRecorderSink.wasCut()) {
            status = "§cStopped: the file reached 20 MB";
        } else if (cfg.isEnabled()) {
            status = "§7Starting on the next tick";
        } else {
            status = "§7Not recording";
        }
        w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(status), Minecraft.getInstance().font));
        y += 16;

        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Files", false),
                Minecraft.getInstance().font));
        y += 14;
        w.add(SettingsButtonWidget.builder(Component.literal("Open Diagnostics Folder"), btn -> {
                    try {
                        Path dir = ModPaths.root().resolve(ModPaths.folderFor("killer560smod-sessionlog-"));
                        Files.createDirectories(dir);
                        ExternalOpen.path(dir);
                    } catch (Exception ignored) {
                    }
                }).bounds(contentX, y, contentWidth, 20).build());
        return w;
    }

    private static Component onOff(boolean value) {
        return Component.literal("Session Recorder: " + (value ? "§aON" : "§cOFF"));
    }
}
