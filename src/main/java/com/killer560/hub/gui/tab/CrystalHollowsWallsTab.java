package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.CrystalHollowsWalls;
import com.killer560.hub.miningqol.CrystalHollowsWalls.Zone;
import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Crystal Hollows Walls (Mining, legit): see {@code com.killer560.hub.miningqol.CrystalHollowsWalls}. */
public class CrystalHollowsWallsTab extends BaseTab {

    public CrystalHollowsWallsTab() {
        super("Crystal Hollows Walls");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Crystal Hollows Walls", cfg::isChWallsRaw, cfg::setChWalls, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;
        if (!cfg.isChWallsRaw() && !isSearchScan()) {
            return w;
        }
        w.add(onOffButton("Hide Walls In Structures", cfg::isChWallsHideInStructures, cfg::setChWallsHideInStructures, cfg::save,
                null, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        for (Zone z : Zone.values()) {
            MiningQolRows.color(w, contentX, y, contentWidth, z.label + " Color", () -> cfg.getChWallColor(z),
                    argb -> cfg.setChWallColor(z, argb), 0xFF000000 | z.defaultColor, cfg::save);
            y += MiningQolRows.STEP;
            MiningQolRows.cycle(w, contentX, y, contentWidth, z.label + " Opacity", () -> cfg.getChWallOpacity(z) + "%",
                    dir -> {
                        int v = cfg.getChWallOpacity(z) + 5 * (dir >= 0 ? 1 : -1);
                        if (v > 100) {
                            v = CrystalHollowsWalls.MIN_OPACITY;
                        } else if (v < CrystalHollowsWalls.MIN_OPACITY) {
                            v = 100;
                        }
                        cfg.setChWallOpacity(z, v);
                    }, cfg::save);
            y += MiningQolRows.STEP;
            MiningQolRows.cycle(w, contentX, y, contentWidth, z.label + " Near Only",
                    () -> cfg.getChWallNear(z) == 0 ? "Always" : cfg.getChWallNear(z) + " blocks",
                    dir -> {
                        int[] s = CrystalHollowsWalls.NEAR_STEPS;
                        int at = 0;
                        for (int i = 0; i < s.length; i++) {
                            if (s[i] <= cfg.getChWallNear(z)) {
                                at = i;
                            }
                        }
                        cfg.setChWallNear(z, s[Math.floorMod(at + (dir >= 0 ? 1 : -1), s.length)]);
                    }, cfg::save);
            y += MiningQolRows.STEP;
        }
        return w;
    }
}
