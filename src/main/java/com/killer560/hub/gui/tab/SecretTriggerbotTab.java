package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.secrettrigger.SecretTriggerbotConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "Secret Triggerbot" settings (cheat build only) - see {@link com.killer560.hub.secrettrigger.SecretTriggerbotFeature}. */
public class SecretTriggerbotTab extends BaseTab {

    public SecretTriggerbotTab() {
        super("Secret Triggerbot");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SecretTriggerbotConfig cfg = SecretTriggerbotConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Secret Triggerbot", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, delayText(cfg),
                cfg.getDelayMs() / (double) SecretTriggerbotConfig.MAX_DELAY_MS) {
            @Override
            protected void updateMessage() {
                setMessage(delayText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setDelayMs((int) Math.round(this.value * SecretTriggerbotConfig.MAX_DELAY_MS));
                cfg.save();
            }
        });
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, cooldownText(cfg),
                cfg.getCooldownMs() / (double) SecretTriggerbotConfig.MAX_COOLDOWN_MS) {
            @Override
            protected void updateMessage() {
                setMessage(cooldownText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setCooldownMs((int) Math.round(this.value * SecretTriggerbotConfig.MAX_COOLDOWN_MS));
                cfg.save();
            }
        });
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Swap To Slot", cfg.isSwapEnabled()), btn -> {
                    cfg.setSwapEnabled(!cfg.isSwapEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isSwapEnabled()) {
            widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, slotText(cfg), (cfg.getSwapSlot() - 1) / 8.0) {
                @Override
                protected void updateMessage() {
                    setMessage(slotText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setSwapSlot(1 + (int) Math.round(this.value * 8));
                    cfg.save();
                }
            });
            widgets.add(SettingsButtonWidget.builder(onOff("Swap Back", cfg.isSwapBack()), btn -> {
                        cfg.setSwapBack(!cfg.isSwapBack());
                        cfg.save();
                        btn.setMessage(onOff("Swap Back", cfg.isSwapBack()));
                    }).bounds(col2bX, y, col2W, 18).build());
        }

        return widgets;
    }

    private static Component delayText(SecretTriggerbotConfig cfg) {
        return Component.literal("Delay: " + cfg.getDelayMs() + "ms");
    }

    private static Component cooldownText(SecretTriggerbotConfig cfg) {
        return Component.literal("Cooldown: " + cfg.getCooldownMs() + "ms");
    }

    private static Component slotText(SecretTriggerbotConfig cfg) {
        return Component.literal("Swap Slot: " + cfg.getSwapSlot());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
