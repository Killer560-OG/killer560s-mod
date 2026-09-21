package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.terminalaura.TerminalAuraConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminal Aura settings - cheat build only, so the whole tab only exists on the cheat jar and its
 *  headers are drawn red. See {@code TerminalAuraFeature} for what it actually does. */
public class TerminalAuraTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;

    public TerminalAuraTab() {
        super("Terminal Aura");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        TerminalAuraConfig cfg = TerminalAuraConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int halfW = (contentWidth - GAP) / 2;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Terminal Aura", true), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Aura", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(rangeText(cfg), btn -> {
                    double next = cfg.getRange() + 0.5;
                    cfg.setRange(next > TerminalAuraConfig.MAX_RANGE ? 1.0 : next);
                    cfg.save();
                    btn.setMessage(rangeText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(SettingsButtonWidget.builder(delayText(cfg), btn -> {
                    int next = cfg.getDelayMs() + 50;
                    cfg.setDelayMs(next > TerminalAuraConfig.MAX_DELAY_MS ? 0 : next);
                    cfg.save();
                    btn.setMessage(delayText(cfg));
                }).bounds(contentX + halfW + GAP, y, Math.max(1, contentWidth - halfW - GAP), ROW).build());
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.builder(onOff("Ground Only", cfg.isGroundOnly()), btn -> {
                    cfg.setGroundOnly(!cfg.isGroundOnly());
                    cfg.save();
                    btn.setMessage(onOff("Ground Only", cfg.isGroundOnly()));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Leap Delay", cfg.isLeapDelayEnabled()), btn -> {
                    cfg.setLeapDelayEnabled(!cfg.isLeapDelayEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + halfW + GAP, y, Math.max(1, contentWidth - halfW - GAP), ROW).build());
        y += ROW + GAP;

        if (cfg.isLeapDelayEnabled()) {
            widgets.add(SettingsButtonWidget.builder(leapDelayText(cfg), btn -> {
                        double next = cfg.getLeapDelaySeconds() + 0.1;
                        cfg.setLeapDelaySeconds(next > 5.0 ? 0.1 : next);
                        cfg.save();
                        btn.setMessage(leapDelayText(cfg));
                    }).bounds(contentX, y, halfW, ROW).build());
            y += ROW + GAP;
        }

        return widgets;
    }

    private static Component rangeText(TerminalAuraConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.1f", cfg.getRange()));
    }

    private static Component delayText(TerminalAuraConfig cfg) {
        return Component.literal("Delay: " + cfg.getDelayMs() + "ms");
    }

    private static Component leapDelayText(TerminalAuraConfig cfg) {
        return Component.literal(String.format(Locale.US, "Leap Delay Time: %.1fs", cfg.getLeapDelaySeconds()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
