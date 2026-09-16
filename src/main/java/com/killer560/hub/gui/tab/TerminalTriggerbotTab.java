package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.terminaltrigger.TerminalTriggerbotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminal Triggerbot settings - cheat build only, so the whole tab only exists on the cheat jar and its
 *  headers are drawn red. See {@code TerminalTriggerbotFeature}. */
public class TerminalTriggerbotTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;

    public TerminalTriggerbotTab() {
        super("Terminal Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        TerminalTriggerbotConfig cfg = TerminalTriggerbotConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int halfW = (contentWidth - GAP) / 2;
        int rightW = Math.max(1, contentWidth - halfW - GAP);

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Terminal Triggerbot", true), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Triggerbot", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Opens the P3 terminal you are looking at. Never moves your camera."), font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(delayText(cfg), btn -> {
                    int next = cfg.getDelayMs() + 25;
                    cfg.setDelayMs(next > TerminalTriggerbotConfig.MAX_DELAY_MS ? 0 : next);
                    cfg.save();
                    btn.setMessage(delayText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(SettingsButtonWidget.builder(cooldownText(cfg), btn -> {
                    int next = cfg.getCooldownMs() + 50;
                    cfg.setCooldownMs(next > TerminalTriggerbotConfig.MAX_COOLDOWN_MS ? 0 : next);
                    cfg.save();
                    btn.setMessage(cooldownText(cfg));
                }).bounds(contentX + halfW + GAP, y, rightW, ROW).build());
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.builder(rangeText(cfg), btn -> {
                    double next = cfg.getRange() + 0.5;
                    cfg.setRange(next > TerminalTriggerbotConfig.MAX_RANGE ? 1.0 : next);
                    cfg.save();
                    btn.setMessage(rangeText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        y += ROW + GAP;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Only clicks what your crosshair is already on - Terminal Aura is the in-range version."),
                font));
        return widgets;
    }

    private static Component delayText(TerminalTriggerbotConfig cfg) {
        return Component.literal("Delay: " + cfg.getDelayMs() + "ms");
    }

    private static Component cooldownText(TerminalTriggerbotConfig cfg) {
        return Component.literal("Cooldown: " + cfg.getCooldownMs() + "ms");
    }

    private static Component rangeText(TerminalTriggerbotConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.1f", cfg.getRange()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
