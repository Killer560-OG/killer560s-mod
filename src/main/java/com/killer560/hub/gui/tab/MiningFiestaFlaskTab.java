package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Fiesta Flask Timer (Mining): see {@code com.killer560.hub.miningqol.FiestaFlask}. */
public class MiningFiestaFlaskTab extends BaseTab {

    public MiningFiestaFlaskTab() {
        super("Fiesta Flask Timer");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Fiesta Flask Timer", cfg::isFiestaFlaskRaw, cfg::setFiestaFlask, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        if (!cfg.isFiestaFlaskRaw() && !isSearchScan()) return w;
        y += MiningQolRows.STEP;
        y = MiningQolRows.toggles(w, contentX, y, contentWidth, cfg::save,
                MiningQolRows.t("Alert When Expired", cfg::isFiestaFlaskAlert, cfg::setFiestaFlaskAlert));
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Warn Before Expiry",
                () -> cfg.getFiestaFlaskWarn() == 0 ? "Off" : cfg.getFiestaFlaskWarn() + " s",
                cfg::stepFiestaFlaskWarn, cfg::save);
        return w;
    }
}
