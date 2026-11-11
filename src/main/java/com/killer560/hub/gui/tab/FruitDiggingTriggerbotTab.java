package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.AutoFruitConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Fruit Digging Triggerbot settings (cheat build only) - see {@code carnival.FruitDiggingTriggerbot}. */
public class FruitDiggingTriggerbotTab extends BaseTab {

    public FruitDiggingTriggerbotTab() {
        super("Fruit Digging Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AutoFruitConfig cfg = AutoFruitConfig.trigger();
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Fruit Digging Triggerbot", cfg::isEnabledRaw,
                cfg::setEnabled, cfg::save, requestRebuild);
        y = IslandRows.automationNote(w, contentX, y, contentWidth);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        IslandRows.slider(w, contentX, y, contentWidth, AutoFruitConfig.MIN_DELAY_MS, AutoFruitConfig.MAX_DELAY_MS, 10,
                cfg::getDelayMs, cfg::setDelayMs, v -> "Reaction Delay: " + v + "ms", cfg::save);
        return w;
    }
}
