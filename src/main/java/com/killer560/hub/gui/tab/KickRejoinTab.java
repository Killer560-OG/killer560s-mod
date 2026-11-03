package com.killer560.hub.gui.tab;

import com.killer560.hub.kickrejoin.KickRejoinConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Kick Rejoin Timer ({@link com.killer560.hub.kickrejoin.KickRejoinFeature}): the switch, the title and sound when
 *  the wait is over, and how long the wait is. */
public class KickRejoinTab extends BaseTab {

    private static final int ROW = 18;

    public KickRejoinTab() {
        super("Kick Rejoin Timer");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        KickRejoinConfig cfg = KickRejoinConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(onOffButton("Kick Rejoin Timer", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(onOffButton("Rejoin Title", cfg::isReadyTitle, cfg::setReadyTitle, cfg::save,
                null, contentX, y, colW, ROW));
        widgets.add(onOffButton("Rejoin Sound", cfg::isReadySound, cfg::setReadySound, cfg::save,
                null, colBX, y, colW, ROW));
        y += 20;
        widgets.add(steppedSlider(contentX, y, contentWidth, ROW, waitText(cfg),
                KickRejoinConfig.COOLDOWN_MIN, KickRejoinConfig.COOLDOWN_MAX, cfg.getCooldownSeconds(), 1,
                v -> cfg.setCooldownSeconds((int) Math.round(v)), () -> waitText(cfg), cfg::save));
        return widgets;
    }

    private static Component waitText(KickRejoinConfig cfg) {
        return Component.literal("Rejoin Wait: " + cfg.getCooldownSeconds() + "s");
    }
}
