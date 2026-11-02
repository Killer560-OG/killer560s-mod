package com.killer560.hub.gui.tab;

import com.killer560.hub.ahflip.AhFlipConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** AH Price Copier settings (2026-11-01): the switch, the Undercut, and Use My Last Price. */
public class AhPriceCopierTab extends BaseTab {

    public AhPriceCopierTab() {
        super("AH Price Copier");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("AH Price Copier", cfg::isCopierEnabledRaw, cfg::setCopierEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 26;
        if (!cfg.isCopierEnabledRaw()) {
            return w;
        }
        w.add(SettingsButtonWidget.cycling(undercutText(cfg), (btn, dir) -> {
            cfg.stepCopierUndercut(dir);
            cfg.save();
            btn.setMessage(undercutText(cfg));
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        w.add(onOffButton("Use My Last Price", cfg::isCopierOwnPrice, cfg::setCopierOwnPrice, cfg::save, null,
                contentX, y, contentWidth, 18));
        return w;
    }

    private static Component undercutText(AhFlipConfig cfg) {
        long v = cfg.getCopierUndercut();
        return Component.literal(v <= 0 ? "Undercut: §6None (exact price)"
                : String.format(Locale.US, "Undercut: §6%,d coin%s", v, v == 1 ? "" : "s"));
    }
}
