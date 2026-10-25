package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.trajectories.TrajectoriesConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Trajectories settings - see {@link com.killer560.hub.trajectories.TrajectoriesFeature}'s class doc
 *  for the real QUOI-ported projectile simulation this is built on. */
public class TrajectoriesTab extends BaseTab {

    public TrajectoriesTab() {
        super("Trajectories");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TrajectoriesConfig cfg = TrajectoriesConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(onOffButton("Trajectories", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Bows", cfg::isShowBows, cfg::setShowBows, cfg::save, null, col2aX, y, col2W, 18));
        widgets.add(onOffButton("Shortbows", cfg::isShowShortbows, cfg::setShowShortbows, cfg::save,
                null, col2bX, y, col2W, 18));
        y += 26;

        widgets.add(onOffButton("Ender Pearls", cfg::isShowPearls, cfg::setShowPearls, cfg::save,
                null, col2aX, y, col2W, 18));
        y += 26;

        double rangeNormalized = (cfg.getRange() - 5) / 115.0;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                Component.literal("Simulated Ticks: " + cfg.getRange()), rangeNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Simulated Ticks: " + cfg.getRange()));
            }

            @Override
            protected void applyValue() {
                cfg.setRange((int) Math.round(5 + this.value * 115));
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 26;

        return widgets;
    }
}
