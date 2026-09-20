package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.p4platform.P4PlatformHighlightConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** P4 Platform Highlight settings - see
 *  {@link com.killer560.hub.p4platform.P4PlatformHighlightFeature}'s class doc for the real QUOI-ported
 *  "highlight the 3x3 to mine after Goldor dies" this is built on. */
public class P4PlatformHighlightTab extends BaseTab {

    public P4PlatformHighlightTab() {
        super("P4 Platform Highlight");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        P4PlatformHighlightConfig cfg = P4PlatformHighlightConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("P4 Platform Highlight", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Filled Box", cfg.isFilled()), btn -> {
                    cfg.setFilled(!cfg.isFilled());
                    cfg.save();
                    btn.setMessage(onOff("Filled Box", cfg.isFilled()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
