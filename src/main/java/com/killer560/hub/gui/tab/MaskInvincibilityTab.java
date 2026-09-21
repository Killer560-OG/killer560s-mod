package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mask/pet invincibility timer settings - see
 *  {@link com.killer560.hub.maskinvincibility.MaskInvincibilityFeature}'s class doc for the real
 *  Odin-ported proc detection this is built on, and {@link com.killer560.hub.maskinvincibility.MaskSwapper}
 *  for the cheat-build auto-swap. */
public class MaskInvincibilityTab extends BaseTab {

    public MaskInvincibilityTab() {
        super("Mask Invincibility");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        MaskInvincibilityConfig cfg = MaskInvincibilityConfig.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff("Mask Invincibility", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Spirit", cfg.isShowSpirit()), btn -> {
                    cfg.setShowSpirit(!cfg.isShowSpirit());
                    cfg.save();
                    btn.setMessage(onOff("Spirit", cfg.isShowSpirit()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Bonzo", cfg.isShowBonzo()), btn -> {
                    cfg.setShowBonzo(!cfg.isShowBonzo());
                    cfg.save();
                    btn.setMessage(onOff("Bonzo", cfg.isShowBonzo()));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Phoenix", cfg.isShowPhoenix()), btn -> {
                    cfg.setShowPhoenix(!cfg.isShowPhoenix());
                    cfg.save();
                    btn.setMessage(onOff("Phoenix", cfg.isShowPhoenix()));
                }).bounds(col3, y, 108, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Item Icons", cfg.isShowItemIcons()), btn -> {
                    cfg.setShowItemIcons(!cfg.isShowItemIcons());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col1, y, 100, 18).build());

        if (cfg.isShowItemIcons()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Hide Mask Names", cfg.isHideMaskNames()), btn -> {
                        cfg.setHideMaskNames(!cfg.isHideMaskNames());
                        cfg.save();
                        btn.setMessage(onOff("Hide Mask Names", cfg.isHideMaskNames()));
                    }).bounds(col2, y, 208, 18).build());
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Announce In Chat", cfg.isAnnounceInChat()), btn -> {
                    cfg.setAnnounceInChat(!cfg.isAnnounceInChat());
                    cfg.save();
                    btn.setMessage(onOff("Announce In Chat", cfg.isAnnounceInChat()));
                }).bounds(col1, y, 208, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Announce To Party", cfg.isAnnounceToParty()), btn -> {
                    cfg.setAnnounceToParty(!cfg.isAnnounceToParty());
                    cfg.save();
                    btn.setMessage(onOff("Announce To Party", cfg.isAnnounceToParty()));
                }).bounds(contentX + 216, y, 108, 18).build());
        y += 24;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - Automation", true), Minecraft.getInstance().font));
        y += 14;

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

        if (cfg.getPhoenixRoute() == MaskInvincibilityConfig.PhoenixRoute.ROD) {
            EditBox rod = new EditBox(Minecraft.getInstance().font, contentX, y, 208, 18,
                    Component.literal("Phoenix Rod"));
            rod.setMaxLength(64);
            rod.setHint(Component.literal("Rod name (part of it is enough)"));
            rod.setValue(cfg.getPhoenixRodName());
            rod.setResponder(text -> {
                cfg.setPhoenixRodName(text);
                cfg.save();
            });
            widgets.add(rod);

            widgets.add(SettingsButtonWidget.builder(onOff("Return To Slot", cfg.isRodReturnToPreviousSlot()), btn -> {
                        cfg.setRodReturnToPreviousSlot(!cfg.isRodReturnToPreviousSlot());
                        cfg.save();
                        btn.setMessage(onOff("Return To Slot", cfg.isRodReturnToPreviousSlot()));
                    }).bounds(contentX + 216, y, 108, 18).build());
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
