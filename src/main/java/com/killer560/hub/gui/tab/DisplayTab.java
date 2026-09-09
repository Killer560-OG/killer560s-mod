package com.killer560.hub.gui.tab;

import com.killer560.hub.fullbright.FullbrightConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.window.WindowModeConfig;
import com.killer560.hub.window.WindowModeFeature;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Window/rendering settings - split out of Home (2026-09-08) per killer560's request that Home only
 *  hold the HUD editor, Join Discord, and Check for Updates. */
public class DisplayTab extends BaseTab {

    public DisplayTab() {
        super("Display");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(borderlessText(), btn -> {
                    WindowModeFeature.toggle();
                    btn.setMessage(borderlessText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(fullbrightText(), btn -> {
                    FullbrightConfig cfg = FullbrightConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(fullbrightText());
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static Component borderlessText() {
        return Component.literal("Borderless Fullscreen: "
                + (WindowModeConfig.getInstance().isBorderlessFullscreenEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component fullbrightText() {
        return Component.literal("Fullbright: "
                + (FullbrightConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
