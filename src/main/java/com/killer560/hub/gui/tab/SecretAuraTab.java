package com.killer560.hub.gui.tab;

import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.leveraura.LeverAuraConfig;
import com.killer560.hub.secrettrigger.SecretTriggerbotConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/** Secret Aura settings, and since 2026-10-15 the Lever Aura's too (killer560: "make lever aura the setting go into
 *  secret aura and have a toggle for in boss or not"). Two features share the tab and each keeps its own switch
 *  ({@link #masterToggles}): Secret Aura, and In Boss - the F7/M7 P3 levers, which is what Lever Aura was (its section
 *  lever options sit under it). There is no separate Lever Aura tab any more. The S2 Lights device is not here: it is its
 *  own cheat tab since 2026-10-15 ({@link LightsDeviceTab}), with its own Aura and Triggerbot.
 *  <p>
 *  Secret Aura was split out of {@code CheatUtilsTab} 2026-09-21 into the "Secrets" folder. Still backed by
 *  {@link CheatUtilsConfig} - same JSON keys, unchanged. Cheat build only:
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

    /** The card is ON while Secret Aura, In Boss or Secret Triggerbot is, OFF only when all are (separate features). */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Secret Aura", "In Boss", "Secret Triggerbot");
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
        if (cfg.isSecretAuraEnabled()) {
            toggle(w, contentX, y, "Chests", cfg::isAuraChests, cfg::setAuraChests, null);
            toggle(w, contentX, y, "Wither Essence", cfg::isAuraEssence, cfg::setAuraEssence, null);
            toggle(w, contentX, y, "Redstone Key", cfg::isAuraRedstoneKey, cfg::setAuraRedstoneKey, null);
            toggle(w, contentX, y, "Place Redstone Key", cfg::isAuraPlaceRedstoneKey, cfg::setAuraPlaceRedstoneKey, null);
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
        }

        // Secret Triggerbot: its own card until killer560, 2026-11-09 ("merge secret aura and secret triggerbot into one red
        // secrets thing"). Same config; its toggle key keeps the old card id (ModuleKeybinds.SECRET_TRIGGERBOT_ID).
        triggerbot(w, contentX, y, contentWidth, half, requestRebuild);

        // Levers. In Rooms is Secret Aura's own (so it shows only while Secret Aura is on). In Boss is the old Lever Aura
        // switch and, as that did, works with Secret Aura off: with Secret Aura on it also lets the aura click the other F7
        // P3 levers (the old "F7 Boss Levers" row).
        header(w, contentX, y, contentWidth, "Levers");
        if (cfg.isSecretAuraEnabled()) {
            toggle(w, contentX, y, "In Rooms", cfg::isAuraLevers, cfg::setAuraLevers, null);
        }
        toggle(w, contentX, y, "In Boss", cfg::isAuraBossLevers, cfg::setAuraBossLevers, requestRebuild);
        if (!cfg.isAuraBossLevers()) {
            return w;
        }

        LeverAuraConfig lever = LeverAuraConfig.getInstance();
        Runnable saveLever = lever::save;
        header(w, contentX, y, contentWidth, "Section Levers");
        toggle(w, contentX, y, "S2 Section Levers", lever::isSectionLeversRaw, lever::setSectionLevers, requestRebuild, saveLever);
        if (lever.isSectionLeversRaw()) {
            toggle(w, contentX, y, "S2 Section Levers Early", lever::isSectionLeversEarlyRaw, lever::setSectionLeversEarly, null, saveLever);
        }

        header(w, contentX, y, contentWidth, "Timing & Range");
        slider(w, contentX, y[0], BTN_W, () -> String.format(Locale.US, "Boss Lever Range: %.1f", lever.getRange()),
                (lever.getRange() - LeverAuraConfig.MIN_RANGE) / (LeverAuraConfig.MAX_RANGE - LeverAuraConfig.MIN_RANGE),
                v -> lever.setRange(LeverAuraConfig.MIN_RANGE + v * (LeverAuraConfig.MAX_RANGE - LeverAuraConfig.MIN_RANGE)),
                saveLever);
        y[0] += 24;
        slider(w, contentX, y[0], BTN_W, () -> "Boss Lever CPS: " + lever.getCps(),
                norm(lever.getCps(), LeverAuraConfig.MIN_CPS, LeverAuraConfig.MAX_CPS),
                v -> lever.setCps(denorm(v, LeverAuraConfig.MIN_CPS, LeverAuraConfig.MAX_CPS)), saveLever);
        y[0] += 24;
        toggle(w, contentX, y, "Boss Lever Swing Hand", lever::isSwingHand, lever::setSwingHand, null, saveLever);
        toggle(w, contentX, y, "Boss Lever Chat Feedback", lever::isChatFeedback, lever::setChatFeedback, null, saveLever);

        return w;
    }

    private static void triggerbot(List<AbstractWidget> w, int x, int[] y, int width, int half, Runnable rebuild) {
        SecretTriggerbotConfig t = SecretTriggerbotConfig.getInstance();
        header(w, x, y, width, "Secret Triggerbot (dungeons)");
        toggle(w, x, y, "Secret Triggerbot", t::isEnabled, t::setEnabled, rebuild, t::save);
        if (!t.isEnabled()) {
            return;
        }
        slider(w, x, y[0], half, () -> "Delay: " + t.getDelayMs() + "ms",
                norm(t.getDelayMs(), 0, SecretTriggerbotConfig.MAX_DELAY_MS),
                v -> t.setDelayMs(denorm(v, 0, SecretTriggerbotConfig.MAX_DELAY_MS)), t::save);
        slider(w, x + half + 8, y[0], half, () -> "Cooldown: " + t.getCooldownMs() + "ms",
                norm(t.getCooldownMs(), 0, SecretTriggerbotConfig.MAX_COOLDOWN_MS),
                v -> t.setCooldownMs(denorm(v, 0, SecretTriggerbotConfig.MAX_COOLDOWN_MS)), t::save);
        y[0] += 24;
        toggle(w, x, y, "Swap To Slot", t::isSwapEnabled, t::setSwapEnabled, rebuild, t::save);
        if (t.isSwapEnabled()) {
            slider(w, x, y[0], half, () -> "Swap Slot: " + t.getSwapSlot(), (t.getSwapSlot() - 1) / 8.0,
                    v -> t.setSwapSlot(1 + (int) Math.round(v * 8)), t::save);
            w.add(onOffButton("Swap Back", t::isSwapBack, t::setSwapBack, t::save, null, x + half + 8, y[0], half, 20));
            y[0] += 24;
        }
        w.add(com.killer560.hub.gui.ModuleKeybinds.row(com.killer560.hub.gui.ModuleKeybinds.SECRET_TRIGGERBOT_ID, x, y[0], width));
        y[0] += 24;
    }

    private static double norm(int value, int min, int max) {
        return (value - min) / (double) (max - min);
    }

    private static int denorm(double v, int min, int max) {
        return (int) Math.round(min + v * (max - min));
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(SectionHeaders.row(x, y[0], width, text, true));
        y[0] += 16;
    }

    /** ON/OFF toggle button; a non-null {@code rebuild} rebuilds the tab (used by master toggles). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        toggle(w, x, y, name, getter, setter, rebuild, () -> CheatUtilsConfig.getInstance().save());
    }

    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild, Runnable save) {
        w.add(onOffButton(name, getter::get, setter, save, rebuild, x, y[0], BTN_W, 20));
        y[0] += 24;
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply) {
        slider(w, x, y, width, text, normalized, apply, () -> CheatUtilsConfig.getInstance().save());
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply, Runnable save) {
        w.add(normSlider(x, y, width, 20, text, normalized, apply, save));
    }
}
