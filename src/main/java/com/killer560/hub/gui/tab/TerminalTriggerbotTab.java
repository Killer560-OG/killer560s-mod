package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.terminaltrigger.TerminalTriggerbotConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

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

        if (masterClosed(widgets, "Terminal Triggerbot", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 24;

        widgets.add(valueSlider(contentX, y, halfW, "Delay", "ms", 0, TerminalTriggerbotConfig.MAX_DELAY_MS, 25, 0,
                cfg::getDelayMs, v -> cfg.setDelayMs((int) v), cfg::save));
        widgets.add(valueSlider(contentX + halfW + GAP, y, rightW, "Cooldown", "ms", 0, TerminalTriggerbotConfig.MAX_COOLDOWN_MS,
                50, 0, cfg::getCooldownMs, v -> cfg.setCooldownMs((int) v), cfg::save));
        y += ROW + GAP;

        widgets.add(valueSlider(contentX, y, halfW, "Range", "", 1.0, TerminalTriggerbotConfig.MAX_RANGE, 0.5, 1,
                cfg::getRange, cfg::setRange, cfg::save));
        y += ROW + GAP;

        return widgets;
    }



}
