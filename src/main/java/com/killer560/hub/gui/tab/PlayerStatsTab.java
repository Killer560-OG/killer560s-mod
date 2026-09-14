package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.playerstats.PlayerStatsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Player Stats HUD settings - see {@link com.killer560.hub.playerstats.PlayerStatsFeature}'s class doc
 *  for the real Odin-ported action-bar parsing this is built on. */
public class PlayerStatsTab extends BaseTab {

    public PlayerStatsTab() {
        super("Player Stats");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        PlayerStatsConfig cfg = PlayerStatsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Player Stats HUD", cfg.isEnabled()), btn -> {
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

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Reads Health/Mana/Defense from the real action bar and shows"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7them as their own always-on-screen HUD line."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
