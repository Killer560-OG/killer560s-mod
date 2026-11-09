package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.terminatorclicker.TerminatorClickerConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Terminator Auto Clicker (cheat only): the switch and the clicks per second. See
 *  {@link com.killer560.hub.terminatorclicker.TerminatorClickerFeature}. */
public class TerminatorClickerTab extends BaseTab {

    public TerminatorClickerTab() {
        super("Terminator Auto Clicker");
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
        widgets.add(onOffButton("Terminator Auto Clicker", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth, 20));
        if (!cfg.isEnabled() && !isSearchScan()) {
            return widgets;
        }
        int min = TerminatorClickerConfig.MIN_CPS;
        int max = TerminatorClickerConfig.MAX_CPS;
        widgets.add(new ThemedSliderButton(contentX, contentY + 24, contentWidth, 18, cpsText(cfg),
                (cfg.getCps() - min) / (double) (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(cpsText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setCps((int) Math.round(min + this.value * (max - min)));
                saveOnRelease(cfg::save);
            }
        });
        return widgets;
    }

    private static Component cpsText(TerminatorClickerConfig cfg) {
        return Component.literal("Clicks Per Second: " + cfg.getCps());
    }
}
