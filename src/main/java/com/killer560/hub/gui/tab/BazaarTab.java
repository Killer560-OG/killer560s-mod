package com.killer560.hub.gui.tab;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.bazaar.BazaarFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Bazaar Browser settings - killer560's item 8.1 ("the same for Bazaar"). See
 *  {@link com.killer560.hub.bazaar.BazaarFeature}. */
public class BazaarTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;
    /** Which key the capture is for: the browser's open key, or the hold-for-Hypixel's-menu key. */
    private boolean capturingVanillaKey = false;

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

        // The reskin works on Hypixel's real menu, so it stands apart from the browser's own switch.
        widgets.add(SettingsButtonWidget.builder(onOff("Reskin Real Bazaar", cfg.isReskinRealBazaarRaw()), btn -> {
                    cfg.setReskinRealBazaar(!cfg.isReskinRealBazaarRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.isReskinRealBazaarRaw()) {
            Component vanillaLabel = capturingKey && capturingVanillaKey ? Component.literal("Press any key...")
                    : vanillaKeyText(cfg);
            widgets.add(SettingsButtonWidget.builder(vanillaLabel, btn -> {
                        capturingKey = true;
                        capturingVanillaKey = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(colBX, y, colW, 18).build());
        }
        y += 26;

        if (!cfg.isBazaarEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Bazaar"), btn -> BazaarFeature.openOrExplain())
                .bounds(contentX, y, colW, 18).build());

        Component keyLabel = capturingKey && !capturingVanillaKey ? Component.literal("Press any key...") : keyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingKey = true;
                    capturingVanillaKey = false;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal(BazaarApi.isRefreshing() ? "Refreshing..." : "Refresh Now"),
                        btn -> BazaarApi.refreshAsync())
                .bounds(contentX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Track My Orders", cfg.isTrackBazaarOrders()), btn -> {
                    cfg.setTrackBazaarOrders(!cfg.isTrackBazaarOrders());
                    cfg.save();
                    btn.setMessage(onOff("Track My Orders", cfg.isTrackBazaarOrders()));
                }).bounds(contentX, y, colW, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Manage Orders"),
                        btn -> BazaarFeature.openManageOrders())
                .bounds(colBX, y, colW, 18).build());
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

        int count = BazaarApi.getProducts().size();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Cached products: " + (count > 0 ? String.valueOf(count) : "none yet")
                        + (BazaarApi.isRefreshing() ? " (refreshing...)" : "")),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component keyText(AuctionConfig cfg) {
        String name = cfg.getOpenBazaarKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getOpenBazaarKeyCode()).getDisplayName().getString();
        return Component.literal("Open Key: §b" + name);
    }

    private static Component vanillaKeyText(AuctionConfig cfg) {
        String name = cfg.getBazaarVanillaKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getBazaarVanillaKeyCode()).getDisplayName().getString();
        return Component.literal("Hypixel Menu Key: §b" + name);
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        if (capturingVanillaKey) {
            cfg.setBazaarVanillaKeyCode(code);
        } else {
            cfg.setOpenBazaarKeyCode(code);
        }
        capturingKey = false;
        capturingVanillaKey = false;
        cfg.save();
    }
}
