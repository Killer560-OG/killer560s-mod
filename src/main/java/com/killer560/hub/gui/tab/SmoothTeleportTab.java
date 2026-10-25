package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.smoothtp.SmoothTeleportConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Smooth Teleport settings - see {@link com.killer560.hub.smoothtp.SmoothTeleport}. Visual only, so both builds. */
public class SmoothTeleportTab extends BaseTab {

    private static final int SPAN = SmoothTeleportConfig.MAX_DURATION_MS - SmoothTeleportConfig.MIN_DURATION_MS;

    public SmoothTeleportTab() {
        super("Smooth Teleport");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        SmoothTeleportConfig cfg = SmoothTeleportConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Smooth Teleport", cfg.isEnabledRaw()), btn -> {
                    SmoothTeleportConfig live = SmoothTeleportConfig.getInstance();
                    live.setEnabled(!live.isEnabledRaw());
                    live.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, durationText(cfg),
                (cfg.getDurationMs() - SmoothTeleportConfig.MIN_DURATION_MS) / (double) SPAN) {
            @Override
            protected void updateMessage() {
                setMessage(durationText(SmoothTeleportConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                // Steps of 10 ms.
                int ms = SmoothTeleportConfig.MIN_DURATION_MS + (int) Math.round(this.value * SPAN / 10.0) * 10;
                SmoothTeleportConfig live = SmoothTeleportConfig.getInstance();
                live.setDurationMs(ms);
                saveOnRelease(() -> live.save());
            }
        });
        y += 22;

        widgets.add(toggle(contentX, y, contentWidth, "Etherwarp", SmoothTeleportConfig::isEtherwarp,
                SmoothTeleportConfig::setEtherwarp));
        y += 22;
        widgets.add(toggle(contentX, y, contentWidth, "Instant Transmission",
                SmoothTeleportConfig::isInstantTransmission, SmoothTeleportConfig::setInstantTransmission));
        y += 22;
        widgets.add(toggle(contentX, y, contentWidth, "Wither Impact", SmoothTeleportConfig::isWitherImpact,
                SmoothTeleportConfig::setWitherImpact));
        y += 22;
        widgets.add(toggle(contentX, y, contentWidth, "Other Teleports", SmoothTeleportConfig::isOtherTeleports,
                SmoothTeleportConfig::setOtherTeleports));
        return widgets;
    }

    /** Acts on the live config each press: a profile apply replaces the instance this tab was built with. */
    private static AbstractWidget toggle(int x, int y, int width, String label,
                                         Predicate<SmoothTeleportConfig> get,
                                         BiConsumer<SmoothTeleportConfig, Boolean> set) {
        return SettingsButtonWidget.builder(onOff(label, get.test(SmoothTeleportConfig.getInstance())), btn -> {
            SmoothTeleportConfig live = SmoothTeleportConfig.getInstance();
            set.accept(live, !get.test(live));
            live.save();
            btn.setMessage(onOff(label, get.test(live)));
        }).bounds(x, y, width, 18).build();
    }

    private static Component durationText(SmoothTeleportConfig cfg) {
        return Component.literal("Duration: " + cfg.getDurationMs() + " ms");
    }
}
