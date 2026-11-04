package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Powder Ghast (Mining): see {@code com.killer560.hub.miningqol.PowderGhast}. */
public class MiningPowderGhastTab extends BaseTab {

    public MiningPowderGhastTab() {
        super("Powder Ghast");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Powder Ghast", () -> cfg.isPowderGhastRaw(), cfg::setPowderGhast, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;

        y = MiningQolRows.toggles(w, contentX, y, contentWidth, cfg::save,
                MiningQolRows.t("Spawn Title", cfg::isPowderGhastTitle, cfg::setPowderGhastTitle),
                MiningQolRows.t("Spawn Sound", cfg::isPowderGhastSound, cfg::setPowderGhastSound),
                MiningQolRows.t("Party Callout", cfg::isPowderGhastParty, cfg::setPowderGhastParty),
                MiningQolRows.t("Despawn Timer", cfg::isPowderGhastTimer, cfg::setPowderGhastTimer),
                MiningQolRows.t("Highlight Box", cfg::isPowderGhastBox, cfg::setPowderGhastBox));
        MiningQolRows.color(w, contentX, y, contentWidth, "Box Color", cfg::getPowderGhastBoxColor,
                cfg::setPowderGhastBoxColor, MiningQolConfig.DEFAULT_POWDER_GHAST_COLOR, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Tracer", cfg::isPowderGhastTracer,
                cfg::setPowderGhastTracer, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Tracer Color", cfg::getPowderGhastTracerColor,
                cfg::setPowderGhastTracerColor, MiningQolConfig.DEFAULT_POWDER_GHAST_COLOR, cfg::save);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += MiningQolRows.STEP + 4;
            MiningQolRows.cheatHeader(w, contentX, y, contentWidth);
            y += 16;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Through Walls", cfg::isPowderGhastEsp,
                    cfg::setPowderGhastEsp, cfg::save);
        }
        return w;
    }
}
