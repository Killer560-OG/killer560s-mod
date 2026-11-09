package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.reparty.RepartyConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Reparty (see {@link com.killer560.hub.reparty.RepartyFeature}): the switch for /rp and Auto Reparty with its delay. */
public class RepartyTab extends BaseTab {

    public RepartyTab() {
        super("Reparty");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        RepartyConfig cfg = RepartyConfig.getInstance();
        if (masterClosed(widgets, "Reparty", cfg::getEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth)) {
            return widgets;
        }
        int colW = (contentWidth - 8) / 2;
        widgets.add(onOffButton("Auto Reparty", cfg::isAuto, cfg::setAuto, cfg::save, null, contentX, contentY + 24, colW, 18));
        widgets.add(SettingsButtonWidget.cycling(delayLabel(cfg), (btn, dir) -> {
            int[] d = RepartyConfig.DELAYS;
            int at = 0;
            for (int i = 0; i < d.length; i++) {
                at = d[i] == cfg.getDelaySeconds() ? i : at;
            }
            cfg.setDelaySeconds(d[SettingsButtonWidget.step(at, dir, d.length)]);
            cfg.save();
            btn.setMessage(delayLabel(cfg));
        }).bounds(contentX + colW + 8, contentY + 24, colW, 18).build());
        return widgets;
    }

    private static Component delayLabel(RepartyConfig cfg) {
        return Component.literal("Auto Reparty Delay: §6" + cfg.getDelaySeconds() + " s");
    }
}
