package com.killer560.hub.gui.tab;

import com.killer560.hub.croesus.AutoCroesusFeature;
import com.killer560.hub.croesus.CroesusConfig;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Croesus (cheat build only), its own tab since 2026-10-12 - killer560: "separate auto croesus and regular croesus
 *  just like etable and auto etable". The settings were the red Auto Croesus section at the bottom of the Croesus tab and
 *  are the same {@link CroesusConfig} fields, so nothing saved moved. The Start Croesus button is drawn over the Croesus
 *  menu itself ({@link AutoCroesusFeature}) and is placed in Edit HUD. Only added to {@link DungeonTab}'s cheat block. */
public class AutoCroesusTab extends BaseTab {

    private static final int GAP = 8;

    public AutoCroesusTab() {
        super("Auto Croesus");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CroesusConfig cfg = CroesusConfig.getInstance();
        int y = contentY;
        int half = (contentWidth - GAP) / 2;
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
                // killer560 (2026-09-27): "have an option for it to use based off of key insta buy
                // price, buy offer price, or by set profit." Left-click cycles forward, right-click
                // back (same repo-wide rule as every other cycling button - see SettingsButtonWidget).
                widgets.add(SettingsButtonWidget.builder(chestKeyModeText(cfg), btn -> {
                            cfg.setChestKeyMode(cfg.getChestKeyMode().next());
                            cfg.save();
                            btn.setMessage(chestKeyModeText(cfg));
                            requestRebuild.run();
                        }).secondaryPress(btn -> {
                            cfg.setChestKeyMode(cfg.getChestKeyMode().previous());
                            cfg.save();
                            btn.setMessage(chestKeyModeText(cfg));
                            requestRebuild.run();
                        }).bounds(contentX, y, contentWidth, 18).build());
                y += 22;
                // Still shown (and still saved) in the two key-price modes - it's the fallback threshold
                // used until the key's live Bazaar price has actually loaded (see
                // AutoCroesusFeature#keyModeThreshold), not just the Set Profit mode's own value.
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, keyProfitText(cfg),
                        cfg.getAutoKeyMinProfitK() / (double) CroesusConfig.MAX_MIN_PROFIT_K) {
                    @Override
                    protected void updateMessage() {
                        setMessage(keyProfitText(cfg));
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
        return widgets;
    }

    private static Component minProfitText() {
        return Component.literal("Min Profit: " + DungeonChestValuer.formatCoins(CroesusConfig.getInstance().getAutoMinProfitK() * 1000L));
    }

    private static Component keyProfitText(CroesusConfig cfg) {
        String suffix = cfg.getChestKeyMode() == CroesusConfig.ChestKeyMode.SET_PROFIT ? "" : " §7(fallback)";
        return Component.literal("Chest Key Min Profit: "
                + DungeonChestValuer.formatCoins(cfg.getAutoKeyMinProfitK() * 1000L) + suffix);
    }

    private static Component chestKeyModeText(CroesusConfig cfg) {
        return Component.literal("Use Key Based On: §6" + cfg.getChestKeyMode().label);
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
