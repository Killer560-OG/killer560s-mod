package com.killer560.hub.autoclear;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Auto Clear's rows for the shared "Auto Clear" tab, which Auto Secret owns: the tab calls {@link #addSettings} for
 * this section. AutoRoutesTab's house style - a header, a full-width master switch, then even two-column rows, 20 high
 * on a 24 step - and nothing below the switch while it is OFF. Tooltips live in {@code SettingTooltipsData} under the
 * bare labels.
 *
 * <p>The toggle KEY row cannot use the tab's own key capture (the tab is not this class), so it captures by polling:
 * after its button is pressed the next key that goes down (Escape clears) is taken, from {@link #pollCapture}.
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

        y[0] += 6;
        widgets.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header("Auto Clear", true),
                Minecraft.getInstance().font));
        y[0] += 16;
        toggleCell(widgets, x, y[0], width, "Auto Clear", cfg::isEnabledRaw, cfg::setEnabled, rebuild);
        y[0] += 24;
        if (!cfg.isEnabledRaw()) {
            return;
        }

        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
            AutoClearConfig.Mode[] all = AutoClearConfig.Mode.values();
            cfg.setMode(all[(cfg.getMode().ordinal() + 1) % all.length]);
            cfg.save();
            btn.setMessage(modeText(cfg));
        }).bounds(x, y[0], half, 20).build());
        widgets.add(SettingsButtonWidget.builder(weaponText(cfg), btn -> {
            AutoClearConfig.Weapon[] all = AutoClearConfig.Weapon.values();
            cfg.setWeapon(all[(cfg.getWeapon().ordinal() + 1) % all.length]);
            cfg.save();
            btn.setMessage(weaponText(cfg));
        }).bounds(x + half + GAP, y[0], right, 20).build());
        y[0] += 24;

        toggleCell(widgets, x, y[0], half, "Hyperion Hops", cfg::isHyperionHops, cfg::setHyperionHops, null);
        toggleCell(widgets, x + half + GAP, y[0], right, "Clear Status HUD", cfg::isStatusHud, cfg::setStatusHud, null);
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
                cfg.save();
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

    /**
     * END_CLIENT_TICK: while the key row waits, the first key that goes DOWN (not one already held when it started -
     * the mouse button that pressed the row is not a key) becomes the toggle key; Escape clears it.
     */
    static void pollCapture(Minecraft client) {
        if (!capturing || client.getWindow() == null) {
            return;
        }
        if (downAtCapture == null) {
            downAtCapture = new boolean[org.lwjgl.glfw.GLFW.GLFW_KEY_LAST + 1];
            for (int k = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE; k <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST; k++) {
                downAtCapture[k] = KeyUtil.isKeyDown(client.getWindow(), k);
            }
            return;
        }
        for (int k = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE; k <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST; k++) {
            boolean down = KeyUtil.isKeyDown(client.getWindow(), k);
            if (down && !downAtCapture[k]) {
                AutoClearConfig cfg = AutoClearConfig.getInstance();
                cfg.setKeybind(k == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : k);
                cfg.save();
                capturing = false;
                Runnable r = captureRebuild;
                captureRebuild = null;
                if (r != null) {
                    r.run();
                }
                return;
            }
            downAtCapture[k] = down;
        }
    }

    private static Component modeText(AutoClearConfig cfg) {
        return Component.literal("Clear Mode: §e" + cfg.getMode().label());
    }

    private static Component weaponText(AutoClearConfig cfg) {
        return Component.literal("Clear Weapon: §e" + cfg.getWeapon().label());
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
