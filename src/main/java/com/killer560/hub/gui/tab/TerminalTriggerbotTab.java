package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.terminaltrigger.TerminalTriggerbotConfig;
import net.minecraft.client.gui.components.AbstractWidget;
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
        int y = contentY;
        int halfW = (contentWidth - GAP) / 2;
        int rightW = Math.max(1, contentWidth - halfW - GAP);

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Terminal Triggerbot", true));
        y += 16;

        widgets.add(onOffButton("Terminal Triggerbot", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(delayText(cfg), (btn, dir) -> {
                    cfg.setDelayMs((int) SettingsButtonWidget.step(cfg.getDelayMs(), 25, 0,
                            TerminalTriggerbotConfig.MAX_DELAY_MS, dir));
                    cfg.save();
                    btn.setMessage(delayText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(SettingsButtonWidget.cycling(cooldownText(cfg), (btn, dir) -> {
                    cfg.setCooldownMs((int) SettingsButtonWidget.step(cfg.getCooldownMs(), 50, 0,
                            TerminalTriggerbotConfig.MAX_COOLDOWN_MS, dir));
                    cfg.save();
                    btn.setMessage(cooldownText(cfg));
                }).bounds(contentX + halfW + GAP, y, rightW, ROW).build());
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.cycling(rangeText(cfg), (btn, dir) -> {
                    cfg.setRange(SettingsButtonWidget.step(cfg.getRange(), 0.5, 1.0, TerminalTriggerbotConfig.MAX_RANGE, dir));
                    cfg.save();
                    btn.setMessage(rangeText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        y += ROW + GAP;

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
}
