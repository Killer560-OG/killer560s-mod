package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.playerstats.PlayerStatsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Stat Bars settings (renamed from "Player Stats", killer560 2026-09-21) - see
 *  {@link com.killer560.hub.playerstats.PlayerStatsFeature}'s class doc for the real Odin-ported
 *  action-bar parsing this is built on, and for how the vanilla hearts/hunger/armour/air bars get hidden. */
public class PlayerStatsTab extends BaseTab {

    public PlayerStatsTab() {
        super("Stat Bars");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        widgets.add(SettingsButtonWidget.builder(onOff("Stat Bars", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Health", cfg.isShowHealth()), btn -> {
                    cfg.setShowHealth(!cfg.isShowHealth());
                    cfg.save();
                    btn.setMessage(onOff("Show Health", cfg.isShowHealth()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Mana", cfg.isShowMana()), btn -> {
                    cfg.setShowMana(!cfg.isShowMana());
                    cfg.save();
                    btn.setMessage(onOff("Show Mana", cfg.isShowMana()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Defense", cfg.isShowDefense()), btn -> {
                    cfg.setShowDefense(!cfg.isShowDefense());
                    cfg.save();
                    btn.setMessage(onOff("Show Defense", cfg.isShowDefense()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        // ---------------- Hide Vanilla Bars ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Hide Vanilla Bars", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Hearts", cfg.isHideVanillaHearts()), btn -> {
                    cfg.setHideVanillaHearts(!cfg.isHideVanillaHearts());
                    cfg.save();
                    btn.setMessage(onOff("Hearts", cfg.isHideVanillaHearts()));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Hunger", cfg.isHideVanillaHunger()), btn -> {
                    cfg.setHideVanillaHunger(!cfg.isHideVanillaHunger());
                    cfg.save();
                    btn.setMessage(onOff("Hunger", cfg.isHideVanillaHunger()));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Armor Bar", cfg.isHideVanillaArmour()), btn -> {
                    cfg.setHideVanillaArmour(!cfg.isHideVanillaArmour());
                    cfg.save();
                    btn.setMessage(onOff("Armor Bar", cfg.isHideVanillaArmour()));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Air Bar", cfg.isHideVanillaAir()), btn -> {
                    cfg.setHideVanillaAir(!cfg.isHideVanillaAir());
                    cfg.save();
                    btn.setMessage(onOff("Air Bar", cfg.isHideVanillaAir()));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        if (cfg.isHideVanillaHearts()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Unhide Hearts In Rift", cfg.isShowHeartsInRift()), btn -> {
                        cfg.setShowHeartsInRift(!cfg.isShowHeartsInRift());
                        cfg.save();
                        btn.setMessage(onOff("Unhide Hearts In Rift", cfg.isShowHeartsInRift()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
