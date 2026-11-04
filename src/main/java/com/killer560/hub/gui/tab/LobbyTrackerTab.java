package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.lobby.LobbyTrackerConfig;
import com.killer560.hub.mining.lobby.LobbyTrackerFeature;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Lobby Tracker (legit): see {@link LobbyTrackerFeature}. */
public class LobbyTrackerTab extends BaseTab {

    public LobbyTrackerTab() {
        super("Lobby Tracker");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        LobbyTrackerConfig cfg = LobbyTrackerConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;
        MiningRows.master(w, contentX, y, contentWidth, "Lobby Tracker", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild);
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        MiningRows.header(w, contentX, y, contentWidth, "Display", false);
        MiningRows.toggle(w, contentX, y[0], half, "Seen Before Message", cfg::isChatOnJoin, cfg::setChatOnJoin,
                cfg::save);
        MiningRows.toggle(w, colB, y[0], half, "Lobby HUD", cfg::isHud, cfg::setHud, cfg::save);
        y[0] += 22;
        MiningRows.toggle(w, contentX, y[0], half, "Show World Day", cfg::isShowWorldDay, cfg::setShowWorldDay,
                cfg::save);
        MiningRows.button(w, colB, y[0], half, "Forget Lobbies", () -> {
            LobbyTrackerFeature.clearHistory();
            requestRebuild.run();
        });
        y[0] += 24;
        MiningRows.note(w, contentX, y, contentWidth, LobbyTrackerFeature.status());
        return w;
    }
}
