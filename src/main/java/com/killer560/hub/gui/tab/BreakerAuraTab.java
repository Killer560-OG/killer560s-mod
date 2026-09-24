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
public class BreakerAuraTab extends BaseTab implements KeyCaptureTab {

    /** Non-zero while the Pick Block key is being captured - same pattern as AbilityKeybindsTab. */
    private int capturing = 0;

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                ? com.killer560.hub.util.KeyUtil.NONE : keyCode);
    }

    public boolean supportsMouseCapture() {
        return true;
    }

    public void onMouseCaptured(int button) {
        applyCapture(com.killer560.hub.abilitykeybinds.AbilityKeybindsConfig.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (capturing == 1) {
            cfg.setBreakerAuraSelectKey(code);
        }
        capturing = 0;
        cfg.save();
    }

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

        // The pick key is offered even with the aura OFF: killer560 picks the wall first and switches it on after.
        widgets.add(SettingsButtonWidget.builder(
                capturing == 1 ? Component.literal("Press any key...")
                        : Component.literal("Pick Block Key: "
                                + CommandKeybindsTab.bindName(cfg.getBreakerAuraSelectKey())),
                btn -> {
                    capturing = 1;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, col2W, 18)
                .build());
        int picked = cfg.getBreakerAuraSelected().size();
        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Clear Picked (" + picked + ")"), btn -> {
                    com.killer560.hub.dungeonextras.BreakerAuraFeature.clearSelection();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18)
                .build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Only Picked Blocks", cfg.isBreakerAuraSelectedOnly()),
                btn -> {
                    cfg.setBreakerAuraSelectedOnly(!cfg.isBreakerAuraSelectedOnly());
                    cfg.save();
                    btn.setMessage(onOff("Only Picked Blocks", cfg.isBreakerAuraSelectedOnly()));
                }).bounds(contentX, y, contentWidth, 18)
                .build());
        y += 22;

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
        y += 20;

        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, perCycleText(cfg),
                (cfg.getBreakerAuraBlocksPerCycle() - 1) / 19.0) {
            @Override
            protected void updateMessage() {
                setMessage(perCycleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBreakerAuraBlocksPerCycle((int) Math.round(1 + this.value * 19));
                cfg.save();
            }
        });
        y -= 20;

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

    /** 1 is one block a tick, the rate a hand can produce; anything above it puts that many interaction packets
     *  in a single tick, which is visible as automation. Said plainly on the control itself rather than buried. */
    private static Component perCycleText(DungeonExtrasConfig cfg) {
        int n = cfg.getBreakerAuraBlocksPerCycle();
        return Component.literal("Blocks Per Tick: " + n + (n == 1 ? " §7(hand-like)" : " §c(obvious automation)"));
    }

    private static Component cooldownText(DungeonExtrasConfig cfg) {
        return Component.literal("Cooldown: " + cfg.getBreakerAuraCooldownTicks() + " ticks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
