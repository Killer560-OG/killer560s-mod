package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.scanner.StructureScannerConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Structure Scanner (cheat build only; reads blocks, never acts): see {@code mining.scanner.StructureScannerFeature}.
 *  Under the Crystal Hollows Map, in the Crystal Hollows group's cheat block (killer560, 2026-11-04). */
public class StructureScannerTab extends BaseTab {

    public StructureScannerTab() {
        super("Structure Scanner");
    }

    /** Only added behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}: the red cheat block. */
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
        StructureScannerConfig cfg = StructureScannerConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;
        MiningRows.master(w, contentX, y, contentWidth, "Structure Scanner", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild);
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        MiningRows.header(w, contentX, y, contentWidth, "Cheat", true);
        MiningRows.toggle(w, contentX, y[0], half, "Structure Waypoints", cfg::isWaypoints, cfg::setWaypoints, cfg::save);
        MiningRows.toggle(w, colB, y[0], half, "Chat On Find", cfg::isChatOnFind, cfg::setChatOnFind, cfg::save);
        y[0] += 22;
        MiningRows.toggle(w, contentX, y[0], half, "Show On Map", cfg::isShowOnMap, cfg::setShowOnMap, cfg::save);
        MiningRows.toggle(w, colB, y[0], half, "Fairy Grottos", cfg::isGrottos, cfg::setGrottos, cfg::save);
        return w;
    }
}
