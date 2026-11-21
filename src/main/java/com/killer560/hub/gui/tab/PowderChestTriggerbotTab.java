package com.killer560.hub.gui.tab;

import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Powder Chest Triggerbot (Mining, cheat-only): see {@code com.killer560.hub.powdermining.PowderChestTriggerbot}. */
public class PowderChestTriggerbotTab extends BaseTab {

    public PowderChestTriggerbotTab() {
        super("Powder Chest Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Powder Chest Triggerbot", cfg::isChestTriggerbotRaw, cfg::setChestTriggerbot, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        if (!cfg.isChestTriggerbotRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 8) / 2;
        w.add(msRange(contentX, y, contentWidth, "Delay", cfg.getChestTriggerbotMinDelayMs(),
                cfg.getChestTriggerbotMaxDelayMs(), cfg::setChestTriggerbotDelayRange, cfg::save));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Swap To Best Gemstone Drill", cfg::isChestTriggerbotSwap,
                cfg::setChestTriggerbotSwap, cfg::save);
        if (cfg.isChestTriggerbotSwap()) {
            MiningQolRows.toggle(w, contentX, y + MiningQolRows.STEP, contentWidth, "Swap Back When Mining",
                    cfg::isChestTriggerbotSwapBack, cfg::setChestTriggerbotSwapBack, cfg::save);
            MiningQolRows.toggle(w, contentX, y + 2 * MiningQolRows.STEP, contentWidth, "Don't Swap While Mining",
                    cfg::isChestTriggerbotNoSwapMining, cfg::setChestTriggerbotNoSwapMining, cfg::save);
        }
        return w;
    }
}
