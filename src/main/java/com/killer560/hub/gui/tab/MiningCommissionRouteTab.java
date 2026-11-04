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
        MiningQolRows.color(w, contentX, y, contentWidth, "Route Color", cfg::getCommissionRouteColor,
                cfg::setCommissionRouteColor, MiningQolConfig.DEFAULT_COMMISSION_ROUTE_COLOR, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Route Mode", () -> cfg.getCommissionRouteMode().label(),
                cfg::stepCommissionRouteMode, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Route To Emissary", cfg::isCommissionRouteEmissary,
                cfg::setCommissionRouteEmissary, cfg::save);
        return w;
    }
}
