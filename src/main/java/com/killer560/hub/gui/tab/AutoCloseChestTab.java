package com.killer560.hub.gui.tab;

import com.killer560.hub.autoclosechest.AutoCloseChestConfig;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Close Chest settings: Close On Any Key (every build, featE-1108) and the cheat-only Auto, which closes the chest
 *  before it opens (killer560, 2026-10-07; 2026-11-09 rework). See {@link com.killer560.hub.autoclosechest.AutoCloseChestFeature}. */
public class AutoCloseChestTab extends BaseTab {

    public AutoCloseChestTab() {
        super("Auto Close Chest");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AutoCloseChestConfig cfg = AutoCloseChestConfig.getInstance();

        widgets.add(onOffButton("Auto Close Chest", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                null, contentX, y, contentWidth, 20));
        if (cfg.isEnabledRaw() || isSearchScan()) {
            y += 24;
            widgets.add(onOffButton("Close On Any Key", cfg::isAnyKey, cfg::setAnyKey, cfg::save,
                    null, contentX, y, contentWidth, 20));
            if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                y += 28;
                widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Automation", true));
                y += 16;
                widgets.add(onOffButton("Auto", cfg::isAutoRaw, cfg::setAuto, cfg::save,
                        null, contentX, y, contentWidth, 20));
            }
        }
        return widgets;
    }
}
