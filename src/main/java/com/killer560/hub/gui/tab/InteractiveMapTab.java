package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.util.KeyUtil;
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
 * <p>
 * killer560, 2026-09-27: "You do not need teleport pathing. The entire portion of interactive map is the teleport
 * pathing. Add into interactive map as a whole the settings under the pathing section of it." - the old separate
 * "Teleport Pathing" on/off is gone; its settings (start/locked-door keys, face-door-on-arrival, keep chunks
 * loaded, timeout) now sit directly under this tab's own Automation header, gated only by Interactive Map's own
 * toggle. "Move auto blood rush into its own section" gave Blood Rush its own header below Automation, and "Remove
 * path threads it should be the minimum amount of threads required to reach the end room" dropped the old 1-16
 * slider entirely - see {@code EtherwarpPathfinder.threadsFor}.
 */
public class InteractiveMapTab extends BaseTab implements KeyCaptureTab {

    private enum KeyTarget { OPEN, START, LOCKED_DOOR, GO_SECRET, BLOOD_RUSH }

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
            // killer560, 2026-09-20: "the room-labels option should sit nearer the top of the tab" - moved up to
            // the first settings row, next to the Open Key, instead of sharing a row with Open From HUD Click.
            widgets.add(keyButton("Open Key", KeyTarget.OPEN, cfg.getOpenKeyCode(), contentX, y, colW));
            widgets.add(LiveMapTab.cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getMapRoomLabels,
                    cfg::setMapRoomLabels, cfg, colB, y, colW));
            y += 20;
            widgets.add(SettingsButtonWidget.builder(mapModeText(cfg), btn -> {
                        cfg.setCloseOnRepress(!cfg.isCloseOnRepress());
                        cfg.save();
                        btn.setMessage(mapModeText(cfg));
                    }).bounds(contentX, y, colW, 18).build());
            widgets.add(LiveMapTab.toggle("Open From HUD Click", cfg::isOpenFromHudClick, cfg::setOpenFromHudClick,
                    cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.slider("Map Scale", cfg::getMapScale, v -> cfg.setMapScale((float) v), 1, 10, "",
                    cfg, contentX, y, colW));
            widgets.add(LiveMapTab.cycle("Player Names", LiveMapConfig.PLAYER_NAME_MODES, cfg::getPlayerNames,
                    cfg::setPlayerNames, cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.toggle("Class Colours", cfg::isClassBorderColour, cfg::setClassBorderColour,
                    cfg, contentX, y, colW));
            widgets.add(LiveMapTab.toggle("Extra Info", cfg::isShowExtraInfo, cfg::setShowExtraInfo,
                    cfg, colB, y, colW));
            y += 24;

            // ---------------------------------------------------------------- automation (was "Teleport Pathing")
            // killer560: "the entire portion of interactive map is the teleport pathing" - no on/off of its own
            // any more; these settings run whenever Interactive Map itself is on.
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    SectionHeaders.header("Automation", true), Minecraft.getInstance().font));
            y += 16;
            // killer560: "The button prebound as lmb and rmb should be customizable in settings, those are the
            // ones currently under start key and locked door key" - both now take a mouse button (see
            // supportsMouseCapture()/onMouseCaptured below), which needed KeyUtil's mouse-bind encoding first.
            widgets.add(keyButton("Start Key", KeyTarget.START, cfg.getStartKeyCode(), contentX, y, colW));
            widgets.add(keyButton("Locked Door Key", KeyTarget.LOCKED_DOOR, cfg.getLockedDoorKeyCode(), colB, y, colW));
            y += 20;
            // killer560, 2026-09-29: "Make a third button bind for go to a room and secret it. You can have an
            // option as well as a toggle ... that is double clicking room to do secret as an option." The bind
            // and the double-press toggle sit side by side because they are the same action reached two ways.
            widgets.add(keyButton("Go + Secret Key", KeyTarget.GO_SECRET, cfg.getGoSecretKeyCode(), contentX, y, colW));
            widgets.add(LiveMapTab.toggle("Double-Press Secrets", cfg::isMapDoublePressStartNode,
                    cfg::setMapDoublePressStartNode, cfg, colB, y, colW));
            y += 20;
            widgets.add(LiveMapTab.toggle("Face Door on Arrival", cfg::isFaceDoorOnArrival, cfg::setFaceDoorOnArrival,
                    cfg, contentX, y, colW));
            widgets.add(LiveMapTab.toggle("Keep Chunks Loaded", cfg::isKeepChunksLoadedRaw, cfg::setKeepChunksLoaded,
                    cfg, colB, y, colW));
            y += 20;
            // "Path Threads" (1-16) is gone - killer560: "Remove path threads it should be the minimum amount of
            // threads required to reach the end room." The search now sizes its own worker count off the room
            // path itself (EtherwarpPathfinder.threadsFor), so Path Timeout takes the full row on its own.
            widgets.add(LiveMapTab.slider("Path Timeout", cfg::getTimeoutMs,
                    v -> cfg.setTimeoutMs((int) (Math.round(v / 50.0) * 50)), 200, 1000, "ms", cfg, contentX, y, contentWidth));
            y += 20;
            // killer560, 2026-09-29: "If I double click a room then it should auto pathfind to the start node to
            // start secreting. If I click a different room mid path then it goes doesn't have to be a double
            // click same with dooring." Both halves of that are toggles because each changes what an existing
            // press already did - the mid-path press used to be swallowed, and a second press used to be two
            // ordinary ones.
            widgets.add(LiveMapTab.toggle("Retarget Mid-Path", cfg::isMapRetargetMidPath, cfg::setMapRetargetMidPath,
                    cfg, contentX, y, colW));
            // Shown whether or not Double-Press Secrets is on: the window is also what stops two quick presses
            // on the same room from reading as two separate goals, so it is never dead weight.
            widgets.add(LiveMapTab.slider("Double-Press Window", cfg::getMapDoublePressMs,
                    v -> cfg.setMapDoublePressMs((int) (Math.round(v / 50.0) * 50)), 150, 1000, "ms",
                    cfg, colB, y, colW));
            y += 24;

            // ---------------------------------------------------------------- etherwarp path preview
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    SectionHeaders.header("Etherwarp Path", true), Minecraft.getInstance().font));
            y += 16;
            widgets.add(LiveMapTab.toggle("Show Etherwarp Path", cfg::isShowEtherwarpPathRaw, cfg::setShowEtherwarpPath,
                    cfg, contentX, y, contentWidth));
            y += 24;
        }

        // ---------------------------------------------------------------- auto blood rush (killer560: "Move auto
        // blood rush into its own section")
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Auto Blood Rush", true), Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(LiveMapTab.onOff("Auto Blood Rush", cfg.isBloodRushEnabledRaw()), btn -> {
                    cfg.setBloodRushEnabled(!cfg.isBloodRushEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isBloodRushEnabledRaw()) {
            widgets.add(keyButton("Blood Rush Key", KeyTarget.BLOOD_RUSH, cfg.getBloodRushKeyCode(), contentX, y, colW));
            // "Click Door on Arrival" removed (killer560: "remove the click door on arrival setting from auto
            // blood rush") - opening the door is Auto Door Opener's job now, so Door Timeout takes the full row.
            widgets.add(LiveMapTab.slider("Door Timeout", cfg::getBloodRushDoorTimeoutSec,
                    v -> cfg.setBloodRushDoorTimeoutSec((int) Math.round(v)), 5, 60, "s", cfg, colB, y, colW));
            y += 20;
        }
        return widgets;
    }

    private AbstractWidget keyButton(String label, KeyTarget target, int code, int x, int y, int w) {
        Component text = capturing == target ? Component.literal(label + ": Press any key...") : keyText(label, code);
        return SettingsButtonWidget.builder(text, btn -> {
                    capturing = target;
                    btn.setMessage(Component.literal(label + ": Press any key..."));
                }).bounds(x, y, w, 18).build();
    }

    /** Same shape as {@link LiveMapTab#keyText}, but through {@link KeyUtil#bindDisplayName} so a mouse-button
     *  code (negative, see {@link KeyUtil#MOUSE_CODE_BASE}) shows "Left Button"/"Right Button"/... instead of
     *  being mistaken for "Not Set" - {@code LiveMapTab.keyText} only ever handled keyboard codes. */
    private static Component keyText(String label, int code) {
        return Component.literal(label + ": §b" + KeyUtil.bindDisplayName(code));
    }

    private static Component mapModeText(LiveMapConfig cfg) {
        // killer560, 2026-09-27: "have an option where pressing the button to open the map is off of click and
        // it stays open until i press it again or hold to keep it open" - already exactly what "Close On"
        // (Release/Repress) did; relabelled to Hold/Toggle since that is how he actually described the two modes.
        return Component.literal("Map Mode: " + (cfg.isCloseOnRepress() ? "Toggle" : "Hold"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode;
        applyCapture(cfg, code);
    }

    /** killer560: "I should be able to set keybinds to mouse buttons as well. Right now i cannot do lmb or rmb
     *  or the side buttons." - Open/Start/Locked Door/Blood Rush keys all go through the same capture, so this
     *  is the one place that needed to change for all four. */
    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        applyCapture(LiveMapConfig.getInstance(), KeyUtil.codeForMouseButton(button));
    }

    private void applyCapture(LiveMapConfig cfg, int code) {
        if (capturing != null) {
            switch (capturing) {
                case OPEN -> cfg.setOpenKeyCode(code);
                case START -> cfg.setStartKeyCode(code);
                case LOCKED_DOOR -> cfg.setLockedDoorKeyCode(code);
                case GO_SECRET -> cfg.setGoSecretKeyCode(code);
                case BLOOD_RUSH -> cfg.setBloodRushKeyCode(code);
            }
        }
        capturing = null;
        cfg.save();
    }
}
