package com.killer560.hub.gui.tab;

import com.killer560.hub.kickhub.KickHubConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Kicked To Hub ({@link com.killer560.hub.kickhub.KickHubFeature}): the switch, then the Hub alert and warp back, then
 *  the restart and rejoin helpers from SkyHanni. */
public class KickHubTab extends BaseTab {

    private static final int ROW = 18;

    public KickHubTab() {
        super("Kicked To Hub");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        KickHubConfig cfg = KickHubConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(onOffButton("Kicked To Hub", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(onOffButton("Hub Alert", cfg::isHubAlert, cfg::setHubAlert, cfg::save,
                null, contentX, y, colW, ROW));
        widgets.add(onOffButton("Kick Alert Sound", cfg::isAlertSound, cfg::setAlertSound, cfg::save,
                null, colBX, y, colW, ROW));
        y += 20;
        widgets.add(onOffButton("Warp Back Line", cfg::isWarpBackLine, cfg::setWarpBackLine, cfg::save,
                null, contentX, y, colW, ROW));
        widgets.add(onOffButton("Auto Warp Back", cfg::isAutoWarpBack, cfg::setAutoWarpBack, cfg::save,
                requestRebuild, colBX, y, colW, ROW));
        y += 20;
        if (cfg.isAutoWarpBack()) {
            widgets.add(steppedSlider(contentX, y, contentWidth, ROW, delayText(cfg),
                    KickHubConfig.DELAY_MIN, KickHubConfig.DELAY_MAX, cfg.getAutoDelaySeconds(), 1,
                    v -> cfg.setAutoDelaySeconds((int) Math.round(v)), () -> delayText(cfg), cfg::save));
            y += 20;
        }
        y += 4;
        widgets.add(onOffButton("Restart Alert", cfg::isRestartAlert, cfg::setRestartAlert, cfg::save,
                null, contentX, y, colW, ROW));
        widgets.add(onOffButton("Restart Countdown", cfg::isRestartCountdown, cfg::setRestartCountdown, cfg::save,
                null, colBX, y, colW, ROW));
        y += 20;
        widgets.add(onOffButton("Rejoin Timer", cfg::isRejoinTimer, cfg::setRejoinTimer, cfg::save,
                requestRebuild, contentX, y, contentWidth, ROW));
        y += 20;
        if (cfg.isRejoinTimer()) {
            widgets.add(steppedSlider(contentX, y, contentWidth, ROW, warnText(cfg),
                    KickHubConfig.WARN_MIN, KickHubConfig.WARN_MAX, cfg.getRejoinWarnSeconds(), 1,
                    v -> cfg.setRejoinWarnSeconds((int) Math.round(v)), () -> warnText(cfg), cfg::save));
        }
        return widgets;
    }

    private static Component delayText(KickHubConfig cfg) {
        return Component.literal("Auto Warp Delay: " + cfg.getAutoDelaySeconds() + "s");
    }

    private static Component warnText(KickHubConfig cfg) {
        return Component.literal("Rejoin Warn Time: " + cfg.getRejoinWarnSeconds() + "s");
    }
}
