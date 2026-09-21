package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.social.BestFriendsConfig;
import com.killer560.hub.social.BestFriendsScreen;
import com.killer560.hub.social.BestFriendsStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Party Time Tracker settings - see {@link com.killer560.hub.social.BestFriendsTracker}. New tab, off by
 *  default per the 2026-09-21 rule for every untested feature. */
public class BestFriendsTab extends BaseTab {

    public BestFriendsTab() {
        super("Best Friends");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        BestFriendsConfig cfg = BestFriendsConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Party Time Tracker", cfg.getEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.getEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.getEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(sortText(cfg), btn -> {
                    cfg.setSortMode(cfg.getSortMode().next());
                    cfg.save();
                    btn.setMessage(sortText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Dungeon Only Filter", cfg.isDungeonOnlyFilter()), btn -> {
                    cfg.setDungeonOnlyFilter(!cfg.isDungeonOnlyFilter());
                    cfg.save();
                    btn.setMessage(onOff("Dungeon Only Filter", cfg.isDungeonOnlyFilter()));
                }).bounds(col2X, y, colW, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Best Friends (/bestfriends)"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new BestFriendsScreen(client.screen));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        int tracked = BestFriendsStore.records().size();
        widgets.add(new net.minecraft.client.gui.components.StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7" + tracked + " player" + (tracked == 1 ? "" : "s") + " tracked so far."),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static Component sortText(BestFriendsConfig cfg) {
        return Component.literal("Sort: §b" + cfg.getSortMode().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
