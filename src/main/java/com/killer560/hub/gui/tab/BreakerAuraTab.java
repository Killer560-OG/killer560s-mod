package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonextras.DungeonExtrasConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Breaker Aura - see {@link com.killer560.hub.dungeonextras.BreakerAuraFeature}. Cheat build only; split
 *  out of the old "Dungeon Extras" tab 2026-09-20 per killer560: "Make a breaker aura tab itself" - shares
 *  {@link DungeonExtrasConfig} with {@link CustomMageBeamTab} and {@link AutoDialogueTab}; the config file
 *  itself was not split. */
public class BreakerAuraTab extends BaseTab {

    public BreakerAuraTab() {
        super("Breaker Aura");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Breaker Aura", cfg.isBreakerAuraEnabledRaw()), btn -> {
                    cfg.setBreakerAuraEnabled(!cfg.isBreakerAuraEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isBreakerAuraEnabledRaw()) {
            return widgets;
        }

        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, reachText(cfg),
                (cfg.getBreakerAuraReach() - 1.0) / 4.5) {
            @Override
            protected void updateMessage() {
                setMessage(reachText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBreakerAuraReach(Math.round((1.0 + this.value * 4.5) * 10.0) / 10.0);
                cfg.save();
            }
        });
        widgets.add(SettingsButtonWidget.builder(onOff("Zero Ping", cfg.isBreakerAuraZeroPingRaw()), btn -> {
                    cfg.setBreakerAuraZeroPing(!cfg.isBreakerAuraZeroPingRaw());
                    cfg.save();
                    btn.setMessage(onOff("Zero Ping", cfg.isBreakerAuraZeroPingRaw()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, cooldownText(cfg),
                (cfg.getBreakerAuraCooldownTicks() - 1) / 19.0) {
            @Override
            protected void updateMessage() {
                setMessage(cooldownText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBreakerAuraCooldownTicks((int) Math.round(1 + this.value * 19));
                cfg.save();
            }
        });
        widgets.add(SettingsButtonWidget.builder(onOff("Pause In Edit Mode", cfg.isBreakerAuraRespectEditMode()), btn -> {
                    cfg.setBreakerAuraRespectEditMode(!cfg.isBreakerAuraRespectEditMode());
                    cfg.save();
                    btn.setMessage(onOff("Pause In Edit Mode", cfg.isBreakerAuraRespectEditMode()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Swap", cfg.isBreakerAuraAutoSwap()), btn -> {
                    cfg.setBreakerAuraAutoSwap(!cfg.isBreakerAuraAutoSwap());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;

        if (cfg.isBreakerAuraAutoSwap()) {
            widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, swapDelayText(cfg),
                    (cfg.getBreakerAuraSwapDelayTicks() - 1) / 19.0) {
                @Override
                protected void updateMessage() {
                    setMessage(swapDelayText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setBreakerAuraSwapDelayTicks((int) Math.round(1 + this.value * 19));
                    cfg.save();
                }
            });
            widgets.add(SettingsButtonWidget.builder(onOff("Swap Back", cfg.isBreakerAuraSwapBack()), btn -> {
                        cfg.setBreakerAuraSwapBack(!cfg.isBreakerAuraSwapBack());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;

            if (cfg.isBreakerAuraSwapBack()) {
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, swapBackText(cfg),
                        (cfg.getBreakerAuraSwapBackIdleTicks() - 5) / 95.0) {
                    @Override
                    protected void updateMessage() {
                        setMessage(swapBackText(cfg));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setBreakerAuraSwapBackIdleTicks((int) Math.round(5 + this.value * 95));
                        cfg.save();
                    }
                });
                y += 20;
            }
        }

        return widgets;
    }

    private static Component reachText(DungeonExtrasConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Reach: %.1f", cfg.getBreakerAuraReach()));
    }

    private static Component swapDelayText(DungeonExtrasConfig cfg) {
        return Component.literal("Swap Delay: " + cfg.getBreakerAuraSwapDelayTicks() + " ticks");
    }

    private static Component swapBackText(DungeonExtrasConfig cfg) {
        return Component.literal("Swap Back After: " + cfg.getBreakerAuraSwapBackIdleTicks() + " idle ticks");
    }

    private static Component cooldownText(DungeonExtrasConfig cfg) {
        return Component.literal("Cooldown: " + cfg.getBreakerAuraCooldownTicks() + " ticks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
