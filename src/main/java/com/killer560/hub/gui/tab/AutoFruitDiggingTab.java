package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.AutoFruitConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Fruit Digging settings (cheat build only) - see {@code carnival.AutoFruitDigging}. */
public class AutoFruitDiggingTab extends BaseTab {

    public AutoFruitDiggingTab() {
        super("Auto Fruit Digging");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AutoFruitConfig cfg = AutoFruitConfig.dig();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Auto Fruit Digging", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        y = IslandRows.automationNote(w, contentX, y, contentWidth);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        int half = (contentWidth - 4) / 2;
        IslandRows.slider(w, contentX, y, half, AutoFruitConfig.MIN_DELAY_MS, AutoFruitConfig.MAX_DELAY_MS, 10,
                cfg::getDelayMs, cfg::setDelayMs, v -> "Dig Delay: " + v + "ms", save);
        IslandRows.toggle(w, contentX + half + 4, y, half, "Free Camera", cfg::isFreeCamera, cfg::setFreeCamera, save);
        return w;
    }
}
