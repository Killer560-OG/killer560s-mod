package com.killer560.hub.gui.tab;

import com.killer560.hub.croesus.AutoCroesusFeature;
import com.killer560.hub.croesus.CroesusConfig;
import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Chest Profit overlay, Croesus Profit Logger totals, and (cheat build only) Auto Croesus settings -
 *  see {@link com.killer560.hub.croesus.ChestProfitFeature}, {@link CroesusProfitLog}, {@link AutoCroesusFeature}. */
public class CroesusTab extends BaseTab {

    private static final int GAP = 8;

    public CroesusTab() {
        super("Croesus / Chest Profit");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CroesusConfig cfg = CroesusConfig.getInstance();
        int y = contentY;
        int half = (contentWidth - GAP) / 2;

        // ---- Chest Profit ----
        widgets.add(SettingsButtonWidget.builder(onOff("Chest Profit", cfg.isChestProfitEnabled()), btn -> {
                    cfg.setChestProfitEnabled(!cfg.isChestProfitEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        widgets.add(label(contentX, y, contentWidth, "§7Value, cost and profit next to any dungeon reward chest; best chest"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7highlighted green in the Croesus run view. Prices: RNG Meter's Bazaar/AH feed."));
        y += 16;
        if (cfg.isChestProfitEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Include Essence", cfg.isIncludeEssence()), btn -> {
                        cfg.setIncludeEssence(!cfg.isIncludeEssence());
                        cfg.save();
                        btn.setMessage(onOff("Include Essence", cfg.isIncludeEssence()));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Best", cfg.isHighlightBest()), btn -> {
                        cfg.setHighlightBest(!cfg.isHighlightBest());
                        cfg.save();
                        btn.setMessage(onOff("Highlight Best", cfg.isHighlightBest()));
                    }).bounds(contentX + half + GAP, y, half, 18).build());
            y += 26;
        }

        // ---- Profit Logger ----
        widgets.add(SettingsButtonWidget.builder(onOff("Croesus Profit Logger", cfg.isLoggerEnabled()), btn -> {
                    cfg.setLoggerEnabled(!cfg.isLoggerEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        widgets.add(label(contentX, y, contentWidth, "§7Logs every claimed chest to config/killer560smod-croesus-log.json."));
        y += 16;
        if (cfg.isLoggerEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Chat Summary", cfg.isLoggerChatSummary()), btn -> {
                        cfg.setLoggerChatSummary(!cfg.isLoggerChatSummary());
                        cfg.save();
                        btn.setMessage(onOff("Chat Summary", cfg.isLoggerChatSummary()));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("Reset totals"), btn -> {
                        CroesusProfitLog.resetTotals();
                        requestRebuild.run();
                    }).bounds(contentX + half + GAP, y, half, 18).build());
            y += 24;
            y = totalsSection(widgets, contentX, y, contentWidth, "Session", CroesusProfitLog.session());
            y = totalsSection(widgets, contentX, y, contentWidth, "All-time", CroesusProfitLog.allTime());
            widgets.add(label(contentX, y, contentWidth, "§7" + CroesusProfitLog.entryCount() + " claims in the log file."));
            y += 16;
        }

        // ---- Auto Croesus (cheat build only) ----
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(SettingsButtonWidget.builder(onOff("Auto Croesus", cfg.getAutoCroesusEnabledRaw()), btn -> {
                        cfg.setAutoCroesusEnabled(!cfg.getAutoCroesusEnabledRaw());
                        cfg.save();
                        if (!cfg.getAutoCroesusEnabledRaw()) {
                            AutoCroesusFeature.requestStop();
                        }
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 22;
            widgets.add(label(contentX, y, contentWidth, "§7Open Croesus yourself: claims the best chest of every unopened run"));
            y += 12;
            widgets.add(label(contentX, y, contentWidth, "§7whose profit is at least the minimum. Close the menu to stop."));
            y += 16;
            if (cfg.getAutoCroesusEnabledRaw()) {
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, minProfitText(),
                        cfg.getAutoMinProfitK() / (double) CroesusConfig.MAX_MIN_PROFIT_K) {
                    @Override
                    protected void updateMessage() {
                        setMessage(minProfitText());
                    }

                    @Override
                    protected void applyValue() {
                        // 50k steps
                        int k = (int) Math.round(this.value * CroesusConfig.MAX_MIN_PROFIT_K / 50.0) * 50;
                        CroesusConfig c = CroesusConfig.getInstance();
                        c.setAutoMinProfitK(k);
                        c.save();
                    }
                });
                y += 24;
                int range = CroesusConfig.MAX_DELAY_BOUND_MS - CroesusConfig.MIN_DELAY_BOUND_MS;
                widgets.add(new ThemedSliderButton(contentX, y, half, 20, minDelayText(),
                        (cfg.getAutoMinDelayMs() - CroesusConfig.MIN_DELAY_BOUND_MS) / (double) range) {
                    @Override
                    protected void updateMessage() {
                        setMessage(minDelayText());
                    }

                    @Override
                    protected void applyValue() {
                        CroesusConfig c = CroesusConfig.getInstance();
                        c.setAutoMinDelayMs((int) Math.round(CroesusConfig.MIN_DELAY_BOUND_MS + this.value * range));
                        c.save();
                    }
                });
                widgets.add(new ThemedSliderButton(contentX + half + GAP, y, half, 20, maxDelayText(),
                        (cfg.getAutoMaxDelayMs() - CroesusConfig.MIN_DELAY_BOUND_MS) / (double) range) {
                    @Override
                    protected void updateMessage() {
                        setMessage(maxDelayText());
                    }

                    @Override
                    protected void applyValue() {
                        CroesusConfig c = CroesusConfig.getInstance();
                        c.setAutoMaxDelayMs((int) Math.round(CroesusConfig.MIN_DELAY_BOUND_MS + this.value * range));
                        c.save();
                    }
                });
                y += 24;
                if (AutoCroesusFeature.isRunning()) {
                    widgets.add(SettingsButtonWidget.builder(Component.literal("§cStop Auto Croesus"), btn -> {
                                AutoCroesusFeature.requestStop();
                                requestRebuild.run();
                            }).bounds(contentX, y, half, 18).build());
                }
            }
        }
        return widgets;
    }

    private static int totalsSection(List<AbstractWidget> widgets, int x, int y, int width, String heading,
                                     Map<String, CroesusProfitLog.Totals> totals) {
        widgets.add(label(x, y, width, "§6" + heading + (totals.isEmpty() ? " §7- nothing claimed yet" : "")));
        y += 12;
        for (Map.Entry<String, CroesusProfitLog.Totals> e : totals.entrySet()) {
            CroesusProfitLog.Totals t = e.getValue();
            String profit = (t.profit >= 0 ? "§a+" : "§c") + DungeonChestValuer.formatCoins(t.profit);
            widgets.add(label(x + 6, y, width - 6, "§f" + e.getKey() + "§7: " + t.chests + " chests, cost §6"
                    + DungeonChestValuer.formatCoins(t.cost) + "§7, value §6" + DungeonChestValuer.formatCoins(t.value)
                    + "§7, profit " + profit));
            y += 11;
        }
        return y + 5;
    }

    private static Component minProfitText() {
        return Component.literal("Min Profit: " + DungeonChestValuer.formatCoins(CroesusConfig.getInstance().getAutoMinProfitK() * 1000L));
    }

    private static Component minDelayText() {
        return Component.literal("Min Delay: " + CroesusConfig.getInstance().getAutoMinDelayMs() + "ms");
    }

    private static Component maxDelayText() {
        return Component.literal("Max Delay: " + CroesusConfig.getInstance().getAutoMaxDelayMs() + "ms");
    }

    private static StringWidget label(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal(text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
