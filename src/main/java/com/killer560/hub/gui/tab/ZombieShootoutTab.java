package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.ZombieShootoutConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Zombie Shootout Helper settings - see {@code carnival.ZombieShootout}. */
public class ZombieShootoutTab extends BaseTab {

    public ZombieShootoutTab() {
        super("Zombie Shootout Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ZombieShootoutConfig cfg = ZombieShootoutConfig.getInstance();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Zombie Shootout Helper", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 4) / 2;
        int colB = contentX + half + 4;
        IslandRows.toggle(w, contentX, y, half, "Zombie Outlines", cfg::isOutlines, cfg::setOutlines, save);
        y = IslandRows.toggle(w, colB, y, half, "Highest Only", cfg::isHighestOnly, cfg::setHighestOnly, save);
        IslandRows.toggle(w, contentX, y, half, "Zombie Timers", cfg::isTimers, cfg::setTimers, save);
        y = IslandRows.toggle(w, colB, y, half, "Best Target", cfg::isBest, cfg::setBest, save);
        IslandRows.toggle(w, contentX, y, half, "Lit Lamps", cfg::isLamps, cfg::setLamps, save);
        y = IslandRows.toggle(w, colB, y, half, "Lamp Line", cfg::isLampLine, cfg::setLampLine, save);
        IslandRows.toggle(w, contentX, y, half, "Shootout HUD", cfg::isHud, cfg::setHud, save);
        y = IslandRows.color(w, colB, y, half, "Best Target Color", cfg::getBestColor, cfg::setBestColor,
                ZombieShootoutConfig.DEFAULT_BEST_COLOR, save);
        return w;
    }
}
