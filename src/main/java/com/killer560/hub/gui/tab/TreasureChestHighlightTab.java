package com.killer560.hub.gui.tab;

import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Treasure Chest Highlight (Mining): see {@code com.killer560.hub.powdermining.TreasureChests}. */
public class TreasureChestHighlightTab extends BaseTab {

    public TreasureChestHighlightTab() {
        super("Treasure Chest Highlight");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Treasure Chest Highlight", cfg::isTreasureChestRaw, cfg::setTreasureChest, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Box Color", cfg::getTreasureChestColor,
                cfg::setTreasureChestColor, PowderMiningConfig.DEFAULT_CHEST_COLOR, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Fade to Red", cfg::isTreasureChestFade,
                cfg::setTreasureChestFade, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Despawn Timer", cfg::isTreasureChestTimer,
                cfg::setTreasureChestTimer, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Countdown Over Chests", cfg::isTreasureChestCountdown,
                cfg::setTreasureChestCountdown, cfg::save);
        y += MiningQolRows.STEP;
        w.add(onOffButton("Tracer", cfg::isTreasureChestTracer, cfg::setTreasureChestTracer, cfg::save, requestRebuild,
                contentX, y, contentWidth, MiningQolRows.ROW));
        y += MiningQolRows.STEP;
        if (cfg.isTreasureChestTracer() || isSearchScan()) {
            MiningQolRows.cycle(w, contentX, y, contentWidth, "Tracer Mode", () -> cfg.getTreasureChestTracerMode().label(),
                    cfg::stepTreasureChestTracerMode, cfg::save);
            y += MiningQolRows.STEP;
        }
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Hide Opened Chests", cfg::isTreasureChestHideOpened,
                cfg::setTreasureChestHideOpened, cfg::save);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += MiningQolRows.STEP + 4;
            MiningQolRows.cheatHeader(w, contentX, y, contentWidth);
            y += 16;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Through Walls", cfg::isTreasureChestEsp,
                    cfg::setTreasureChestEsp, cfg::save);
        }
        return w;
    }
}
