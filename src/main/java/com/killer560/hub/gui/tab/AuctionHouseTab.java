package com.killer560.hub.gui.tab;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.AuctionHouseFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auction House Browser + Listing Helper settings - killer560's item 8.1. See
 *  {@link com.killer560.hub.auction.AuctionHouseFeature} and
 *  {@link com.killer560.hub.auction.ListingHelperFeature}. Explanatory text lives in tooltips (hover each
 *  control) per the mod-wide "no in-panel paragraphs" rule; only live status stays inline. */
public class AuctionHouseTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public AuctionHouseTab() {
        super("Auction House");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        AuctionConfig cfg = AuctionConfig.getInstance();
        int y = contentY;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Auction House Browser", cfg.isAhEnabledRaw()), btn -> {
                    cfg.setAhEnabled(!cfg.isAhEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isAhEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Auction House"), btn -> AuctionHouseFeature.openDeferred())
                .bounds(contentX, y, colW, 18).build());

        Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("/ah Override", cfg.isOverrideAhCommand()), btn -> {
                    cfg.setOverrideAhCommand(!cfg.isOverrideAhCommand());
                    cfg.save();
                    btn.setMessage(onOff("/ah Override", cfg.isOverrideAhCommand()));
                }).bounds(contentX, y, colW, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal(AuctionHouseApi.isScanning() ? "Refreshing..." : "Refresh Now"),
                        btn -> AuctionHouseApi.refreshAsync())
                .bounds(colBX, y, colW, 18).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(onOff("Listing Helper", cfg.isListingHelperEnabledRaw()), btn -> {
                    cfg.setListingHelperEnabled(!cfg.isListingHelperEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Listing Helper", cfg.isListingHelperEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component keyText(AuctionConfig cfg) {
        String name = cfg.getOpenAhKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getOpenAhKeyCode()).getDisplayName().getString();
        return Component.literal("Open Key: §b" + name);
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setOpenAhKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingKey = false;
        cfg.save();
    }
}
