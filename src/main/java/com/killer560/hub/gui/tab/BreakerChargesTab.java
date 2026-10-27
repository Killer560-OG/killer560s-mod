package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Breaker Charges HUD (see {@link com.killer560.hub.dungeonalerts.BreakerChargesHud}); moved in the HUD editor. */
public class BreakerChargesTab extends BaseTab {

    public BreakerChargesTab() {
        super("Breaker Charges");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        w.add(SettingsButtonWidget.builder(onOff(cfg.isBreakerChargesHud()), btn -> {
                    cfg.setBreakerChargesHud(!cfg.isBreakerChargesHud());
                    cfg.save();
                    btn.setMessage(onOff(cfg.isBreakerChargesHud()));
                }).bounds(contentX, contentY, contentWidth, 20).build());
        return w;
    }

    private static Component onOff(boolean value) {
        return Component.literal("Breaker Charges: " + (value ? "§aON" : "§cOFF"));
    }
}
