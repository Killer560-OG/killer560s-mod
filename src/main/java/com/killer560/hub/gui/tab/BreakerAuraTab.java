package com.killer560.hub.gui.tab;

import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.dungeonextras.BreakerAuraConfigScreen;
import com.killer560.hub.dungeonextras.BreakerAuraStore;
import com.killer560.hub.dungeonextras.DungeonExtrasConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

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

        // Swappable configs (killer560: "breaker aura also needs an option to swap between breaker auras just
        // like the auto routes can swap. with the same easy copy and whatnot") - same row order as AP3's
        // "Choose AP3 Config" / "Open AP3 Folder" / "Reload AP3 Chains". Offered even with the aura OFF, same
        // reasoning as the pick key below: he picks the wall (and which saved wall-set) before switching it on.
        widgets.add(SettingsButtonWidget.builder(Component.literal("Choose Breaker Aura Config: §6"
                        + cfg.getBreakerAuraConfigFile()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new BreakerAuraConfigScreen(McCompat.screen(client)));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Breaker Aura Folder"), btn -> openFolder())
                .bounds(contentX, y, col2W, 20).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reload Breaker Aura Picks"), btn -> {
                    BreakerAuraStore.reload();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 20).build());
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
        int picked = BreakerAuraStore.getInstance().pickedKeys().size();
        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Clear Picked (" + picked + ")"), btn -> {
                    com.killer560.hub.dungeonextras.BreakerAuraFeature.clearSelection();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18)
                .build());
        y += 22;

        if (!cfg.isBreakerAuraEnabledRaw()) {
            return widgets;
        }

        // The slider spans 1.0 to the MEASURED limit, not to 5.5. It ran to 5.5, so the top of its travel
        // asked the server for a break it refuses outright - a setting that cannot work is not a setting.
        final double reachMin = 1.0;
        final double reachSpan = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH - reachMin;
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, reachText(cfg),
                (cfg.getBreakerAuraReach() - reachMin) / reachSpan) {
            @Override
            protected void updateMessage() {
                setMessage(reachText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBreakerAuraReach(Math.round((reachMin + this.value * reachSpan) * 10.0) / 10.0);
                cfg.save();
            }
        });
        // Cooldown beside Reach, then the switches two to a row. Every row steps down by exactly one row height and
        // nothing ever steps back up: the old y += 20 ... y -= 20 side-steps left two pairs of controls on one
        // rectangle, where the pane draws the later one but hands every press to the earlier one (2026-10-05,
        // testkit 386-ui-sliders).
        //
        // 0..20, not 1..20. setBreakerAuraCooldownTicks was widened to accept the field's own default of 0
        // (no cooldown) but this slider was left mapping onto 1..20, so the GUI half of that bug survived it:
        // 0 was unreachable, and on a fresh config the start position was (0 - 1) / 19 = -0.05, off the
        // widget's own scale, so the knob sat pinned left reading "0 ticks" and the first drag silently threw
        // the shipped default away for good.
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, cooldownText(cfg),
                cfg.getBreakerAuraCooldownTicks() / 20.0) {
            @Override
            protected void updateMessage() {
                setMessage(cooldownText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBreakerAuraCooldownTicks((int) Math.round(this.value * 20));
                cfg.save();
            }
        });
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Zero Ping", cfg.isBreakerAuraZeroPingRaw()), btn -> {
                    cfg.setBreakerAuraZeroPing(!cfg.isBreakerAuraZeroPingRaw());
                    cfg.save();
                    btn.setMessage(onOff("Zero Ping", cfg.isBreakerAuraZeroPingRaw()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Multi Break", cfg.isBreakerAuraMultiBreak()),
                btn -> {
                    cfg.setBreakerAuraMultiBreak(!cfg.isBreakerAuraMultiBreak());
                    cfg.save();
                    btn.setMessage(onOff("Multi Break", cfg.isBreakerAuraMultiBreak()));
                }).bounds(col2bX, y, col2W, 18)
                .build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Edit Mode", cfg.isBreakerAuraEditMode()),
                btn -> {
                    cfg.setBreakerAuraEditMode(!cfg.isBreakerAuraEditMode());
                    cfg.save();
                    btn.setMessage(onOff("Edit Mode", cfg.isBreakerAuraEditMode()));
                }).bounds(contentX, y, col2W, 18)
                .build());
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

    /** Mirrors {@code Ap3Tab#openFolder} - a fresh install has no folder yet, and opening a missing directory
     *  does nothing at all, silently, so it is created first. */
    private static void openFolder() {
        try {
            Path dir = BreakerAuraStore.directory();
            Files.createDirectories(dir);
            ExternalOpen.path(dir);
        } catch (Exception ignored) {
        }
    }
}
