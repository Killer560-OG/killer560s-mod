package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import com.killer560.hub.miningqol.PigeonDrillSwap;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Swap To Drill On Pigeon (Mining, cheat-only): see {@link PigeonDrillSwap}. */
public class MiningPigeonDrillTab extends BaseTab {

    public MiningPigeonDrillTab() {
        super("Swap To Drill On Pigeon");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Swap To Drill On Pigeon", cfg::isPigeonDrillRaw, cfg::setPigeonDrill, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Drill Choice", () -> cfg.getPigeonDrillMode().label(),
                cfg::stepPigeonDrillMode, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Specific Drill",
                () -> cfg.getPigeonDrillName().isEmpty() ? "none" : cfg.getPigeonDrillName(), dir -> {
                    List<String> names = PigeonDrillSwap.hotbarDrillNames();
                    if (names.isEmpty()) {
                        return;
                    }
                    int at = names.indexOf(cfg.getPigeonDrillName());
                    cfg.setPigeonDrillName(names.get(Math.floorMod(at + dir, names.size())));
                }, cfg::save);
        return w;
    }
}
