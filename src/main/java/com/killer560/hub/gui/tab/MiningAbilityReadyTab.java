package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Ability Ready Alert (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningAbilityReadyTab extends BaseTab {

    public MiningAbilityReadyTab() {
        super("Ability Ready Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Ability Ready Alert", () -> cfg.isAbilityAlertRaw(), cfg::setAbilityAlert, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        MiningQolRows.toggle(w, contentX, y, contentWidth, "Cooldown Timer", cfg::isAbilityTimer, cfg::setAbilityTimer,
                cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Ready Sound", cfg::isAbilitySound, cfg::setAbilitySound,
                cfg::save);
        return w;
    }
}
