package com.killer560.hub.gui.tab;

import com.killer560.hub.rngmeter.RngMeterConfig;
import com.killer560.hub.rngmeter.RngMeterEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RNG Meter settings only - the actual rankings show as an overlay on Hypixel's own in-game RNG
 * Meter menu (see RngMeterOverlay), not as a browsable menu here.
 */
public class RngMeterTab extends BaseTab {

    private EditBox intervalField;

    public RngMeterTab() {
        super("RNG Meter Overlay");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    RngMeterConfig.getInstance().setEnabled(!RngMeterConfig.getInstance().isEnabled());
                    RngMeterConfig.getInstance().save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(pricingModeText(), btn -> {
                    RngMeterConfig.getInstance().setUseInstantBuyPrice(!RngMeterConfig.getInstance().isUseInstantBuyPrice());
                    RngMeterConfig.getInstance().save();
                    btn.setMessage(pricingModeText());
                }).bounds(contentX, y, 260, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Refresh interval (minutes, starts after each refresh finishes):"),
                Minecraft.getInstance().font));
        y += 12;

        intervalField = new EditBox(Minecraft.getInstance().font, contentX, y, 60, 20, Component.literal("Minutes"));
        intervalField.setMaxLength(3);
        intervalField.setValue(String.valueOf(RngMeterConfig.getInstance().getRefreshIntervalMinutes()));
        widgets.add(intervalField);

        widgets.add(SettingsButtonWidget.builder(Component.literal("Save"), btn -> {
                    try {
                        int minutes = Integer.parseInt(intervalField.getValue());
                        RngMeterConfig.getInstance().setRefreshIntervalMinutes(minutes);
                        RngMeterConfig.getInstance().save();
                    } catch (NumberFormatException ignored) {
                    }
                }).bounds(contentX + 66, y, 70, 20).build());
        y += 30;

        var prices = RngMeterEngine.PRICES;
        widgets.add(SettingsButtonWidget.builder(prices.isRefreshing() ? Component.literal("Refreshing...") : Component.literal("Refresh Now"), btn -> {
                    RngMeterEngine.PRICES.refreshAsync();
                    requestRebuild.run();
                }).bounds(contentX, y, 140, 20).build());
        y += 26;

        String lastRefreshed = prices.getLastRefreshedAtMs() == 0 ? "never"
                : (System.currentTimeMillis() - prices.getLastRefreshedAtMs()) / 1000 + "s ago";
        String status = prices.isRefreshing()
                ? String.format("§7Scanning... Bazaar: %d prices, AH: %d prices so far (AH scans the whole "
                        + "auction house page by page, this can take a bit).", prices.getBazaarPriceCount(), prices.getAhPriceCount())
                : prices.getLastError() != null
                ? "§cLast refresh error: " + prices.getLastError()
                : String.format("§aLast refreshed %s. Bazaar: %d prices, AH: %d prices.",
                        lastRefreshed, prices.getBazaarPriceCount(), prices.getAhPriceCount());
        widgets.add(new StringWidget(contentX, y, contentWidth, 20, Component.literal(status),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("RNG Meter Overlay Enabled: " + (RngMeterConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component pricingModeText() {
        boolean buy = RngMeterConfig.getInstance().isUseInstantBuyPrice();
        return Component.literal("Bazaar Pricing: §b" + (buy ? "Instant Buy" : "Instant Sell"));
    }
}
