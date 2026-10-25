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
import com.killer560.hub.compat.McCompat;

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

        widgets.add(SettingsButtonWidget.builder(onOff("Track Party Time", cfg.getEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.getEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.getEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(sortText(cfg), (btn, dir) -> {
                    cfg.setSortMode(dir > 0 ? cfg.getSortMode().next() : cfg.getSortMode().previous());
                    cfg.save();
                    btn.setMessage(sortText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        y += 24;

        widgets.add(com.killer560.hub.gui.ScreenThemes.button(com.killer560.hub.social.BestFriendsScreen.THEME_ID, contentX, y, colW, 18));
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Best Friends (/bestfriends)"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new BestFriendsScreen(McCompat.screen(client)));
                }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }

    private static Component sortText(BestFriendsConfig cfg) {
        return Component.literal("Sort: §b" + cfg.getSortMode().label);
    }
}
