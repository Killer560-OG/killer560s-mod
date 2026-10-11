package com.killer560.hub.gui.tab;

import com.killer560.hub.autokick.AutoKickApi;
import com.killer560.hub.autokick.AutoKickConfig;
import com.killer560.hub.autokick.AutoKickConfig.ActionMode;
import com.killer560.hub.autokick.AutoKickConfig.Floor;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Kick - Party Finder only (killer560, 2026-10-09). See {@code autokick.AutoKickFeature} for the logic: a joiner
 * whose S+ personal best on the queued floor is slower than that floor's slider is warned about or kicked.
 */
public class AutoKickTab extends BaseTab {

    public AutoKickTab() {
        super("Auto Kick");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        AutoKickConfig cfg = AutoKickConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Kick", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        // ---------------------------------------------------------------- Action
        // killer560, 2026-10-09: Party Finder only, and the "this can remove real ...", "0 = disabled" and "ships ..."
        // notes are gone. The Action Mode is Warn Only or Kick, and the floor being queued sits beside it.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Action", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(modeText(cfg.getMode()), btn -> {
                    cycleMode(cfg, 1);
                    btn.setMessage(modeText(cfg.getMode()));
                }).secondaryPress(btn -> {
                    cycleMode(cfg, -1);
                    btn.setMessage(modeText(cfg.getMode()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(queueFloorText(cfg.getQueueFloor()), btn -> {
                    cycleQueueFloor(cfg, 1);
                    btn.setMessage(queueFloorText(cfg.getQueueFloor()));
                }).secondaryPress(btn -> {
                    cycleQueueFloor(cfg, -1);
                    btn.setMessage(queueFloorText(cfg.getQueueFloor()));
                }).bounds(colBX, y, colW, 18).build());
        y += 26;

        // ---------------------------------------------------------------- Per-floor S+ times
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Slowest S+ Allowed", false), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Populate My Times From API"), btn -> {
                    btn.active = false;
                    ModChat.send("Auto Kick", ModChat.text("Looking up your dungeon clear times..."));
                    AutoKickApi.populate("").whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                        if (error != null) {
                            ModChat.send("Auto Kick", ModChat.bad(ProfileViewerApi.messageFor(error)));
                        } else {
                            ModChat.send("Auto Kick",
                                    ModChat.text("Set "), ModChat.value(String.valueOf(result.floorsSet())),
                                    ModChat.text(" floor time(s) from your S+ personal bests."));
                        }
                        requestRebuild.run();
                    }));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        Floor[] floors = Floor.values();
        // First 7 = F1-F7, next 7 = M1-M7 (see Floor's own declaration order) - left/right columns.
        for (int i = 0; i < 7; i++) {
            widgets.add(floorSlider(cfg, floors[i], contentX, y, colW));
            widgets.add(floorSlider(cfg, floors[i + 7], colBX, y, colW));
            y += 22;
        }

        return widgets;
    }

    private static void cycleQueueFloor(AutoKickConfig cfg, int direction) {
        Floor[] floors = Floor.values();
        // -1 = Last Run, then F1..M7.
        int at = cfg.getQueueFloor() == null ? -1 : cfg.getQueueFloor().ordinal();
        int next = Math.floorMod(at + 1 + direction, floors.length + 1) - 1;
        cfg.setQueueFloor(next < 0 ? null : floors[next]);
        cfg.save();
    }

    private static Component queueFloorText(Floor floor) {
        return Component.literal("Queue Floor: §6" + (floor == null ? "Last Run" : floor.label()));
    }

    private static void cycleMode(AutoKickConfig cfg, int direction) {
        ActionMode[] modes = ActionMode.values();
        int next = Math.floorMod(cfg.getMode().ordinal() + direction, modes.length);
        cfg.setMode(modes[next]);
        cfg.save();
    }

    /** 30 s up to the floor's own top (4 min; F7 6 min, M7 7 min), in 5 s steps. */
    private static AbstractWidget floorSlider(AutoKickConfig cfg, Floor floor, int x, int y, int w) {
        int min = AutoKickConfig.MIN_TARGET_SECONDS;
        int max = floor.maxSeconds();
        double normalized = (cfg.getTargetSeconds(floor) - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, w, 18, floorText(floor, cfg.getTargetSeconds(floor)), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(floorText(floor, cfg.getTargetSeconds(floor)));
            }

            @Override
            protected void applyValue() {
                int secs = (int) (Math.round((min + this.value * (max - min)) / 5.0) * 5);
                cfg.setTargetSeconds(floor, secs);
                cfg.save();
            }
        };
    }

    private static Component floorText(Floor floor, int seconds) {
        return Component.literal(floor.label() + ": §6" + mmSs(seconds));
    }

    private static String mmSs(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static Component modeText(ActionMode mode) {
        return Component.literal("Action Mode: §6" + mode.label());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
