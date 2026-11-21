package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Commission Route (Mining): see {@code com.killer560.hub.miningqol.CommissionRoute}. Its own switch, apart from the Commission HUD. */
public class MiningCommissionRouteTab extends BaseTab {

    public MiningCommissionRouteTab() {
        super("Commission Route");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Commission Route", () -> cfg.isCommissionRouteRaw(), cfg::setCommissionRoute, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        if (cfg.isCommissionRouteRaw() || isSearchScan()) {
            MiningQolRows.color(w, contentX, y, contentWidth, "Route Color", cfg::getCommissionRouteColor,
                    cfg::setCommissionRouteColor, MiningQolConfig.DEFAULT_COMMISSION_ROUTE_COLOR, cfg::save);
            y += MiningQolRows.STEP;
            MiningQolRows.cycle(w, contentX, y, contentWidth, "Route Mode", () -> cfg.getCommissionRouteMode().label(),
                    cfg::stepCommissionRouteMode, cfg::save);
            y += MiningQolRows.STEP;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Route To Emissary", cfg::isCommissionRouteEmissary,
                    cfg::setCommissionRouteEmissary, cfg::save);
            y += MiningQolRows.STEP;
            MiningQolRows.toggle(w, contentX, y, contentWidth, "Auto Route To Commission", cfg::isCommissionAutoRoute,
                    cfg::setCommissionAutoRoute, cfg::save);
            y += MiningQolRows.STEP;
        }
        // Runs on its own flag (CommissionClaim.pigeonPulse), so it stays while the route is off.
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Highlight Royal Pigeon", cfg::isCommissionPigeon,
                cfg::setCommissionPigeon, cfg::save);
        return w;
    }

    /** Each feature here has its own switch, so the card is ON while either is (menufix-1102 rule). */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Commission Route", "Highlight Royal Pigeon");
    }
}
