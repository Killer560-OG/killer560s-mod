package com.killer560.hub.gui.tab;

import com.killer560.hub.autokick.AutoKickConfig;
import com.killer560.hub.autokick.AutoKickConfig.ActionMode;
import com.killer560.hub.autokick.AutoKickConfig.Floor;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Kick - killer560's own request: "create auto kick. I really Like Odins. The kick based off of
 * timed comp of a floor and whatnot." See {@code autokick.AutoKickFeature} for the logic and
 * {@code autokick.AutoKickConfig} for why every per-floor target time below ships at 0 (disabled) rather
 * than a guessed default.
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
            widgets.add(note(contentX, y, contentWidth,
                    "Kicks (or warns) once a floor's target time is missed - off until every per-floor target below is set."));
            return widgets;
        }

        widgets.add(note(contentX, y, contentWidth,
                "This can remove real party members automatically. Warn Only never sends a command."));
        y += 16;

        // ---------------------------------------------------------------- Action
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Action", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(modeText(cfg.getMode()), btn -> {
                    cycleMode(cfg, 1);
                    btn.setMessage(modeText(cfg.getMode()));
                    requestRebuild.run();
                }).secondaryPress(btn -> {
                    cycleMode(cfg, -1);
                    btn.setMessage(modeText(cfg.getMode()));
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.getMode() == ActionMode.KICK_SPECIFIC) {
            EditBox members = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                    Component.literal("Specific Members"));
            members.setMaxLength(256);
            members.setHint(Component.literal("Comma-separated IGNs"));
            members.setValue(cfg.getSpecificMembers());
            members.setResponder(text -> {
                cfg.setSpecificMembers(text);
                cfg.save();
            });
            widgets.add(members);
            y += 22;
        }
        y += 4;

        // ---------------------------------------------------------------- Per-floor target times
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Per-Floor Target Times", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(note(contentX, y, contentWidth, "0 = disabled. Ships unset - see the tooltip for why."));
        y += 16;

        Floor[] floors = Floor.values();
        // First 7 = F1-F7, next 7 = M1-M7 (see Floor's own declaration order) - left/right columns.
        for (int i = 0; i < 7; i++) {
            widgets.add(floorSlider(cfg, floors[i], contentX, y, colW));
            widgets.add(floorSlider(cfg, floors[i + 7], colBX, y, colW));
            y += 22;
        }

        return widgets;
    }

    private static void cycleMode(AutoKickConfig cfg, int direction) {
        ActionMode[] modes = ActionMode.values();
        int next = Math.floorMod(cfg.getMode().ordinal() + direction, modes.length);
        cfg.setMode(modes[next]);
        cfg.save();
    }

    private static AbstractWidget floorSlider(AutoKickConfig cfg, Floor floor, int x, int y, int w) {
        int seconds = cfg.getTargetSeconds(floor);
        double normalized = seconds / (double) AutoKickConfig.MAX_TARGET_SECONDS;
        return new ThemedSliderButton(x, y, w, 18, floorText(floor, seconds), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(floorText(floor, cfg.getTargetSeconds(floor)));
            }

            @Override
            protected void applyValue() {
                // Snapped to the nearest 5s - fine granularity for a target time without needing exact typing.
                int secs = (int) (Math.round(this.value * AutoKickConfig.MAX_TARGET_SECONDS / 5.0) * 5);
                cfg.setTargetSeconds(floor, secs);
                cfg.save();
            }
        };
    }

    private static Component floorText(Floor floor, int seconds) {
        return Component.literal(floor.label() + ": " + (seconds <= 0 ? "§7Disabled" : "§6" + mmSs(seconds)));
    }

    private static String mmSs(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static Component modeText(ActionMode mode) {
        return Component.literal("Action Mode: §6" + mode.label());
    }

    private static StringWidget note(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal("§7" + text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
