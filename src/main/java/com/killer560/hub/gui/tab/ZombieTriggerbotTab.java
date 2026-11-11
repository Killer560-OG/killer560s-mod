package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.ShootoutShotConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Zombie Shootout Triggerbot settings (cheat build only) - see {@code carnival.ZombieTriggerbot}. */
public class ZombieTriggerbotTab extends BaseTab {

    public ZombieTriggerbotTab() {
        super("Zombie Shootout Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ShootoutShotConfig cfg = ShootoutShotConfig.triggerbot();
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Zombie Shootout Triggerbot", cfg::isEnabledRaw,
                cfg::setEnabled, cfg::save, requestRebuild);
        y = IslandRows.automationNote(w, contentX, y, contentWidth);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        ShootoutRows.rows(w, contentX, y, contentWidth, cfg);
        return w;
    }
}
