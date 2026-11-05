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
        w.add(steppedSlider(contentX, y, contentWidth, 20, cpsLabel(cfg), PowderMiningConfig.MIN_CHEST_CPS,
                PowderMiningConfig.MAX_CHEST_CPS, cfg.getChestTriggerbotCps(), 1, v -> cfg.setChestTriggerbotCps((int) v),
                () -> cpsLabel(cfg), cfg::save));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Swap To Best Gemstone Drill", cfg::isChestTriggerbotSwap,
                cfg::setChestTriggerbotSwap, cfg::save);
        return w;
    }

    private static net.minecraft.network.chat.Component cpsLabel(PowderMiningConfig cfg) {
        return net.minecraft.network.chat.Component.literal("Triggerbot CPS: " + cfg.getChestTriggerbotCps());
    }
}
