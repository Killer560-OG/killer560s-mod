package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.pricetooltip.PriceTooltipConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Price Tooltip settings - see {@link com.killer560.hub.pricetooltip.PriceTooltipFeature}. Ships OFF; every line
 *  defaults on under the switch. */
public class PriceTooltipTab extends BaseTab {

    private static final int[] REFRESH_STEPS = {1, 2, 5, 10, 15, 30};

    public PriceTooltipTab() {
        super("Price Tooltip");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PriceTooltipConfig cfg = PriceTooltipConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Price Tooltip", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Lines", false));
        y += 16;
        widgets.add(toggle(contentX, y, half, "NPC Sell Price", cfg::isNpcPrice, cfg::setNpcPrice, cfg));
        widgets.add(toggle(col2, y, half, "Bazaar Buy/Sell", cfg::isBazaar, cfg::setBazaar, cfg));
        y += 22;
        widgets.add(toggle(contentX, y, half, "Lowest BIN", cfg::isLowestBin, cfg::setLowestBin, cfg));
        widgets.add(toggle(col2, y, half, "Average BIN", cfg::isAverageBin, cfg::setAverageBin, cfg));
        y += 22;
        widgets.add(toggle(contentX, y, half, "Est. Item Value", cfg::isEstimatedValue, cfg::setEstimatedValue, cfg));
        widgets.add(toggle(col2, y, half, "Obtained Date", cfg::isObtained, cfg::setObtained, cfg));
        y += 22;
        widgets.add(toggle(contentX, y, half, "Museum Status", cfg::isMuseum, cfg::setMuseum, cfg));
        widgets.add(SettingsButtonWidget.cycling(daysText(cfg), (btn, dir) -> {
                    cfg.setAverageDays(cfg.getAverageDays() == 1 ? 3 : 1);
                    cfg.save();
                    btn.setMessage(daysText(cfg));
                    PriceData.refreshAsync();
                }).bounds(col2, y, half, 18).build());
        y += 26;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Display", false));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(stackText(cfg), (btn, dir) -> {
                    cfg.setStackPrice(SettingsButtonWidget.step(cfg.getStackPrice(), dir));
                    cfg.save();
                    btn.setMessage(stackText(cfg));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.cycling(breakdownText(cfg), (btn, dir) -> {
                    cfg.setBreakdown(SettingsButtonWidget.step(cfg.getBreakdown(), dir));
                    cfg.save();
                    btn.setMessage(breakdownText(cfg));
                }).bounds(col2, y, half, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.cycling(valueModeText(cfg), (btn, dir) -> {
                    cfg.setValueMode(SettingsButtonWidget.step(cfg.getValueMode(), dir));
                    cfg.save();
                    btn.setMessage(valueModeText(cfg));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.cycling(formatText(cfg), (btn, dir) -> {
                    cfg.setNumberFormat(SettingsButtonWidget.step(cfg.getNumberFormat(), dir));
                    cfg.save();
                    btn.setMessage(formatText(cfg));
                }).bounds(col2, y, half, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.cycling(refreshText(cfg), (btn, dir) -> {
                    int idx = 0;
                    for (int i = 0; i < REFRESH_STEPS.length; i++) {
                        if (REFRESH_STEPS[i] <= cfg.getRefreshMinutes()) {
                            idx = i;
                        }
                    }
                    cfg.setRefreshMinutes(REFRESH_STEPS[SettingsButtonWidget.step(idx, dir, REFRESH_STEPS.length)]);
                    cfg.save();
                    btn.setMessage(refreshText(cfg));
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh Prices"), btn -> PriceData.refreshAsync())
                .bounds(col2, y, half, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(status()), mc.font));
        return widgets;
    }

    private static String status() {
        if (PriceData.isRefreshing()) {
            return "§7Fetching prices...";
        }
        long at = PriceData.pricesFetchedAt();
        if (at <= 0) {
            return "§7No prices yet: hover a Skyblock item to start.";
        }
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000L);
        String err = PriceData.lastError();
        return "§7" + PriceData.lowestBinCount() + " BIN, " + PriceData.bazaarCount() + " Bazaar, updated "
                + (min == 0 ? "just now" : min + " min ago") + (err == null ? "" : " §c(" + err + ")");
    }

    private static AbstractWidget toggle(int x, int y, int w, String label, Supplier<Boolean> get, Consumer<Boolean> set,
                                         PriceTooltipConfig cfg) {
        return SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
            set.accept(!get.get());
            cfg.save();
            btn.setMessage(onOff(label, get.get()));
        }).bounds(x, y, w, 18).build();
    }

    private static Component daysText(PriceTooltipConfig cfg) {
        return Component.literal("Average Over: " + cfg.getAverageDays() + (cfg.getAverageDays() == 1 ? " Day" : " Days"));
    }

    private static Component stackText(PriceTooltipConfig cfg) {
        return Component.literal("Stack Price: " + cfg.getStackPrice().label);
    }

    private static Component breakdownText(PriceTooltipConfig cfg) {
        return Component.literal("Value Breakdown: " + cfg.getBreakdown().label);
    }

    private static Component valueModeText(PriceTooltipConfig cfg) {
        return Component.literal("Value Mode: " + cfg.getValueMode().label);
    }

    private static Component formatText(PriceTooltipConfig cfg) {
        return Component.literal("Number Format: " + cfg.getNumberFormat().label);
    }

    private static Component refreshText(PriceTooltipConfig cfg) {
        return Component.literal("Refresh Every: " + cfg.getRefreshMinutes() + " min");
    }
}
