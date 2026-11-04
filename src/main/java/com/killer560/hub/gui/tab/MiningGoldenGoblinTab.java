package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Golden Goblin (Mining): see {@code com.killer560.hub.miningqol.GoldenGoblin}. */
public class MiningGoldenGoblinTab extends BaseTab {

    public MiningGoldenGoblinTab() {
        super("Golden Goblin");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Golden Goblin", () -> cfg.isGoldenGoblinRaw(), cfg::setGoldenGoblin, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Highlight Box", cfg::isGoldenGoblinBox,
                cfg::setGoldenGoblinBox, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Tracer", cfg::isGoldenGoblinTracer,
                cfg::setGoldenGoblinTracer, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Golden Color", cfg::getGoldenGoblinColor,
                cfg::setGoldenGoblinColor, MiningQolConfig.DEFAULT_GOLDEN_GOBLIN_COLOR, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Diamond Color", cfg::getDiamondGoblinColor,
                cfg::setDiamondGoblinColor, MiningQolConfig.DEFAULT_DIAMOND_GOBLIN_COLOR, cfg::save);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += MiningQolRows.STEP + 4;
            MiningQolRows.cheatHeader(w, contentX, y, contentWidth);
            y += 16;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Through Walls", cfg::isGoldenGoblinEsp,
                    cfg::setGoldenGoblinEsp, cfg::save);
        }
        return w;
    }
}
