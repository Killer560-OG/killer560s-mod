package com.killer560.hub.gui.tab;

import com.killer560.hub.autoclosechest.AutoCloseChestConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Close Chest settings. Click mode (any key or mouse press closes the dungeon secret chest, featE-1108) is in
 *  every build; Auto mode, which closes the chest before it opens, is cheat build only (killer560, 2026-10-07) - the
 *  Mode row exists only in a cheat jar. See {@link com.killer560.hub.autoclosechest.AutoCloseChestFeature}. */
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
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && (cfg.isEnabledRaw() || isSearchScan())) {
            y += 24;
            widgets.add(SettingsButtonWidget.cycleEnum(() -> Component.literal("Close Mode: "
                            + (cfg.getMode() == AutoCloseChestConfig.Mode.AUTO ? "Auto" : "Click")),
                    cfg::getMode, cfg::setMode, cfg::save).bounds(contentX, y, contentWidth, 20).build());
        }
        return widgets;
    }
}
