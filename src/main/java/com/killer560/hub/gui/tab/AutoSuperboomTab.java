package com.killer560.hub.gui.tab;

import com.killer560.hub.autosuperboom.AutoSuperboomConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Superboom (Dungeon, cheat-only): see {@link com.killer560.hub.autosuperboom.AutoSuperboomFeature}. */
public class AutoSuperboomTab extends BaseTab {

    public AutoSuperboomTab() {
        super("Auto Superboom");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AutoSuperboomConfig cfg = AutoSuperboomConfig.getInstance();
        masterClosed(w, "Auto Superboom", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY,
                contentWidth);
        return w;
    }
}
