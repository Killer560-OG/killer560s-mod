package com.killer560.hub.gui.tab;

import com.killer560.hub.ahflip.AhFlipConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** AH Price Buttons settings (2026-11-03, was the AH Price Copier): the switch and the Undercut Copy LBIN takes off. */
public class AhPriceButtonsTab extends BaseTab {

    public AhPriceButtonsTab() {
        super("AH Price Buttons");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("AH Price Buttons", cfg::isPriceButtonsEnabledRaw, cfg::setPriceButtonsEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;
        if (!cfg.isPriceButtonsEnabledRaw()) {
            return w;
        }
        w.add(SettingsButtonWidget.cycling(undercutText(cfg), (btn, dir) -> {
            cfg.stepCopierUndercut(dir);
            cfg.save();
            btn.setMessage(undercutText(cfg));
        }).bounds(contentX, y, contentWidth, 18).build());
        return w;
    }

    private static Component undercutText(AhFlipConfig cfg) {
        long v = cfg.getCopierUndercut();
        return Component.literal(v <= 0 ? "Undercut: §6None (exact price)"
                : String.format(Locale.US, "Undercut: §6%,d coin%s", v, v == 1 ? "" : "s"));
    }
}
