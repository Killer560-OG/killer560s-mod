package com.killer560.hub.gui.tab;

import com.killer560.hub.doorhelpers.DoorHelpersConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Door Helpers (cheat build only) - Auto Door Opener and Look At Door, see
 *  {@link com.killer560.hub.doorhelpers.DoorHelpersFeature}. */
public class DoorHelpersTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing = false;

    public DoorHelpersTab() {
        super("Door Helpers");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        DoorHelpersConfig cfg = DoorHelpersConfig.getInstance();
        cfg.setLookAtDoorKey(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturing = false;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DoorHelpersConfig cfg = DoorHelpersConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;
        int y = contentY;

        // ---- Auto Door Opener ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Auto Door Opener", true), mc.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Door Opener", cfg.isAutoDoorEnabledRaw()), btn -> {
                    cfg.setAutoDoorEnabled(!cfg.isAutoDoorEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isAutoDoorEnabledRaw()) {
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
        }
        y += 6;

        // ---- Look At Door ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Look At Door", true), mc.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Look At Door", cfg.isLookAtDoorEnabledRaw()), btn -> {
                    cfg.setLookAtDoorEnabled(!cfg.isLookAtDoorEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isLookAtDoorEnabledRaw()) {
            widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                        capturing = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("On Key Pickup", cfg.isLookAtDoorOnKeyPickup()), btn -> {
                        cfg.setLookAtDoorOnKeyPickup(!cfg.isLookAtDoorOnKeyPickup());
                        cfg.save();
                        btn.setMessage(onOff("On Key Pickup", cfg.isLookAtDoorOnKeyPickup()));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;

            int speedSpan = DoorHelpersConfig.SPEED_MAX - DoorHelpersConfig.SPEED_MIN;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, speedText(cfg),
                    (cfg.getLookAtDoorSpeed() - DoorHelpersConfig.SPEED_MIN) / (double) speedSpan) {
                @Override
                protected void updateMessage() {
                    setMessage(speedText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setLookAtDoorSpeed((int) Math.round(DoorHelpersConfig.SPEED_MIN + this.value * speedSpan));
                    cfg.save();
                }
            });
        }

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

    private static Component speedText(DoorHelpersConfig cfg) {
        return Component.literal("Speed: " + cfg.getLookAtDoorSpeed());
    }

    private Component keyText(DoorHelpersConfig cfg) {
        if (capturing) {
            return Component.literal("Press any key...");
        }
        int key = cfg.getLookAtDoorKey();
        String name = key == -1 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal("Key: " + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
