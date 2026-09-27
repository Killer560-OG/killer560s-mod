package com.killer560.hub.gui.tab;

import com.killer560.hub.doorhelpers.DoorHelpersConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Auto Door Opener (cheat build only) - see {@link com.killer560.hub.doorhelpers.DoorHelpersFeature}.
 *  <p>
 *  killer560, 2026-09-27: "Remove look at doors as a setting from door helpers ... And rename it to auto door
 *  opener." This tab used to also hold Look At Door (its own camera-turn feature, with its own keybind); that
 *  section is gone entirely, so this class no longer implements {@code KeyCaptureTab} - there is nothing left
 *  here to bind a key to. */
public class DoorHelpersTab extends BaseTab {

    public DoorHelpersTab() {
        super("Auto Door Opener");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DoorHelpersConfig cfg = DoorHelpersConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Door Opener", cfg.isAutoDoorEnabledRaw()), btn -> {
                    cfg.setAutoDoorEnabled(!cfg.isAutoDoorEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isAutoDoorEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.setAutoDoorMode(cfg.getAutoDoorMode() == DoorHelpersConfig.OpenerMode.AURA
                            ? DoorHelpersConfig.OpenerMode.TRIGGERBOT : DoorHelpersConfig.OpenerMode.AURA);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, cfg.getAutoDoorMode() == DoorHelpersConfig.OpenerMode.AURA ? col2W : contentWidth, 18)
                .build());
        if (cfg.getAutoDoorMode() == DoorHelpersConfig.OpenerMode.AURA) {
            double span = DoorHelpersConfig.RANGE_MAX - DoorHelpersConfig.RANGE_MIN;
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, rangeText(cfg),
                    (cfg.getAutoDoorRange() - DoorHelpersConfig.RANGE_MIN) / span) {
                @Override
                protected void updateMessage() {
                    setMessage(rangeText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setAutoDoorRange(DoorHelpersConfig.RANGE_MIN + this.value * span);
                    cfg.save();
                }
            });
        }
        y += 20;

        int retrySpan = DoorHelpersConfig.RETRY_MAX_MS - DoorHelpersConfig.RETRY_MIN_MS;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, retryText(cfg),
                (cfg.getAutoDoorRetryDelayMs() - DoorHelpersConfig.RETRY_MIN_MS) / (double) retrySpan) {
            @Override
            protected void updateMessage() {
                setMessage(retryText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setAutoDoorRetryDelayMs((int) Math.round(DoorHelpersConfig.RETRY_MIN_MS + this.value * retrySpan));
                cfg.save();
            }
        });
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Swing Hand", cfg.isAutoDoorSwing()), btn -> {
                    cfg.setAutoDoorSwing(!cfg.isAutoDoorSwing());
                    cfg.save();
                    btn.setMessage(onOff("Swing Hand", cfg.isAutoDoorSwing()));
                }).bounds(contentX, y, col2W, 18).build());
        y += 22;

        return widgets;
    }

    private static Component modeText(DoorHelpersConfig cfg) {
        return Component.literal("Mode: " + (cfg.getAutoDoorMode() == DoorHelpersConfig.OpenerMode.AURA ? "Aura" : "Triggerbot"));
    }

    private static Component rangeText(DoorHelpersConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.1f", cfg.getAutoDoorRange()));
    }

    private static Component retryText(DoorHelpersConfig cfg) {
        return Component.literal("Retry Delay: " + cfg.getAutoDoorRetryDelayMs() + "ms");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
