package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.metaldetector.MetalDetectorConfig;
import com.killer560.hub.mining.metaldetector.MetalDetectorFeature;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Metal Detector (legit): the Mines of Divan treasure solver's settings. See {@link MetalDetectorFeature}. */
public class MetalDetectorTab extends BaseTab {

    public MetalDetectorTab() {
        super("Metal Detector");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MetalDetectorConfig cfg = MetalDetectorConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;
        MiningRows.master(w, contentX, y, contentWidth, "Metal Detector", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild);
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        MiningRows.header(w, contentX, y, contentWidth, "Display", false);
        MiningRows.toggle(w, contentX, y[0], half, "Line To Treasure", cfg::isShowLine, cfg::setShowLine, cfg::save);
        MiningRows.toggle(w, colB, y[0], half, "Show Possible Spots", cfg::isShowCandidates, cfg::setShowCandidates,
                cfg::save);
        y[0] += 22;
        MiningRows.toggle(w, contentX, y[0], half, "Chat Updates", cfg::isChatUpdates, cfg::setChatUpdates, cfg::save);
        y[0] += 22;
        MiningRows.color(w, contentX, y[0], half, "Metal Detector", "Treasure Color", cfg::getColor, cfg::setColor,
                MetalDetectorConfig.DEFAULT_COLOR, cfg::save);
        MiningRows.color(w, colB, y[0], half, "Metal Detector", "Spot Color", cfg::getCandidateColor,
                cfg::setCandidateColor, MetalDetectorConfig.DEFAULT_CANDIDATE_COLOR, cfg::save);
        y[0] += 24;
        MiningRows.header(w, contentX, y, contentWidth, "Now", false);
        MiningRows.note(w, contentX, y, contentWidth, MetalDetectorFeature.status());
        return w;
    }
}
