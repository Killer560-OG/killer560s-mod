package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mining.events.MiningEvent;
import com.killer560.hub.mining.events.MiningEventsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Mining Events (legit): see {@link com.killer560.hub.mining.events.MiningEventsFeature}. */
public class MiningEventsTab extends BaseTab {

    public MiningEventsTab() {
        super("Mining Events");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningEventsConfig cfg = MiningEventsConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;
        MiningRows.master(w, contentX, y, contentWidth, "Mining Events", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild);
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        MiningRows.header(w, contentX, y, contentWidth, "Alerts", false);
        MiningRows.toggle(w, contentX, y[0], half, "Event Title", cfg::isStartTitle, cfg::setStartTitle, cfg::save);
        MiningRows.toggle(w, colB, y[0], half, "Event Sound", cfg::isStartSound, cfg::setStartSound, cfg::save);
        y[0] += 22;
        MiningRows.cycle(w, contentX, y[0], half, "Event Sound Choice", () -> cfg.getSound().label,
                dir -> cfg.setSound(SettingsButtonWidget.step(cfg.getSound(), dir)), cfg::save);
        MiningRows.toggle(w, colB, y[0], half, "Ending Soon", cfg::isEndingSoon, cfg::setEndingSoon, cfg::save);
        y[0] += 22;
        MiningRows.toggle(w, contentX, y[0], contentWidth, "Event Timer HUD", cfg::isTimerHud, cfg::setTimerHud,
                cfg::save);
        y[0] += 22;
        MiningRows.header(w, contentX, y, contentWidth, "Events", false);
        MiningEvent[] all = MiningEvent.values();
        for (int i = 0; i < all.length; i++) {
            MiningEvent e = all[i];
            MiningRows.toggle(w, i % 2 == 0 ? contentX : colB, y[0], half, e.label, () -> cfg.isAlerting(e),
                    on -> cfg.setAlerting(e, on), cfg::save);
            if (i % 2 == 1 || i == all.length - 1) {
                y[0] += 22;
            }
        }
        return w;
    }
}
