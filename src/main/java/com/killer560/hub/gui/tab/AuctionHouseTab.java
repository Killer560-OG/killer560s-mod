package com.killer560.hub.gui.tab;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.AuctionHouseApi;
import com.killer560.hub.auction.AuctionHouseConfig;
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
    /** True while the Hypixel Menu Key button is waiting for a key (the other capture is the Open Key). */
    private boolean capturingVanillaKey = false;

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
                    capturingVanillaKey = false;
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

        // The unified Auction House (2026-10-07): Hypixel's real AH menus drawn in the browser's look.
        AuctionHouseConfig ah = AuctionHouseConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Reskin Real Auction House", ah.isReskinRealAhRaw()), btn -> {
                    ah.setReskinRealAh(!ah.isReskinRealAhRaw());
                    ah.save();
                    btn.setMessage(onOff("Reskin Real Auction House", ah.isReskinRealAhRaw()));
                }).bounds(contentX, y, colW, 18).build());
        Component vanillaLabel = capturingVanillaKey ? Component.literal("Press any key...") : vanillaKeyText(ah);
        widgets.add(SettingsButtonWidget.builder(vanillaLabel, btn -> {
                    capturingVanillaKey = true;
                    capturingKey = false;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Recent Searches & Items", ah.isTrackRecents()), btn -> {
                    ah.setTrackRecents(!ah.isTrackRecents());
                    ah.save();
                    btn.setMessage(onOff("Recent Searches & Items", ah.isTrackRecents()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Clear Recents"), btn -> {
                    ah.clearRecents();
                    ah.save();
                }).bounds(colBX, y, colW, 18).build());
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

    private static Component vanillaKeyText(AuctionHouseConfig ah) {
        String name = ah.getVanillaKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(ah.getVanillaKeyCode()).getDisplayName().getString();
        return Component.literal("Hypixel Menu Key: §b" + name);
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey || capturingVanillaKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturingVanillaKey) {
            AuctionHouseConfig ah = AuctionHouseConfig.getInstance();
            ah.setVanillaKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
            capturingVanillaKey = false;
            ah.save();
            return;
        }
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setOpenAhKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingKey = false;
        cfg.save();
    }
}
