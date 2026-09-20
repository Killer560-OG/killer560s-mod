package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.livemap.LiveMapConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive Map - the full-screen map you open with a key, split out of the old Live Map tab per killer560
 * (2026-09-17): "Interactive Map should be in its own tab". Room/door colours and checkmarks are shared with the
 * HUD map and live in {@link LiveMapTab}; only what is specific to this screen is here.
 * <p>
 * <b>Cheat build only</b> - killer560, 2026-09-20: "the interactive map is the one where I click on a room and it
 * etherwarps me to that room. and it can also start my secret route by clicking on it again and whatnot. That is a
 * cheat." Clicking a room to teleport is the whole point of the screen, so the tab is only added to {@link NewTab}
 * behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} and gets the red title; the legit jar does not have it at all.
 */
public class InteractiveMapTab extends BaseTab implements KeyCaptureTab {

    private enum KeyTarget { OPEN, START, LOCKED_DOOR, BLOOD_RUSH }

    private KeyTarget capturing = null;

    public InteractiveMapTab() {
        super("Interactive Map");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colB = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(LiveMapTab.onOff("Interactive Map", cfg.isInteractiveMapEnabledRaw()), btn -> {
                    cfg.setInteractiveMapEnabled(!cfg.isInteractiveMapEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isInteractiveMapEnabledRaw()) {
            widgets.add(keyButton("Open Key", KeyTarget.OPEN, cfg.getOpenKeyCode(), contentX, y, colW));
            widgets.add(SettingsButtonWidget.builder(closeOnText(cfg), btn -> {
                        cfg.setCloseOnRepress(!cfg.isCloseOnRepress());
                        cfg.save();
                        btn.setMessage(closeOnText(cfg));
                    }).bounds(colB, y, colW, 18).build());
            y += 20;
            widgets.add(LiveMapTab.toggle("Open From HUD Click", cfg::isOpenFromHudClick, cfg::setOpenFromHudClick,
                    cfg, contentX, y, colW));
            widgets.add(LiveMapTab.cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getMapRoomLabels,
                    cfg::setMapRoomLabels, cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.slider("Map Scale", cfg::getMapScale, v -> cfg.setMapScale((float) v), 1, 10, "",
                    cfg, contentX, y, colW));
            widgets.add(LiveMapTab.cycle("Player Names", LiveMapConfig.PLAYER_NAME_MODES, cfg::getPlayerNames,
                    cfg::setPlayerNames, cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.toggle("Class Colours", cfg::isClassBorderColour, cfg::setClassBorderColour,
                    cfg, contentX, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- teleport pathing
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Automation", true), Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(LiveMapTab.onOff("Teleport Pathing", cfg.isPathingEnabledRaw()), btn -> {
                    cfg.setPathingEnabled(!cfg.isPathingEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isPathingEnabledRaw()) {
            widgets.add(keyButton("Start Key", KeyTarget.START, cfg.getStartKeyCode(), contentX, y, colW));
            widgets.add(keyButton("Locked Door Key", KeyTarget.LOCKED_DOOR, cfg.getLockedDoorKeyCode(), colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.toggle("Face Door on Arrival", cfg::isFaceDoorOnArrival, cfg::setFaceDoorOnArrival,
                    cfg, contentX, y, colW));
            widgets.add(LiveMapTab.toggle("Keep Chunks Loaded", cfg::isKeepChunksLoadedRaw, cfg::setKeepChunksLoaded,
                    cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.slider("Path Threads", cfg::getThreads, v -> cfg.setThreads((int) Math.round(v)),
                    1, 16, "", cfg, contentX, y, colW));
            widgets.add(LiveMapTab.slider("Path Timeout", cfg::getTimeoutMs,
                    v -> cfg.setTimeoutMs((int) (Math.round(v / 50.0) * 50)), 200, 1000, "ms", cfg, colB, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- auto blood rush
        widgets.add(SettingsButtonWidget.builder(LiveMapTab.onOff("Auto Blood Rush", cfg.isBloodRushEnabledRaw()), btn -> {
                    cfg.setBloodRushEnabled(!cfg.isBloodRushEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isBloodRushEnabledRaw()) {
            widgets.add(keyButton("Blood Rush Key", KeyTarget.BLOOD_RUSH, cfg.getBloodRushKeyCode(), contentX, y, colW));
            widgets.add(LiveMapTab.toggle("Click Door on Arrival", cfg::isBloodRushClickDoor, cfg::setBloodRushClickDoor,
                    cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.slider("Door Timeout", cfg::getBloodRushDoorTimeoutSec,
                    v -> cfg.setBloodRushDoorTimeoutSec((int) Math.round(v)), 5, 60, "s", cfg, contentX, y, colW));
            y += 20;
        }
        return widgets;
    }

    private AbstractWidget keyButton(String label, KeyTarget target, int code, int x, int y, int w) {
        Component text = capturing == target ? Component.literal(label + ": Press any key...") : LiveMapTab.keyText(label, code);
        return SettingsButtonWidget.builder(text, btn -> {
                    capturing = target;
                    btn.setMessage(Component.literal(label + ": Press any key..."));
                }).bounds(x, y, w, 18).build();
    }

    private static Component closeOnText(LiveMapConfig cfg) {
        return Component.literal("Close On: " + (cfg.isCloseOnRepress() ? "Repress" : "Release"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        if (capturing != null) {
            switch (capturing) {
                case OPEN -> cfg.setOpenKeyCode(code);
                case START -> cfg.setStartKeyCode(code);
                case LOCKED_DOOR -> cfg.setLockedDoorKeyCode(code);
                case BLOOD_RUSH -> cfg.setBloodRushKeyCode(code);
            }
        }
        capturing = null;
        cfg.save();
    }
}
