package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.CarnivalTrackerConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Carnival Tracker settings - see {@code carnival.CarnivalTracker}. */
public class CarnivalTrackerTab extends BaseTab {

    public CarnivalTrackerTab() {
        super("Carnival Tracker");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        CarnivalTrackerConfig cfg = CarnivalTrackerConfig.getInstance();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Carnival Tracker", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 4) / 2;
        int colB = contentX + half + 4;
        IslandRows.toggle(w, contentX, y, half, "Current Game", cfg::isShowGame, cfg::setShowGame, save);
        y = IslandRows.toggle(w, colB, y, half, "Session", cfg::isShowSession, cfg::setShowSession, save);
        IslandRows.toggle(w, contentX, y, half, "Best Scores", cfg::isShowBest, cfg::setShowBest, save);
        y = IslandRows.toggle(w, colB, y, half, "Goals Left", cfg::isShowGoals, cfg::setShowGoals, save);
        IslandRows.toggle(w, contentX, y, half, "Ticket Reminder", cfg::isTicketReminder, cfg::setTicketReminder, save);
        return w;
    }
}
