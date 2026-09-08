package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.nofire.NoFireConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Toggles that hide specific vanilla screen elements/effects - starting with the fire overlay, more
 *  to be added here over time (2026-09-08, per killer560's request). */
public class ObjectHiderTab extends BaseTab {

    public ObjectHiderTab() {
        super("Object Hider");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(noFireText(), btn -> {
                    NoFireConfig cfg = NoFireConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(noFireText());
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static Component noFireText() {
        return Component.literal("No Fire Overlay: "
                + (NoFireConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
