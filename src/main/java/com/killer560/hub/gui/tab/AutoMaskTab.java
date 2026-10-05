package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Mask - the cheat-build mask/Phoenix auto-swap ({@link com.killer560.hub.maskinvincibility.MaskSwapper}),
 *  moved out of {@link MaskInvincibilityTab}'s "Cheat Build - Auto Mask" divider into its own section on
 *  2026-10-04, per killer560: "Move auto mask into its own section as well." Only added to {@link DungeonTab}
 *  on the cheat build, so its header is red ({@link #isCheatOnly()}). Same fields and JSON keys as before; it
 *  reacts to the procs Mask Invincibility detects, so it still needs that switched on. */
public class AutoMaskTab extends BaseTab {

    public AutoMaskTab() {
        super("Auto Mask");
    }

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
        int y = contentY;
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        int col1 = contentX;

        if (!cfg.isEnabled()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("\u00a77Turn on Mask Invincibility in its own section to use this."),
                    Minecraft.getInstance().font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Swap", cfg.isAutoSwapEnabled()), btn -> {
                    cfg.setAutoSwapEnabled(!cfg.isAutoSwapEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col1, y, 100, 18).build());
        y += 22;

        if (!cfg.isAutoSwapEnabled()) {
            return widgets;
        }

        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, stepDelayLabel(cfg),
                (cfg.getSwapStepDelayMs() - MaskInvincibilityConfig.MIN_STEP_DELAY_MS)
                        / (double) (MaskInvincibilityConfig.MAX_STEP_DELAY_MS
                        - MaskInvincibilityConfig.MIN_STEP_DELAY_MS)) {
            @Override
            protected void updateMessage() {
                setMessage(stepDelayLabel(cfg));
            }

            @Override
            protected void applyValue() {
                int range = MaskInvincibilityConfig.MAX_STEP_DELAY_MS - MaskInvincibilityConfig.MIN_STEP_DELAY_MS;
                cfg.setSwapStepDelayMs((int) (Math.round(
                        (MaskInvincibilityConfig.MIN_STEP_DELAY_MS + this.value * range) / 25.0) * 25));
                cfg.save();
            }
        });
        y += 22;

        widgets.add(SettingsButtonWidget.builder(phoenixRouteLabel(cfg), btn -> {
                    cfg.setPhoenixRoute(cfg.getPhoenixRoute() == MaskInvincibilityConfig.PhoenixRoute.ROD
                            ? MaskInvincibilityConfig.PhoenixRoute.PETS : MaskInvincibilityConfig.PhoenixRoute.ROD);
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

    private static Component stepDelayLabel(MaskInvincibilityConfig cfg) {
        return Component.literal("Swap Step Delay: " + cfg.getSwapStepDelayMs() + "ms");
    }

    private static Component phoenixRouteLabel(MaskInvincibilityConfig cfg) {
        return Component.literal("Phoenix Route: §b"
                + (cfg.getPhoenixRoute() == MaskInvincibilityConfig.PhoenixRoute.ROD ? "Rod / Autopet" : "/pets menu"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
