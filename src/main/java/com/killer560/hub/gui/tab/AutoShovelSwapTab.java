package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.AutoFruitConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Shovel Swap settings (cheat build only) - see {@code carnival.AutoShovelSwap}. */
public class AutoShovelSwapTab extends BaseTab {

    public AutoShovelSwapTab() {
        super("Auto Shovel Swap");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AutoFruitConfig cfg = AutoFruitConfig.swap();
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Auto Shovel Swap", cfg::isEnabledRaw,
                cfg::setEnabled, cfg::save, requestRebuild);
        y = IslandRows.automationNote(w, contentX, y, contentWidth);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        IslandRows.slider(w, contentX, y, contentWidth, AutoFruitConfig.MIN_DELAY_MS, AutoFruitConfig.MAX_DELAY_MS, 10,
                cfg::getDelayMs, cfg::setDelayMs, v -> "Swap Delay: " + v + "ms", cfg::save);
        return w;
    }
}
