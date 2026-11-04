package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.ghosts.GhostEspConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Ghost ESP (cheat build only; draws, never acts): see {@link com.killer560.hub.mining.ghosts.GhostEspFeature}. */
public class GhostEspTab extends BaseTab {

    public GhostEspTab() {
        super("Ghost ESP");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        GhostEspConfig cfg = GhostEspConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;
        MiningRows.master(w, contentX, y, contentWidth, "Ghost ESP", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild);
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        MiningRows.header(w, contentX, y, contentWidth, "Cheat", true);
        MiningRows.toggle(w, contentX, y[0], half, "Ghosts Through Walls", cfg::isThroughWalls, cfg::setThroughWalls,
                cfg::save);
        MiningRows.color(w, colB, y[0], half, "Ghost ESP", "Ghost Color", cfg::getColor, cfg::setColor,
                GhostEspConfig.DEFAULT_COLOR, cfg::save);
        y[0] += 22;
        return w;
    }
}
