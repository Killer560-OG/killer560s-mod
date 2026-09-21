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
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/** Secret Aura settings - split out of {@code CheatUtilsTab} 2026-09-21 into the "Secrets" folder per
 *  killer560's menu-structure request ("secret sound, messages, secret waypoints, etherwarp waypoints,
 *  Secret Aura, Secret Triggerbot and Lever Aura all in one place"). Still backed by
 *  {@link CheatUtilsConfig} - same JSON keys, unchanged - so this is a GUI-only move. Cheat build only:
 *  only ever added to the "Secrets" folder behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} (SecretsTab
 *  pattern), so on the legit build this tab builds no settings at all and is never in the list at all -
 *  see {@link SecretsTab}. */
public class SecretAuraTab extends BaseTab {

    private static final int BTN_W = 220;

    public SecretAuraTab() {
        super("Secret Aura");
    }

    /** Only added to {@link SecretsTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
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

        header(w, contentX, y, contentWidth, "Secret Aura (dungeons)");
        toggle(w, contentX, y, "Secret Aura", cfg::isSecretAuraEnabled, cfg::setSecretAuraEnabled, requestRebuild);
        if (!cfg.isSecretAuraEnabled()) {
            return w;
        }

        toggle(w, contentX, y, "Chests", cfg::isAuraChests, cfg::setAuraChests, null);
        toggle(w, contentX, y, "Levers", cfg::isAuraLevers, cfg::setAuraLevers, null);
        toggle(w, contentX, y, "Wither Essence", cfg::isAuraEssence, cfg::setAuraEssence, null);
        toggle(w, contentX, y, "F7 Boss Levers (P3/Devices)", cfg::isAuraBossLevers, cfg::setAuraBossLevers, null);
        toggle(w, contentX, y, "Swing Hand", cfg::isAuraSwing, cfg::setAuraSwing, null);
        toggle(w, contentX, y, "Pause While Sneaking", cfg::isAuraPauseWhileSneaking, cfg::setAuraPauseWhileSneaking, null);
        slider(w, contentX, y[0], half, () -> String.format(Locale.US, "Range: %.1f", cfg.getAuraRange()),
                (cfg.getAuraRange() - CheatUtilsConfig.MIN_AURA_RANGE) / (CheatUtilsConfig.MAX_AURA_RANGE - CheatUtilsConfig.MIN_AURA_RANGE),
                v -> cfg.setAuraRange(CheatUtilsConfig.MIN_AURA_RANGE + v * (CheatUtilsConfig.MAX_AURA_RANGE - CheatUtilsConfig.MIN_AURA_RANGE)));
        slider(w, contentX + half + 8, y[0], half, () -> String.format(Locale.US, "Skull Range: %.1f", cfg.getAuraSkullRange()),
                (cfg.getAuraSkullRange() - CheatUtilsConfig.MIN_AURA_RANGE) / (CheatUtilsConfig.MAX_AURA_SKULL_RANGE - CheatUtilsConfig.MIN_AURA_RANGE),
                v -> cfg.setAuraSkullRange(CheatUtilsConfig.MIN_AURA_RANGE + v * (CheatUtilsConfig.MAX_AURA_SKULL_RANGE - CheatUtilsConfig.MIN_AURA_RANGE)));
        y[0] += 24;
        slider(w, contentX, y[0], BTN_W, () -> "Click Cooldown: " + cfg.getAuraCooldownMs() + "ms",
                norm(cfg.getAuraCooldownMs(), CheatUtilsConfig.MIN_AURA_COOLDOWN_MS, CheatUtilsConfig.MAX_AURA_COOLDOWN_MS),
                v -> cfg.setAuraCooldownMs(denorm(v, CheatUtilsConfig.MIN_AURA_COOLDOWN_MS, CheatUtilsConfig.MAX_AURA_COOLDOWN_MS)));
        y[0] += 24;
        EditBox pause = new EditBox(Minecraft.getInstance().font, contentX, y[0], BTN_W, 18, Component.literal("Pause while holding"));
        pause.setMaxLength(200);
        pause.setValue(cfg.getAuraPauseHolding());
        pause.setResponder(text -> {
            cfg.setAuraPauseHolding(text);
            cfg.save();
        });
        w.add(pause);
        y[0] += 26;

        return w;
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
                               DoubleConsumer apply) {
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
