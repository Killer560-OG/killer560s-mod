package com.killer560.hub.gui.tab;

import com.killer560.hub.accadvisor.AccessoryAdvisor;
import com.killer560.hub.accadvisor.AccessoryAdvisorConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Accessory Advisor settings - see {@link com.killer560.hub.accadvisor.AccessoryAdvisor}: the switch, the ranking mode
 *  and how many rows the panel shows. */
public class AccessoryAdvisorTab extends BaseTab {

    public AccessoryAdvisorTab() {
        super("Accessory Advisor");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AccessoryAdvisorConfig cfg = AccessoryAdvisorConfig.getInstance();

        if (masterClosed(widgets, "Accessory Advisor", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 26;

        widgets.add(SettingsButtonWidget.cycling(Component.literal("Mode: " + cfg.getMode().label), (btn, dir) -> {
                    cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), dir));
                    cfg.save();
                    btn.setMessage(Component.literal("Mode: " + cfg.getMode().label));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        int span = AccessoryAdvisorConfig.MAX_ROWS - AccessoryAdvisorConfig.MIN_ROWS + 1;
        widgets.add(SettingsButtonWidget.cycling(Component.literal("Rows: " + cfg.getRows()), (btn, dir) -> {
                    cfg.setRows(AccessoryAdvisorConfig.MIN_ROWS + SettingsButtonWidget.step(
                            cfg.getRows() - AccessoryAdvisorConfig.MIN_ROWS, dir, span));
                    cfg.save();
                    btn.setMessage(Component.literal("Rows: " + cfg.getRows()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Position"), btn -> AccessoryAdvisor.resetPosition())
                .bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }
}
