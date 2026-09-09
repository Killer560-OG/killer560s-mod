package com.killer560.hub.gui.tab;

import com.killer560.hub.autojoinskyblock.AutoJoinSkyblockConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** General server/session behavior - a home for features that don't fit the more specific
 *  categories, starting with Auto Join Skyblock (2026-09-08). */
public class GeneralTab extends BaseTab {

    public GeneralTab() {
        super("General");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(autoJoinSkyblockText(), btn -> {
                    AutoJoinSkyblockConfig cfg = AutoJoinSkyblockConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(autoJoinSkyblockText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Sends /skyblock a few seconds after you first connect to"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Hypixel. Won't fire if you send any command yourself first"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("(e.g. /lobby), and won't re-trigger on /lobby afterwards."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component autoJoinSkyblockText() {
        return Component.literal("Auto Join Skyblock: "
                + (AutoJoinSkyblockConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
