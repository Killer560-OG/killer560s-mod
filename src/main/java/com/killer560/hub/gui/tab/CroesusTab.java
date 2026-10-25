package com.killer560.hub.gui.tab;

import com.killer560.hub.croesus.CroesusConfig;
import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.croesus.CroesusTrackerScreen;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/** Croesus (legit): the Chest Profit overlay, Price Mode and the Croesus Profit Logger - see
 *  {@link com.killer560.hub.croesus.ChestProfitFeature}, {@link CroesusProfitLog}. Auto Croesus has its own cheat-only
 *  tab since 2026-10-12 ({@link AutoCroesusTab}; killer560: "separate auto croesus and regular croesus just like etable
 *  and auto etable"), on the same {@link CroesusConfig} fields, so nothing saved moved.
 *  <p>
 *  The session / all-time totals used to be painted into this panel; killer560 (2026-09-20) asked for them to
 *  move out: "Remove the session and all time text inside the mod menu, instead i should type /croesus profit
 *  session or all to see it". They now live in {@code /croesus} ({@link CroesusTrackerScreen}) together with
 *  the item log and the big-drop list, and the button below just opens it. */
public class CroesusTab extends BaseTab {

    private static final int GAP = 8;

    public CroesusTab() {
        super("Croesus");
    }

    /** The card is ON while any of its features is, OFF only when all are (killer560, 2026-10-14): Chest Profit, the Profit Logger and Hide Opened Runs are separate features. */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Chest Profit", "Croesus Profit Logger", "Hide Opened Runs", "Reroll Protection",
                "Kismet Count", "Croesus Chests HUD");
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
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Free Chest", cfg.isHighlightFreeChest()), btn -> {
                        cfg.setHighlightFreeChest(!cfg.isHighlightFreeChest());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX + half + GAP, y, half, 18).build());
            y += 20;
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Runs", cfg.isHighlightRuns()), btn -> {
                        cfg.setHighlightRuns(!cfg.isHighlightRuns());
                        cfg.save();
                        btn.setMessage(onOff("Highlight Runs", cfg.isHighlightRuns()));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Key Chest", cfg.isHighlightKeyChest()), btn -> {
                        cfg.setHighlightKeyChest(!cfg.isHighlightKeyChest());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX + half + GAP, y, half, 18).build());
            y += 20;
            if (cfg.isHighlightFreeChest() || cfg.isHighlightKeyChest()) {
                widgets.add(colourButton("Free Chest Color", cfg.getChestFreeColor(),
                        CroesusConfig.DEFAULT_CHEST_FREE_COLOR, rgb -> cfg.setChestFreeColor(rgb), contentX, y, half));
                widgets.add(colourButton("Key Chest Color", cfg.getChestKeyColor(),
                        CroesusConfig.DEFAULT_CHEST_KEY_COLOR, rgb -> cfg.setChestKeyColor(rgb),
                        contentX + half + GAP, y, half));
                y += 20;
            }
            if (cfg.isHighlightRuns()) {
                widgets.add(colourButton("Unclaimed Run Color", cfg.getRunUnclaimedColor(),
                        CroesusConfig.DEFAULT_RUN_UNCLAIMED_COLOR, rgb -> cfg.setRunUnclaimedColor(rgb), contentX, y, half));
                widgets.add(colourButton("Needs Key Color", cfg.getRunKeyColor(),
                        CroesusConfig.DEFAULT_RUN_KEY_COLOR, rgb -> cfg.setRunKeyColor(rgb),
                        contentX + half + GAP, y, half));
                y += 20;
                widgets.add(colourButton("Opened Run Color", cfg.getRunOpenedColor(),
                        CroesusConfig.DEFAULT_RUN_OPENED_COLOR, rgb -> cfg.setRunOpenedColor(rgb), contentX, y, half));
                widgets.add(colourButton("Key Used Color", cfg.getRunClaimedColor(),
                        CroesusConfig.DEFAULT_RUN_CLAIMED_COLOR, rgb -> cfg.setRunClaimedColor(rgb),
                        contentX + half + GAP, y, half));
                y += 20;
                widgets.add(colourButton("Not Looked At Color", cfg.getRunUnknownColor(),
                        CroesusConfig.DEFAULT_RUN_UNKNOWN_COLOR, rgb -> cfg.setRunUnknownColor(rgb), contentX, y, half));
                widgets.add(SettingsButtonWidget.builder(onOff("Color Legend", cfg.isShowRunLegend()), btn -> {
                            cfg.setShowRunLegend(!cfg.isShowRunLegend());
                            cfg.save();
                            btn.setMessage(onOff("Color Legend", cfg.isShowRunLegend()));
                        }).bounds(contentX + half + GAP, y, half, 18).build());
                y += 20;
            }
            y += 6;
        }

        // killer560, 2026-10-12: "make an option to hide opened chests". Its own row, outside Chest Profit: it hides
        // claimed runs whether or not the values are shown.
        widgets.add(SettingsButtonWidget.builder(onOff("Hide Opened Runs", cfg.isHideOpenedRuns()), btn -> {
                    cfg.setHideOpenedRuns(!cfg.isHideOpenedRuns());
                    cfg.save();
                    btn.setMessage(onOff("Hide Opened Runs", cfg.isHideOpenedRuns()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        // ---- Reroll Protection, Kismet Count, Croesus Chests HUD (2026-10-25): each only drops his own click or shows a
        // number, so legit ----
        widgets.add(SettingsButtonWidget.builder(onOff("Reroll Protection", cfg.isRerollProtectionRaw()), btn -> {
                    cfg.setRerollProtection(!cfg.isRerollProtectionRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        if (cfg.isRerollProtectionRaw()) {
            widgets.add(new com.killer560.hub.gui.ThemedSliderButton(contentX, y, contentWidth, 18, protectAboveText(),
                    cfg.getRerollProtectAboveK() / (double) CroesusConfig.MAX_MIN_PROFIT_K) {
                @Override
                protected void updateMessage() {
                    setMessage(protectAboveText());
                }

                @Override
                protected void applyValue() {
                    int k = (int) Math.round(this.value * CroesusConfig.MAX_MIN_PROFIT_K / 250.0) * 250;
                    CroesusConfig c = CroesusConfig.getInstance();
                    c.setRerollProtectAboveK(k);
                    saveOnRelease(() -> c.save());
                }
            });
            y += 20;
        }
        widgets.add(SettingsButtonWidget.builder(onOff("Kismet Count", cfg.isKismetCountRaw()), btn -> {
                    cfg.setKismetCount(!cfg.isKismetCountRaw());
                    cfg.save();
                    btn.setMessage(onOff("Kismet Count", cfg.isKismetCountRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Croesus Chests HUD", cfg.isChestCountHudRaw()), btn -> {
                    cfg.setChestCountHud(!cfg.isChestCountHudRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        if (cfg.isChestCountHudRaw()) {
            widgets.add(new com.killer560.hub.gui.ThemedSliderButton(contentX, y, contentWidth, 18, warnAtText(),
                    (cfg.getChestWarnAt() - 1) / (double) (CroesusConfig.MAX_CHEST_WARN_AT - 1)) {
                @Override
                protected void updateMessage() {
                    setMessage(warnAtText());
                }

                @Override
                protected void applyValue() {
                    CroesusConfig c = CroesusConfig.getInstance();
                    c.setChestWarnAt(1 + (int) Math.round(this.value * (CroesusConfig.MAX_CHEST_WARN_AT - 1)));
                    saveOnRelease(() -> c.save());
                }
            });
            y += 20;
        }
        y += 4;

        // Price Mode (killer560, 2026-10-11): how every Croesus value is priced - the chest overlay, the logger and
        // Auto Croesus's decisions. Left click steps forward, right click back.
        widgets.add(SettingsButtonWidget.cycling(priceModeText(cfg), (btn, dir) -> {
                    cfg.setPriceMode(SettingsButtonWidget.step(cfg.getPriceMode(), dir));
                    cfg.save();
                    btn.setMessage(priceModeText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        // ---- Theme: ONE for all of Croesus (killer560, 2026-10-18) - the chest profit panel, the Start button and the
        // /croesus tracker screen all follow it (ScreenThemes.section) ----
        widgets.add(com.killer560.hub.gui.ScreenThemes.button("croesus", contentX, y, contentWidth, 18));
        y += 24;

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
                        client.setScreenAndShow(new CroesusTrackerScreen(McCompat.screen(client), CroesusTrackerScreen.View.OVERVIEW));
                    }).bounds(contentX + half + GAP, y, half, 18).build());
            y += 20;
            // Shared by every profit tracker (killer560, 2026-10-15): whose profit, and whether mod profiles share it.
            y = com.killer560.hub.gui.profit.ProfitScopeBar.settingsRows(widgets, contentX, y, contentWidth);
        }

        return widgets;
    }

    private static AbstractWidget colourButton(String label, int rgb, int defaultRgb,
                                               java.util.function.IntConsumer set, int x, int y, int w) {
        return SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label(label, 0xFF000000 | rgb), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(McCompat.screen(client), label,
                            0xFF000000 | rgb, 0xFF000000 | defaultRgb, argb -> {
                        set.accept(argb & 0xFFFFFF);
                        CroesusConfig.getInstance().save();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    private static Component protectAboveText() {
        return Component.literal("Protect Above: §6"
                + DungeonChestValuer.formatCoins(CroesusConfig.getInstance().getRerollProtectAboveK() * 1000L));
    }

    private static Component warnAtText() {
        return Component.literal("Chest Warning At: §6" + CroesusConfig.getInstance().getChestWarnAt() + "/60");
    }

    private static Component priceModeText(CroesusConfig cfg) {
        return Component.literal("Price Mode: §6" + cfg.getPriceMode().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
