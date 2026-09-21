package com.killer560.hub.gui.tab;

import com.killer560.hub.croesus.AutoCroesusFeature;
import com.killer560.hub.croesus.CroesusConfig;
import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.croesus.CroesusTrackerScreen;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chest Profit overlay, Croesus Profit Logger and (cheat build only) Auto Croesus settings - see
 *  {@link com.killer560.hub.croesus.ChestProfitFeature}, {@link CroesusProfitLog}, {@link AutoCroesusFeature}.
 *  <p>
 *  The session / all-time totals used to be painted into this panel; killer560 (2026-09-20) asked for them to
 *  move out: "Remove the session and all time text inside the mod menu, instead i should type /croesus profit
 *  session or all to see it". They now live in {@code /croesus} ({@link CroesusTrackerScreen}) together with
 *  the item log and the big-drop list, and the button below just opens it. */
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
            y += 20;
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Runs", cfg.isHighlightRuns()), btn -> {
                        cfg.setHighlightRuns(!cfg.isHighlightRuns());
                        cfg.save();
                        btn.setMessage(onOff("Highlight Runs", cfg.isHighlightRuns()));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Second Best With Key", cfg.isHighlightSecondWithKey()), btn -> {
                        cfg.setHighlightSecondWithKey(!cfg.isHighlightSecondWithKey());
                        cfg.save();
                        btn.setMessage(onOff("Second Best With Key", cfg.isHighlightSecondWithKey()));
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
        if (cfg.isLoggerEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Chat Summary", cfg.isLoggerChatSummary()), btn -> {
                        cfg.setLoggerChatSummary(!cfg.isLoggerChatSummary());
                        cfg.save();
                        btn.setMessage(onOff("Chat Summary", cfg.isLoggerChatSummary()));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("Open Profit Tracker"), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreenAndShow(new CroesusTrackerScreen(client.screen, CroesusTrackerScreen.View.TOTALS));
                    }).bounds(contentX + half + GAP, y, half, 18).build());
            y += 20;
            widgets.add(label(contentX, y, contentWidth, "§7" + CroesusProfitLog.entryCount()
                    + " claims logged §8- §7/croesus"));
            y += 18;
        }

        // ---- Auto Croesus (cheat build only) ----
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Red header, like every other cheat-only section (killer560, 2026-09-20: "Change auto croesus
            // to be red and make sure it is only on the cheat version"). The gating was already right - it
            // is behind CHEAT_FEATURES_ENABLED here and in CroesusConfig.isAutoCroesusEnabled - but nothing
            // on screen said so, so it read like an ordinary setting.
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    com.killer560.hub.gui.SectionHeaders.header("Auto Croesus", true),
                    Minecraft.getInstance().font));
            y += 14;
            widgets.add(SettingsButtonWidget.builder(onOff("Auto Croesus", cfg.getAutoCroesusEnabledRaw()), btn -> {
                        cfg.setAutoCroesusEnabled(!cfg.getAutoCroesusEnabledRaw());
                        cfg.save();
                        if (!cfg.getAutoCroesusEnabledRaw()) {
                            AutoCroesusFeature.requestStop();
                        }
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 22;
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

                widgets.add(SettingsButtonWidget.builder(onOff("Use Chest Keys", cfg.isAutoUseChestKeys()), btn -> {
                            cfg.setAutoUseChestKeys(!cfg.isAutoUseChestKeys());
                            cfg.save();
                            requestRebuild.run();
                        }).bounds(contentX, y, half, 18).build());
                widgets.add(SettingsButtonWidget.builder(onOff("Use Kismets", cfg.isAutoUseKismets()), btn -> {
                            cfg.setAutoUseKismets(!cfg.isAutoUseKismets());
                            cfg.save();
                            requestRebuild.run();
                        }).bounds(contentX + half + GAP, y, half, 18).build());
                y += 22;
                if (cfg.isAutoUseChestKeys()) {
                    widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, keyProfitText(),
                            cfg.getAutoKeyMinProfitK() / (double) CroesusConfig.MAX_MIN_PROFIT_K) {
                        @Override
                        protected void updateMessage() {
                            setMessage(keyProfitText());
                        }

                        @Override
                        protected void applyValue() {
                            int k = (int) Math.round(this.value * CroesusConfig.MAX_MIN_PROFIT_K / 50.0) * 50;
                            CroesusConfig c = CroesusConfig.getInstance();
                            c.setAutoKeyMinProfitK(k);
                            c.save();
                        }
                    });
                    y += 24;
                }
                if (cfg.isAutoUseKismets()) {
                    widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, rerollBelowText(),
                            cfg.getAutoRerollBelowK() / (double) CroesusConfig.MAX_MIN_PROFIT_K) {
                        @Override
                        protected void updateMessage() {
                            setMessage(rerollBelowText());
                        }

                        @Override
                        protected void applyValue() {
                            int k = (int) Math.round(this.value * CroesusConfig.MAX_MIN_PROFIT_K / 50.0) * 50;
                            CroesusConfig c = CroesusConfig.getInstance();
                            c.setAutoRerollBelowK(k);
                            c.save();
                        }
                    });
                    y += 24;
                }
                // Live status only - the button that actually starts it is drawn over the Croesus menu itself.
                widgets.add(label(contentX, y, contentWidth, AutoCroesusFeature.isRunning()
                        ? "§aRunning §7- any key stops it"
                        : "§7Idle §8- §7starts from the §6Start Croesus §7button over the menu"));
                y += 14;
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

    private static Component minProfitText() {
        return Component.literal("Min Profit: " + DungeonChestValuer.formatCoins(CroesusConfig.getInstance().getAutoMinProfitK() * 1000L));
    }

    private static Component keyProfitText() {
        return Component.literal("Chest Key Min Profit: "
                + DungeonChestValuer.formatCoins(CroesusConfig.getInstance().getAutoKeyMinProfitK() * 1000L));
    }

    private static Component rerollBelowText() {
        return Component.literal("Reroll Below: "
                + DungeonChestValuer.formatCoins(CroesusConfig.getInstance().getAutoRerollBelowK() * 1000L));
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
