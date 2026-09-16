package com.killer560.hub.gui.tab;

import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Cheat Utils settings - Secret Aura, Auto GFS, Auto Ult, Auto Chocolate Factory (see
 *  {@code com.killer560.hub.cheatutils}). Cheat build only: on the legit build this tab builds no settings
 *  at all. Each section collapses to its master toggle while that toggle is OFF (SecretsTab pattern). */
public class CheatUtilsTab extends BaseTab {

    private static final int BTN_W = 220;

    public CheatUtilsTab() {
        super("Cheat Utils");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        int half = (contentWidth - 8) / 2;
        int[] y = {contentY};

        label(w, contentX, y, contentWidth, "Real macros/ESP against Hypixel's rules - use at your own risk.");


        // ---- Secret Aura ----
        header(w, contentX, y, contentWidth, "Secret Aura (dungeons)");
        toggle(w, contentX, y, "Secret Aura", cfg::isSecretAuraEnabled, v -> cfg.setSecretAuraEnabled(v), requestRebuild);
        if (cfg.isSecretAuraEnabled()) {
            toggle(w, contentX, y, "Chests", cfg::isAuraChests, cfg::setAuraChests, null);
            toggle(w, contentX, y, "Levers", cfg::isAuraLevers, cfg::setAuraLevers, null);
            toggle(w, contentX, y, "Wither Essence", cfg::isAuraEssence, cfg::setAuraEssence, null);
            toggle(w, contentX, y, "F7 Boss Levers (P3/devices)", cfg::isAuraBossLevers, cfg::setAuraBossLevers, null);
            toggle(w, contentX, y, "Swing Hand", cfg::isAuraSwing, cfg::setAuraSwing, null);
            toggle(w, contentX, y, "Pause While Sneaking", cfg::isAuraPauseWhileSneaking, cfg::setAuraPauseWhileSneaking, null);
            slider(w, contentX, y[0], half, () -> String.format(java.util.Locale.US, "Range: %.1f", cfg.getAuraRange()),
                    (cfg.getAuraRange() - CheatUtilsConfig.MIN_AURA_RANGE) / (CheatUtilsConfig.MAX_AURA_RANGE - CheatUtilsConfig.MIN_AURA_RANGE),
                    v -> cfg.setAuraRange(CheatUtilsConfig.MIN_AURA_RANGE + v * (CheatUtilsConfig.MAX_AURA_RANGE - CheatUtilsConfig.MIN_AURA_RANGE)));
            slider(w, contentX + half + 8, y[0], half, () -> String.format(java.util.Locale.US, "Skull Range: %.1f", cfg.getAuraSkullRange()),
                    (cfg.getAuraSkullRange() - CheatUtilsConfig.MIN_AURA_RANGE) / (CheatUtilsConfig.MAX_AURA_SKULL_RANGE - CheatUtilsConfig.MIN_AURA_RANGE),
                    v -> cfg.setAuraSkullRange(CheatUtilsConfig.MIN_AURA_RANGE + v * (CheatUtilsConfig.MAX_AURA_SKULL_RANGE - CheatUtilsConfig.MIN_AURA_RANGE)));
            y[0] += 24;
            slider(w, contentX, y[0], BTN_W, () -> "Click Cooldown: " + cfg.getAuraCooldownMs() + "ms",
                    norm(cfg.getAuraCooldownMs(), CheatUtilsConfig.MIN_AURA_COOLDOWN_MS, CheatUtilsConfig.MAX_AURA_COOLDOWN_MS),
                    v -> cfg.setAuraCooldownMs(denorm(v, CheatUtilsConfig.MIN_AURA_COOLDOWN_MS, CheatUtilsConfig.MAX_AURA_COOLDOWN_MS)));
            y[0] += 24;
            label(w, contentX, y, contentWidth, "Pause while holding (comma-separated item names / IDs):");
            EditBox pause = new EditBox(Minecraft.getInstance().font, contentX, y[0], BTN_W, 18, Component.literal("Pause while holding"));
            pause.setMaxLength(200);
            pause.setValue(cfg.getAuraPauseHolding());
            pause.setResponder(text -> {
                cfg.setAuraPauseHolding(text);
                cfg.save();
            });
            w.add(pause);
            y[0] += 26;
        }

        // ---- Auto GFS ----
        header(w, contentX, y, contentWidth, "Auto GFS (dungeons, /gfs from sacks)");
        toggle(w, contentX, y, "Auto GFS", cfg::isAutoGfsEnabled, v -> cfg.setAutoGfsEnabled(v), requestRebuild);
        if (cfg.isAutoGfsEnabled()) {
            toggle(w, contentX, y, "Ender Pearls", cfg::isGfsPearls, cfg::setGfsPearls, null);
            toggle(w, contentX, y, "Spirit Leaps", cfg::isGfsLeaps, cfg::setGfsLeaps, null);
            toggle(w, contentX, y, "Superboom TNT", cfg::isGfsSuperbooms, cfg::setGfsSuperbooms, null);
            toggle(w, contentX, y, "Inflatable Jerry", cfg::isGfsJerries, cfg::setGfsJerries, null);
            toggle(w, contentX, y, "Skip If None In Inventory", cfg::isGfsSkipIfNone, cfg::setGfsSkipIfNone, null);
            slider(w, contentX, y[0], half, () -> "Refill Below: " + cfg.getGfsThresholdPercent() + "%",
                    norm(cfg.getGfsThresholdPercent(), CheatUtilsConfig.MIN_GFS_THRESHOLD_PERCENT, CheatUtilsConfig.MAX_GFS_THRESHOLD_PERCENT),
                    v -> cfg.setGfsThresholdPercent(denorm(v, CheatUtilsConfig.MIN_GFS_THRESHOLD_PERCENT, CheatUtilsConfig.MAX_GFS_THRESHOLD_PERCENT)));
            slider(w, contentX + half + 8, y[0], half, () -> "Check Every: " + cfg.getGfsIntervalSec() + "s",
                    norm(cfg.getGfsIntervalSec(), CheatUtilsConfig.MIN_GFS_INTERVAL_SEC, CheatUtilsConfig.MAX_GFS_INTERVAL_SEC),
                    v -> cfg.setGfsIntervalSec(denorm(v, CheatUtilsConfig.MIN_GFS_INTERVAL_SEC, CheatUtilsConfig.MAX_GFS_INTERVAL_SEC)));
            y[0] += 28;
        }

        // ---- Auto Ult ----
        header(w, contentX, y, contentWidth, "Auto Ult (F7/M7 boss, Healer/Tank)");
        toggle(w, contentX, y, "Auto Ult", cfg::isAutoUltEnabled, v -> cfg.setAutoUltEnabled(v), requestRebuild);
        if (cfg.isAutoUltEnabled()) {
            toggle(w, contentX, y, "On Maxor Enraged", cfg::isUltMaxorEnraged, cfg::setUltMaxorEnraged, null);
            toggle(w, contentX, y, "On Goldor Factory Destroyed", cfg::isUltGoldorFactory, cfg::setUltGoldorFactory, null);
            w.add(SettingsButtonWidget.builder(classText(cfg), btn -> {
                cfg.cycleUltClassOverride();
                cfg.save();
                btn.setMessage(classText(cfg));
            }).bounds(contentX, y[0], BTN_W, 20).build());
            y[0] += 28;
        }

        // ---- Auto Chocolate Factory ----
        header(w, contentX, y, contentWidth, "Auto Chocolate Factory (in its GUI)");
        toggle(w, contentX, y, "Auto Chocolate Factory", cfg::isChocolateEnabled, v -> cfg.setChocolateEnabled(v), requestRebuild);
        if (cfg.isChocolateEnabled()) {
            toggle(w, contentX, y, "Click Cookie", cfg::isCfClickCookie, cfg::setCfClickCookie, null);
            toggle(w, contentX, y, "Buy Best Upgrade", cfg::isCfAutoUpgrade, cfg::setCfAutoUpgrade, null);
            toggle(w, contentX, y, "Claim Stray Rabbits", cfg::isCfClaimStrays, cfg::setCfClaimStrays, null);
            toggle(w, contentX, y, "Auto Time Tower", cfg::isCfAutoTimeTower, cfg::setCfAutoTimeTower, null);
            slider(w, contentX, y[0], half, () -> "Min Delay: " + cfg.getCfMinDelayMs() + "ms",
                    norm(cfg.getCfMinDelayMs(), CheatUtilsConfig.MIN_CF_DELAY_MS, CheatUtilsConfig.MAX_CF_DELAY_MS),
                    v -> cfg.setCfMinDelayMs(denorm(v, CheatUtilsConfig.MIN_CF_DELAY_MS, CheatUtilsConfig.MAX_CF_DELAY_MS)));
            slider(w, contentX + half + 8, y[0], half, () -> "Max Delay: " + cfg.getCfMaxDelayMs() + "ms",
                    norm(cfg.getCfMaxDelayMs(), CheatUtilsConfig.MIN_CF_DELAY_MS, CheatUtilsConfig.MAX_CF_DELAY_MS),
                    v -> cfg.setCfMaxDelayMs(denorm(v, CheatUtilsConfig.MIN_CF_DELAY_MS, CheatUtilsConfig.MAX_CF_DELAY_MS)));
            y[0] += 24;
            slider(w, contentX, y[0], BTN_W, () -> "Upgrade Delay: " + cfg.getCfUpgradeDelayMs() + "ms",
                    norm(cfg.getCfUpgradeDelayMs(), CheatUtilsConfig.MIN_CF_UPGRADE_DELAY_MS, CheatUtilsConfig.MAX_CF_UPGRADE_DELAY_MS),
                    v -> cfg.setCfUpgradeDelayMs(denorm(v, CheatUtilsConfig.MIN_CF_UPGRADE_DELAY_MS, CheatUtilsConfig.MAX_CF_UPGRADE_DELAY_MS)));
            y[0] += 24;
        }
        return w;
    }

    private static Component classText(CheatUtilsConfig cfg) {
        String c = cfg.getUltClassOverride();
        return Component.literal("Class: §b" + ("AUTO".equalsIgnoreCase(c) ? "Auto (tab list)" : c.charAt(0) + c.substring(1).toLowerCase(java.util.Locale.ROOT)));
    }

    private static double norm(int value, int min, int max) {
        return (value - min) / (double) (max - min);
    }

    private static int denorm(double v, int min, int max) {
        return (int) Math.round(min + v * (max - min));
    }

    private static void label(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        w.add(new StringWidget(x, y[0], width, 12, Component.literal(text), Minecraft.getInstance().font));
        y[0] += 16;
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, true), Minecraft.getInstance().font));
        y[0] += 16;
    }

    /** ON/OFF toggle button; a non-null {@code rebuild} rebuilds the tab (used by master toggles). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            CheatUtilsConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               java.util.function.DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, width, 20, Component.literal(text.get()), Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
                CheatUtilsConfig.getInstance().save();
            }
        });
    }

}
