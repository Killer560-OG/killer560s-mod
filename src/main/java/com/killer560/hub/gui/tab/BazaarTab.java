package com.killer560.hub.gui.tab;

import com.killer560.hub.auction.AuctionConfig;
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

        widgets.add(onOffButton("Bazaar Browser", cfg::isBazaarEnabledRaw, cfg::setBazaarEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isBazaarEnabledRaw()) {
            return widgets;
        }
        widgets.add(com.killer560.hub.gui.ScreenThemes.button("bazaar", contentX, y, contentWidth, 18, "INVENTORY"));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Bazaar"), btn -> BazaarFeature.openOrExplain())
                .bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(onOffButton("Track My Orders", cfg::isTrackBazaarOrders, cfg::setTrackBazaarOrders, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        com.killer560.hub.bazaar.BazaarConfig bz = com.killer560.hub.bazaar.BazaarConfig.getInstance();
        widgets.add(onOffButton("Follow-up Click", bz::isFollowUpClick, bz::setFollowUpClick, bz::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Hide HUDs in Bazaar", bz::isHideHuds, bz::setHideHuds, bz::save,
                null, colBX, y, colW, 18));
        y += 26;

        return widgets;
    }

}
