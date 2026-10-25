package com.killer560.hub.gui.tab;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.bazaar.BazaarFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Bazaar Browser settings - killer560's item 8.1 ("the same for Bazaar"). See
 *  {@link com.killer560.hub.bazaar.BazaarFeature}. */
public class BazaarTab extends BaseTab {

    public BazaarTab() {
        super("Bazaar");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        AuctionConfig cfg = AuctionConfig.getInstance();
        int y = contentY;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Bazaar Browser", cfg.isBazaarEnabledRaw()), btn -> {
                    cfg.setBazaarEnabled(!cfg.isBazaarEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isBazaarEnabledRaw()) {
            return widgets;
        }
        widgets.add(com.killer560.hub.gui.ScreenThemes.button("bazaar", contentX, y, contentWidth, 18, "INVENTORY"));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Bazaar"), btn -> BazaarFeature.openOrExplain())
                .bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Track My Orders", cfg.isTrackBazaarOrders()), btn -> {
                    cfg.setTrackBazaarOrders(!cfg.isTrackBazaarOrders());
                    cfg.save();
                    btn.setMessage(onOff("Track My Orders", cfg.isTrackBazaarOrders()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        com.killer560.hub.bazaar.BazaarConfig bz = com.killer560.hub.bazaar.BazaarConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Follow-up Click", bz.isFollowUpClick()), btn -> {
                    bz.setFollowUpClick(!bz.isFollowUpClick());
                    bz.save();
                    btn.setMessage(onOff("Follow-up Click", bz.isFollowUpClick()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Hide HUDs in Bazaar", bz.isHideHuds()), btn -> {
                    bz.setHideHuds(!bz.isHideHuds());
                    bz.save();
                    btn.setMessage(onOff("Hide HUDs in Bazaar", bz.isHideHuds()));
                }).bounds(colBX, y, colW, 18).build());
        y += 26;

        return widgets;
    }

}
