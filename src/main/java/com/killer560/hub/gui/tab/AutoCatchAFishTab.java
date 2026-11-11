package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.AutoCatchAFishConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Catch a Fish settings (cheat build only) - see {@code carnival.AutoCatchAFish}. */
public class AutoCatchAFishTab extends BaseTab {

    public AutoCatchAFishTab() {
        super("Auto Catch a Fish");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AutoCatchAFishConfig cfg = AutoCatchAFishConfig.getInstance();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Auto Catch a Fish", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        y = IslandRows.automationNote(w, contentX, y, contentWidth);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 4) / 2;
        int colB = contentX + half + 4;
        IslandRows.slider(w, contentX, y, half, AutoCatchAFishConfig.MIN_DELAY_MS, AutoCatchAFishConfig.MAX_DELAY_MS, 10,
                cfg::getCastDelayMs, cfg::setCastDelayMs, v -> "Cast Delay: " + v + "ms", save);
        y = IslandRows.slider(w, colB, y, half, AutoCatchAFishConfig.MIN_WAIT_S, AutoCatchAFishConfig.MAX_WAIT_S, 1,
                cfg::getHookWaitS, cfg::setHookWaitS, v -> "Hook Wait: " + v + "s", save);
        IslandRows.toggle(w, contentX, y, half, "Free Camera", cfg::isFreeCamera, cfg::setFreeCamera, save);
        return w;
    }
}
