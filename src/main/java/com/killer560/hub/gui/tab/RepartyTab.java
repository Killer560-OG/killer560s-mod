package com.killer560.hub.gui.tab;

import com.killer560.hub.reparty.RepartyConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Reparty (see {@link com.killer560.hub.reparty.RepartyFeature}): the switch for /rp, the invite delay and Auto Rejoin. */
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
        int y = contentY + 24;
        widgets.add(steppedSlider(contentX, y, contentWidth, 18, delayLabel(cfg), RepartyConfig.MIN_DELAY, RepartyConfig.MAX_DELAY,
                cfg.getInviteDelay(), 0.5, cfg::setInviteDelay, () -> delayLabel(cfg), cfg::save));
        y += 24;
        widgets.add(onOffButton("Auto Rejoin", cfg::isAutoRejoin, cfg::setAutoRejoin, cfg::save, requestRebuild, contentX, y, colW, 18));
        if (cfg.isAutoRejoin() || isSearchScan()) {
            widgets.add(steppedSlider(contentX + colW + 8, y, colW, 18, windowLabel(cfg), RepartyConfig.MIN_WINDOW, RepartyConfig.MAX_WINDOW,
                    cfg.getRejoinWindow(), 5, v -> cfg.setRejoinWindow((int) v), () -> windowLabel(cfg), cfg::save));
        }
        return widgets;
    }

    private static Component delayLabel(RepartyConfig cfg) {
        return Component.literal("Invite Delay: §6" + cfg.getInviteDelay() + " s");
    }

    private static Component windowLabel(RepartyConfig cfg) {
        return Component.literal("Rejoin Window: §6" + cfg.getRejoinWindow() + " s");
    }
}
