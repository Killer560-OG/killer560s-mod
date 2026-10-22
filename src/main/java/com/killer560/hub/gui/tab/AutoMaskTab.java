package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Mask - the cheat-build mask/Phoenix auto-swap ({@link com.killer560.hub.maskinvincibility.AutoMask} deciding,
 *  {@link com.killer560.hub.maskinvincibility.MaskSwapper} swapping), moved out of {@link MaskInvincibilityTab}'s
 *  "Cheat Build - Auto Mask" divider into its own section on 2026-10-04, per killer560: "Move auto mask into its own
 *  section as well." Only added to {@link DungeonTab} on the cheat build, so its header is red ({@link #isCheatOnly()}).
 *  It reacts to the procs Mask Invincibility detects and reads its cooldowns, so it still needs that switched on.
 *  <p>
 *  Rows (killer560, 2026-10-16): the switch, Mode (Keybind / Full Auto), the Swap Key or a line saying what Full Auto
 *  does, Swap Order, Dungeons Only, Boss Only, then the swap's own Step Delay (ticks), Phoenix Route and Return To Slot. */
public class AutoMaskTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing = false;

    public AutoMaskTab() {
        super("Auto Mask");
    }

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
        applyCapture(keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        applyCapture(KeyUtil.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        if (capturing) {
            cfg.setSwapKey(code);
            cfg.save();
        }
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        int y = contentY;
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        int col1 = contentX;
        int gap = 8;
        int half = (contentWidth - gap) / 2;

        // Auto Mask as a whole (killer560, 2026-10-13) - first, so it is the card's switch even while Mask Invincibility is off.
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Mask", cfg.isAutoSwapEnabled()), btn -> {
                    cfg.setAutoSwapEnabled(!cfg.isAutoSwapEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col1, y, 100, 18).build());
        y += 22;

        if (!cfg.isAutoSwapEnabled()) {
            return widgets;
        }

        if (!cfg.isEnabled()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Turn on Mask Invincibility in its own section to use this."),
                    Minecraft.getInstance().font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(modeLabel(cfg), (btn, dir) -> {
                    cfg.setAutoMaskMode(SettingsButtonWidget.step(cfg.getAutoMaskMode(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, half, 18).build());
        y += 22;

        if (cfg.getAutoMaskMode() == MaskInvincibilityConfig.AutoMaskMode.KEYBIND) {
            widgets.add(SettingsButtonWidget.builder(swapKeyLabel(cfg), btn -> {
                        capturing = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, half, 18).build());
            y += 22;
        } else {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Swaps once the worn mask or Phoenix procs."),
                    Minecraft.getInstance().font));
            y += 16;
        }

        widgets.add(SettingsButtonWidget.cycling(swapOrderLabel(cfg), (btn, dir) -> {
                    cfg.setSwapOrder(SettingsButtonWidget.step(cfg.getSwapOrder(), dir));
                    cfg.save();
                    btn.setMessage(swapOrderLabel(cfg));
                }).bounds(contentX, y, half, 18).build());
        y += 22;

        // killer560, 2026-10-18: which masks Auto Mask may swap to. Off skips that one in both modes, wherever it sits
        // in the Swap Order. Separate from the HUD Spirit/Bonzo/Phoenix rows on Mask Invincibility.
        widgets.add(SettingsButtonWidget.builder(onOff("Swap To Spirit", cfg.isAutoSwapSpirit()), btn -> {
                    cfg.setAutoSwapSpirit(!cfg.isAutoSwapSpirit());
                    cfg.save();
                    btn.setMessage(onOff("Swap To Spirit", cfg.isAutoSwapSpirit()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("Swap To Bonzo", cfg.isAutoSwapBonzo()), btn -> {
                    cfg.setAutoSwapBonzo(!cfg.isAutoSwapBonzo());
                    cfg.save();
                    btn.setMessage(onOff("Swap To Bonzo", cfg.isAutoSwapBonzo()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("Swap To Phoenix", cfg.isAutoSwapPhoenix()), btn -> {
                    cfg.setAutoSwapPhoenix(!cfg.isAutoSwapPhoenix());
                    cfg.save();
                    btn.setMessage(onOff("Swap To Phoenix", cfg.isAutoSwapPhoenix()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Dungeons Only", cfg.isAutoMaskDungeonsOnly()), btn -> {
                    cfg.setAutoMaskDungeonsOnly(!cfg.isAutoMaskDungeonsOnly());
                    cfg.save();
                    btn.setMessage(onOff("Dungeons Only", cfg.isAutoMaskDungeonsOnly()));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Boss Only", cfg.isAutoMaskBossOnly()), btn -> {
                    cfg.setAutoMaskBossOnly(!cfg.isAutoMaskBossOnly());
                    cfg.save();
                    btn.setMessage(onOff("Boss Only", cfg.isAutoMaskBossOnly()));
                }).bounds(contentX + half + gap, y, half, 18).build());
        y += 22;

        int range = MaskInvincibilityConfig.MAX_STEP_DELAY_TICKS - MaskInvincibilityConfig.MIN_STEP_DELAY_TICKS;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, stepDelayLabel(cfg),
                (cfg.getSwapStepDelayTicks() - MaskInvincibilityConfig.MIN_STEP_DELAY_TICKS) / (double) range) {
            @Override
            protected void updateMessage() {
                setMessage(stepDelayLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setSwapStepDelayTicks(MaskInvincibilityConfig.MIN_STEP_DELAY_TICKS
                        + (int) Math.round(this.value * range));
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 22;

        widgets.add(SettingsButtonWidget.cycling(phoenixRouteLabel(cfg), (btn, dir) -> {
                    cfg.setPhoenixRoute(SettingsButtonWidget.step(cfg.getPhoenixRoute(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        // killer560 9.1: "Remove the rod text section as well it should auto detect the rod." The name-a-rod
        // text box is gone - MaskSwapper#findRodSlot now finds whatever real fishing rod is in the hotbar on
        // its own (see its javadoc), and falls back to the /pets menu route by itself if there isn't one.
        if (cfg.getPhoenixRoute() == MaskInvincibilityConfig.PhoenixRoute.ROD) {
            widgets.add(SettingsButtonWidget.builder(onOff("Return To Slot", cfg.isRodReturnToPreviousSlot()), btn -> {
                        cfg.setRodReturnToPreviousSlot(!cfg.isRodReturnToPreviousSlot());
                        cfg.save();
                        btn.setMessage(onOff("Return To Slot", cfg.isRodReturnToPreviousSlot()));
                    }).bounds(contentX, y, 220, 18).build());
        }

        return widgets;
    }

    private static Component modeLabel(MaskInvincibilityConfig cfg) {
        return Component.literal("Mode: §b" + cfg.getAutoMaskMode().label());
    }

    private Component swapKeyLabel(MaskInvincibilityConfig cfg) {
        if (capturing) {
            return Component.literal("Press any key...");
        }
        return Component.literal("Swap Key: " + CommandKeybindsTab.bindName(cfg.getSwapKey()));
    }

    private static Component swapOrderLabel(MaskInvincibilityConfig cfg) {
        return Component.literal("Swap Order: §b" + cfg.getSwapOrder().label());
    }

    private static Component stepDelayLabel(MaskInvincibilityConfig cfg) {
        int t = cfg.getSwapStepDelayTicks();
        return Component.literal("Swap Step Delay: " + t + (t == 1 ? " tick" : " ticks")
                + String.format(" (%.2fs)", t / 20.0));
    }

    private static Component phoenixRouteLabel(MaskInvincibilityConfig cfg) {
        return Component.literal("Phoenix Route: §b"
                + (cfg.getPhoenixRoute() == MaskInvincibilityConfig.PhoenixRoute.ROD ? "Rod / Autopet" : "/pets menu"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
