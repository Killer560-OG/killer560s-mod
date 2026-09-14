package com.killer560.hub.gui.tab;

import com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Etherwarp Overlay settings - see {@link com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature}'s
 *  class doc for the real Odin-ported landing-prediction this is built on, and why the client-side
 *  predicted-execution half of Odin's own feature was deliberately left out. */
public class EtherwarpOverlayTab extends BaseTab {

    public EtherwarpOverlayTab() {
        super("Etherwarp Overlay");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        EtherwarpOverlayConfig cfg = EtherwarpOverlayConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Etherwarp Overlay", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show When Failed", cfg.isShowWhenFailed()), btn -> {
                    cfg.setShowWhenFailed(!cfg.isShowWhenFailed());
                    cfg.save();
                    btn.setMessage(onOff("Show When Failed", cfg.isShowWhenFailed()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Full Block Box", cfg.isFullBlock()), btn -> {
                    cfg.setFullBlock(!cfg.isFullBlock());
                    cfg.save();
                    btn.setMessage(onOff("Full Block Box", cfg.isFullBlock()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7While holding a real Etherwarp item (shift for the enchant,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7no shift needed for the Conduit), highlights where you'd land -"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7green if safe, red if not. Never moves you - the real server"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7still handles the actual Etherwarp exactly as normal."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
