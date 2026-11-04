package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Commission HUD (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningCommissionHudTab extends BaseTab {

    public MiningCommissionHudTab() {
        super("Commission HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Commission HUD", () -> cfg.isCommissionHudRaw(), cfg::setCommissionHud, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        y = MiningQolRows.toggles(w, contentX, y, contentWidth, cfg::save,
                MiningQolRows.t("Hide While Powder Mining", cfg::isHideCommissionsPowder, cfg::setHideCommissionsPowder),
                MiningQolRows.t("Color By Progress", cfg::isCommissionColorProgress, cfg::setCommissionColorProgress),
                MiningQolRows.t("Live Progress", cfg::isCommissionInterpolate, cfg::setCommissionInterpolate),
                MiningQolRows.t("Commission Waypoints", cfg::isCommissionWaypoints, cfg::setCommissionWaypoints),
                MiningQolRows.t("Emissary Waypoints", cfg::isCommissionEmissaries, cfg::setCommissionEmissaries),
                MiningQolRows.t("Completed Commissions", cfg::isCommissionMenuHighlight, cfg::setCommissionMenuHighlight),
                MiningQolRows.t("Commission Mobs", cfg::isCommissionMobs, cfg::setCommissionMobs));
        MiningQolRows.color(w, contentX, y, contentWidth, "Commission Mob Color", cfg::getCommissionMobColor,
                cfg::setCommissionMobColor, MiningQolConfig.DEFAULT_COMMISSION_MOB_COLOR, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Commission Mob Style", () -> cfg.getCommissionMobStyle().label(),
                cfg::stepCommissionMobStyle, cfg::save);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += MiningQolRows.STEP + 4;
            MiningQolRows.cheatHeader(w, contentX, y, contentWidth);
            y += 16;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Commission Mobs Through Walls", cfg::isCommissionMobsEsp,
                    cfg::setCommissionMobsEsp, cfg::save);
        }
        return w;
    }
}
