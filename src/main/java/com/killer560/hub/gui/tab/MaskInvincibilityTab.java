package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.maskinvincibility.MaskInvincibilityConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mask/pet invincibility timer settings - see
 *  {@link com.killer560.hub.maskinvincibility.MaskInvincibilityFeature}'s class doc for the real
 *  Odin-ported proc detection this is built on. */
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

        widgets.add(SettingsButtonWidget.builder(onOff("Announce In Chat", cfg.isAnnounceInChat()), btn -> {
                    cfg.setAnnounceInChat(!cfg.isAnnounceInChat());
                    cfg.save();
                    btn.setMessage(onOff("Announce In Chat", cfg.isAnnounceInChat()));
                }).bounds(col1, y, 220, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Bonzo's real cooldown varies slightly and is normally read from"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7the item's own tooltip - this uses a fixed 180s estimate instead."),
                Minecraft.getInstance().font));
        y += 20;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lCheat Build - Automation"), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Swap", cfg.isAutoSwapEnabled()), btn -> {
                    cfg.setAutoSwapEnabled(!cfg.isAutoSwapEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Auto Swap", cfg.isAutoSwapEnabled()));
                }).bounds(col1, y, 100, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7When Spirit/Bonzo procs, right-clicks the other one if it's in your"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7hotbar and off cooldown - real vanilla equip-swap, works mid-fight."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
