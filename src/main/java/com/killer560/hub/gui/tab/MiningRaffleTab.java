package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Raffle Ticket Highlight (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningRaffleTab extends BaseTab {

    public MiningRaffleTab() {
        super("Raffle Ticket Highlight");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Raffle Ticket Highlight", () -> cfg.isRaffleRaw(), cfg::setRaffle, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        MiningQolRows.color(w, contentX, y, contentWidth, "Ticket Color", cfg::getRaffleColor, cfg::setRaffleColor,
                MiningQolConfig.DEFAULT_RAFFLE_COLOR, cfg::save);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += MiningQolRows.STEP + 4;
            MiningQolRows.cheatHeader(w, contentX, y, contentWidth);
            y += 16;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Through Walls", cfg::isRaffleEsp, cfg::setRaffleEsp,
                    cfg::save);
        }
        return w;
    }
}
