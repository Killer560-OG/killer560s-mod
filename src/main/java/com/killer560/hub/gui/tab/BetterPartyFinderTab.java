package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.partyfinder.BetterPartyFinderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Better Party Finder settings - see
 *  {@link com.killer560.hub.partyfinder.BetterPartyFinderFeature}'s class doc for the real Odin-ported
 *  stats display this is built on, and why Auto Kick was deliberately left out. */
public class BetterPartyFinderTab extends BaseTab {

    public BetterPartyFinderTab() {
        super("Better Party Finder");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BetterPartyFinderConfig cfg = BetterPartyFinderConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Better Party Finder", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Kick Button", cfg.isShowKickButton()), btn -> {
                    cfg.setShowKickButton(!cfg.isShowKickButton());
                    cfg.save();
                    btn.setMessage(onOff("Show Kick Button", cfg.isShowKickButton()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Shows Catacombs level + secrets when someone joins your real"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Party Finder group, with a clickable Kick button. No Auto Kick -"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7kicking always needs your own real click."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
