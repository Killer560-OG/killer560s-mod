package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
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

        // killer560 9.1: "add an option to only show the invulnerabilites in dungeons, and one for boss only".
        // Even halves of the tab's real content width, same fix as Announce In Chat/To Party just below.
        int gateGap = 8;
        int gateW = (contentWidth - gateGap) / 2;
        widgets.add(SettingsButtonWidget.builder(onOff("Only In Dungeons", cfg.isOnlyInDungeons()), btn -> {
                    cfg.setOnlyInDungeons(!cfg.isOnlyInDungeons());
                    cfg.save();
                    btn.setMessage(onOff("Only In Dungeons", cfg.isOnlyInDungeons()));
                }).bounds(contentX, y, gateW, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Boss Only", cfg.isBossOnly()), btn -> {
                    cfg.setBossOnly(!cfg.isBossOnly());
                    cfg.save();
                    btn.setMessage(onOff("Boss Only", cfg.isBossOnly()));
                }).bounds(contentX + gateW + gateGap, y, gateW, 18).build());
        y += 22;

        // killer560 9.1: "The announce to party text does not fit its box" - it was squeezed into the same
        // 108px third column as the short "Phoenix"-style labels while sitting next to "Announce In Chat" in
        // a 208px box, even though "Announce To Party" is the longer of the two labels. Even halves of the
        // tab's real content width fit both.
        int announceGap = 8;
        int announceW = (contentWidth - announceGap) / 2;
        widgets.add(SettingsButtonWidget.builder(onOff("Announce In Chat", cfg.isAnnounceInChat()), btn -> {
                    cfg.setAnnounceInChat(!cfg.isAnnounceInChat());
                    cfg.save();
                    btn.setMessage(onOff("Announce In Chat", cfg.isAnnounceInChat()));
                }).bounds(contentX, y, announceW, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Announce To Party", cfg.isAnnounceToParty()), btn -> {
                    cfg.setAnnounceToParty(!cfg.isAnnounceToParty());
                    cfg.save();
                    btn.setMessage(onOff("Announce To Party", cfg.isAnnounceToParty()));
                }).bounds(contentX + announceW + announceGap, y, announceW, 18).build());
        y += 24;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        // killer560 9.1: "make auto mask its own unique section" - was just the generic red cheat-build
        // divider every other cheat-only section in this mod also uses; naming it here makes it clear this
        // whole block (the toggle plus everything below it) is one distinct feature, not a shared bucket.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - Auto Mask", true), Minecraft.getInstance().font));
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
