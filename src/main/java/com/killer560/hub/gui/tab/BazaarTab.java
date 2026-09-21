package com.killer560.hub.gui.tab;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Bazaar Browser settings - killer560's item 8.1 ("the same for Bazaar"). See
 *  {@link com.killer560.hub.auction.BazaarFeature}. */
public class BazaarTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

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

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Bazaar"), btn -> BazaarFeature.openDeferred())
                .bounds(contentX, y, colW, 18).build());

        Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal(BazaarApi.isRefreshing() ? "Refreshing..." : "Refresh Now"),
                        btn -> BazaarApi.refreshAsync())
                .bounds(contentX, y, contentWidth, 18).build());
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

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setOpenBazaarKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingKey = false;
        cfg.save();
    }
}
