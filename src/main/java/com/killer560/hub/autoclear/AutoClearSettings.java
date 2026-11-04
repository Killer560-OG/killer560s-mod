package com.killer560.hub.autoclear;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Auto Clear's rows for the shared "Auto Clear" tab, which Auto Secret owns: the tab calls {@link #addMaster} for its
 * first row (the Auto Clear switch) and {@link #addSettings} for this section. AutoRoutesTab's house style - a header,
 * then even two-column rows, 20 high on a 24 step - and nothing of the section while the switch is OFF. Tooltips live in {@code SettingTooltipsData} under the
 * bare labels.
 *
 * <p>The toggle KEY row cannot use the tab's own key capture (the tab is not this class), so it captures by polling:
 * after its button is pressed the next key or mouse button is taken, routed by ModScreen through AutoClearTab.
 */
public final class AutoClearSettings {

    private static final int GAP = 6;

    /** Waiting for a key for the toggle row. */
    private static boolean capturing = false;
    private static Runnable captureRebuild = null;
    private static boolean[] downAtCapture = null;

    private AutoClearSettings() {
    }

    /**
     * Auto Clear's ON/OFF row, full width at {@code x}, {@code y[0]} (advanced past it). The tab puts it first: it is the
     * tab's one saved switch, and the card's (Dungeon Autopilot and Auto Secret are started and stopped, not switched).
     */
    public static void addMaster(List<AbstractWidget> widgets, int x, int[] y, int width, Runnable rebuild) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        AutoClearConfig cfg = AutoClearConfig.getInstance();
        toggleCell(widgets, x, y[0], width, "Auto Clear", cfg::isEnabledRaw, cfg::setEnabled, rebuild);
        y[0] += 24;
    }

    /**
     * Appends Auto Clear's section at {@code x}, {@code y[0]} (advanced past what it adds), {@code width} wide.
     * {@code rebuild} rebuilds the tab (the master switch collapses the section).
     */
    public static void addSettings(List<AbstractWidget> widgets, int x, int[] y, int width, Runnable rebuild) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        AutoClearConfig cfg = AutoClearConfig.getInstance();
        int half = (width - GAP) / 2;
        int right = Math.max(1, width - half - GAP);

        // The Auto Clear switch itself is the tab's first row ({@link #addMaster}); its settings show only while it is on.
        if (!cfg.isEnabledRaw()) {
            return;
        }
        y[0] += 6;
        widgets.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header("Auto Clear", true),
                Minecraft.getInstance().font));
        y[0] += 16;

        widgets.add(SettingsButtonWidget.cycleEnum(() -> modeText(cfg), cfg::getMode, cfg::setMode, cfg::save).bounds(x, y[0], half, 20).build());
        widgets.add(SettingsButtonWidget.cycleEnum(() -> weaponText(cfg), cfg::getWeapon, cfg::setWeapon, cfg::save).bounds(x + half + GAP, y[0], right, 20).build());
        y[0] += 24;

        toggleCell(widgets, x, y[0], half, "Hyperion Hops", cfg::isHyperionHops, cfg::setHyperionHops, null);
        toggleCell(widgets, x + half + GAP, y[0], right, "Pick Up Wither Keys", cfg::isPickUpKeys, cfg::setPickUpKeys, null);
        y[0] += 24;

        toggleCell(widgets, x, y[0], half, "Walk When Faster", cfg::isWalkWhenFaster, cfg::setWalkWhenFaster, null);
        toggleCell(widgets, x + half + GAP, y[0], right, "Predictive Bats", cfg::isPredictiveBats, cfg::setPredictiveBats,
                null);
        y[0] += 24;

        widgets.add(SettingsButtonWidget.cycleEnum(() -> oneTapText(cfg), cfg::getOneTap, cfg::setOneTap, cfg::save).bounds(x, y[0], half, 20).build());
        y[0] += 24;

        int span = AutoClearConfig.MAX_MOB_TIMEOUT_TICKS - AutoClearConfig.MIN_MOB_TIMEOUT_TICKS;
        Supplier<String> timeoutText = () -> String.format(Locale.US, "Mob Timeout: %.1fs", cfg.getMobTimeoutTicks() / 20.0);
        widgets.add(new ThemedSliderButton(x, y[0], half, 20, Component.literal(timeoutText.get()),
                Math.max(0.0, Math.min(1.0, (cfg.getMobTimeoutTicks() - AutoClearConfig.MIN_MOB_TIMEOUT_TICKS) / (double) span))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(timeoutText.get()));
            }

            @Override
            protected void applyValue() {
                cfg.setMobTimeoutTicks((int) Math.round(AutoClearConfig.MIN_MOB_TIMEOUT_TICKS + this.value * span));
                saveOnRelease(() -> cfg.save());
            }
        });
        widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
            capturing = true;
            captureRebuild = rebuild;
            downAtCapture = null;
            btn.setMessage(keyText(cfg));
        }).bounds(x + half + GAP, y[0], right, 20).build());
        y[0] += 24;
    }

    /** Whether the Clear Toggle Key row waits for a bind. AutoClearTab (a KeyCaptureTab) answers for it, so ModScreen
     *  routes the next key or mouse button here like every other key row (2026-11-02; it used to poll keys only, so a
     *  mouse button could never be bound and Escape also closed the menu). */
    public static boolean isCapturing() {
        return capturing;
    }

    /** Binds {@code code} (a key, a KeyUtil mouse code, or KeyUtil.NONE to clear) and stops waiting. */
    public static void finishCapture(int code) {
        if (!capturing) {
            return;
        }
        AutoClearConfig cfg = AutoClearConfig.getInstance();
        cfg.setKeybind(code);
        cfg.save();
        cancelCapture();
    }

    /** Stops waiting and keeps the old bind (Escape). */
    public static void cancelCapture() {
        capturing = false;
        captureRebuild = null;
        downAtCapture = null;
    }

    /** END_CLIENT_TICK: kept registered, nothing to poll any more (the capture is routed by ModScreen). */
    static void pollCapture(Minecraft client) {
    }

    private static Component modeText(AutoClearConfig cfg) {
        return Component.literal("Clear Mode: §e" + cfg.getMode().label());
    }

    private static Component weaponText(AutoClearConfig cfg) {
        return Component.literal("Clear Weapon: §e" + cfg.getWeapon().label());
    }

    private static Component oneTapText(AutoClearConfig cfg) {
        return Component.literal("One-Tap Warp: §e" + cfg.getOneTap().label());
    }

    private static Component keyText(AutoClearConfig cfg) {
        if (capturing) {
            return Component.literal("Clear Toggle Key: §ePress any key...");
        }
        int key = cfg.getKeybind();
        String name = key == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(key);
        return Component.literal("Clear Toggle Key: " + name);
    }

    private static void toggleCell(List<AbstractWidget> w, int x, int y, int width, String name, Supplier<Boolean> getter,
                                   Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            AutoClearConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y, Math.max(1, width), 20).build());
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }
}
