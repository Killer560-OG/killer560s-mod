package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.RangeSliderWidget;
import com.killer560.hub.terminatorclicker.TerminatorClickerConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Shortbow Auto Clicker (cheat only): the switch and the clicks per second range (two handles). See
 *  {@link com.killer560.hub.terminatorclicker.TerminatorClickerFeature}. */
public class TerminatorClickerTab extends BaseTab {

    public TerminatorClickerTab() {
        super("Shortbow Auto Clicker");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        TerminatorClickerConfig cfg = TerminatorClickerConfig.getInstance();
        widgets.add(onOffButton("Shortbow Auto Clicker", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth, 20));
        if (!cfg.isEnabled() && !isSearchScan()) {
            return widgets;
        }
        widgets.add(new RangeSliderWidget(contentX, contentY + 24, contentWidth, 18, TerminatorClickerConfig.MIN_CPS,
                TerminatorClickerConfig.MAX_CPS, cfg.getCpsMin(), cfg.getCpsMax()) {
            @Override
            protected Component label(int low, int high) {
                return Component.literal("Clicks Per Second: " + low + " - " + high);
            }

            @Override
            protected void onRangeChanged(int low, int high) {
                cfg.setCpsRange(low, high);
                com.killer560.hub.gui.ThemedSliderButton.saveOnRelease(cfg::save);
            }
        });
        return widgets;
    }
}
