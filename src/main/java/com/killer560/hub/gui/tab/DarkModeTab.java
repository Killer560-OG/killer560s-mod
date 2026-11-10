package com.killer560.hub.gui.tab;

import com.killer560.hub.darkmode.DarkModeConfig;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Dark Mode settings - see {@link DarkModeConfig} and {@code darkmode/DarkModeFeature}. */
public class DarkModeTab extends BaseTab {

    public DarkModeTab() {
        super("Dark Mode");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DarkModeConfig cfg = DarkModeConfig.getInstance();
        int y = contentY;

        widgets.add(onOffButton("Dark Mode", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        double range = DarkModeConfig.MAX_OPACITY - DarkModeConfig.MIN_OPACITY;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, opacityText(cfg.getOpacity()),
                (cfg.getOpacity() - DarkModeConfig.MIN_OPACITY) / range) {
            @Override
            protected void updateMessage() {
                setMessage(opacityText(cfg.getOpacity()));
            }

            @Override
            protected void applyValue() {
                cfg.setOpacity((int) Math.round(DarkModeConfig.MIN_OPACITY + this.value * range));
                saveOnRelease(() -> cfg.save());
            }
        });
        return widgets;
    }

    private static Component opacityText(int opacity) {
        return Component.literal("Opacity: " + opacity + "%");
    }
}
