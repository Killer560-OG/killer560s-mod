package com.killer560.hub.gui.tab;

import com.killer560.hub.blink.BlinkConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Blink - cheat-only (see {@link com.killer560.hub.blink.BlinkFeature} and {@link com.killer560.hub.blink.BlinkCharge}).
 * The switch and the display rows, then a Charge section (key, Toggle/Hold, ticks) and a Hold section (key, ticks), each
 * with a line saying what it does (killer560, 2026-10-25). Only added to its category behind
 * {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 */
public class BlinkTab extends BaseTab implements KeyCaptureTab {

    private static final int ROW = 18;

    /** Which key is being captured: 0 none, 1 Charge, 2 Hold. */
    private int capturing;

    public BlinkTab() {
        super("Blink");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        apply(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        apply(KeyUtil.codeForMouseButton(button));
    }

    private void apply(int code) {
        BlinkConfig cfg = BlinkConfig.getInstance();
        if (capturing == 1) {
            cfg.setChargeKeyCode(code);
        } else if (capturing == 2) {
            cfg.setHoldKeyCode(code);
        }
        cfg.save();
        capturing = 0;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        var font = Minecraft.getInstance().font;
        BlinkConfig cfg = BlinkConfig.getInstance();
        int y = contentY;

        w.add(SettingsButtonWidget.builder(Component.literal("Blink: " + (cfg.isEnabled() ? "§aON" : "§cOFF")), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 6;
        if (!cfg.isEnabled()) {
            return w;
        }
        y = toggle(w, contentX, y, contentWidth, "Show HUD", cfg::isShowHud, v -> cfg.setShowHud(v), cfg);
        y = toggle(w, contentX, y, contentWidth, "Show Server Position", cfg::isShowServerBox,
                v -> cfg.setShowServerBox(v), cfg);
        y = note(w, font, contentX, y, contentWidth, "§7Red box: where the server has you during a hold. Green box: where a");
        y = note(w, font, contentX, y, contentWidth, "§7charge shot would land you if you fired now.");
        y += 4;
        y = toggle(w, contentX, y, contentWidth, "Chat Messages", cfg::isChatMessages, v -> cfg.setChatMessages(v), cfg);
        y += 6;

        // ---- Charge
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Charge", true));
        y += 16;
        y = note(w, font, contentX, y, contentWidth, "§7Stand still to bank ticks (a terminal, levers, mining all work). Fire it");
        y = note(w, font, contentX, y, contentWidth, "§7and you shoot that many ticks of running forward at once. A full bank");
        y = note(w, font, contentX, y, contentWidth, "§7waits for you. Fired while standing still, it goes off when you move.");
        y += 4;
        w.add(SettingsButtonWidget.builder(keyText("Charge Key", cfg.getChargeKeyCode(), capturing == 1), btn -> {
                    capturing = 1;
                    btn.setMessage(Component.literal("Charge Key: §ePress any key..."));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setChargeMode(SettingsButtonWidget.step(cfg.getChargeMode(), dir));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 2;
        y = note(w, font, contentX, y, contentWidth, "§7Toggle: press to charge, press again to fire. Hold: charge while held,");
        y = note(w, font, contentX, y, contentWidth, "§7fire when you let go.");
        y += 4;
        y = slider(w, contentX, y, contentWidth, "Charge Ticks", cfg::getChargeTicks, cfg::setChargeTicks, cfg);
        y += 6;

        // ---- Hold
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Hold", true));
        y += 16;
        y = note(w, font, contentX, y, contentWidth, "§7Hold the key: everything you send waits. Let go: it all goes at once.");
        y = note(w, font, contentX, y, contentWidth, "§7Menu clicks always go through. At Hold Ticks it lets go on its own.");
        y += 4;
        w.add(SettingsButtonWidget.builder(keyText("Hold Key", cfg.getHoldKeyCode(), capturing == 2), btn -> {
                    capturing = 2;
                    btn.setMessage(Component.literal("Hold Key: §ePress any key..."));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        slider(w, contentX, y, contentWidth, "Hold Ticks", cfg::getHoldTicks, cfg::setHoldTicks, cfg);
        return w;
    }

    private static int note(List<AbstractWidget> w, net.minecraft.client.gui.Font font, int x, int y, int width, String text) {
        w.add(new StringWidget(x, y, width, 10, Component.literal(text), font));
        return y + 11;
    }

    private interface BoolSetter {
        void set(boolean v);
    }

    private static int toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                              java.util.function.BooleanSupplier get, BoolSetter set, BlinkConfig cfg) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
                    set.set(!get.getAsBoolean());
                    cfg.save();
                    btn.setMessage(onOff(label, get.getAsBoolean()));
                }).bounds(x, y, width, ROW).build());
        return y + ROW + 4;
    }

    private static int slider(List<AbstractWidget> w, int x, int y, int width, String label, IntSupplier get,
                              IntConsumer set, BlinkConfig cfg) {
        double min = BlinkConfig.MIN_TICKS;
        double span = BlinkConfig.MAX_TICKS - min;
        w.add(new ThemedSliderButton(x, y, width, ROW, ticksText(label, get.getAsInt()),
                Math.max(0.0, Math.min(1.0, (get.getAsInt() - min) / span))) {
            @Override
            protected void updateMessage() {
                setMessage(ticksText(label, get.getAsInt()));
            }

            @Override
            protected void applyValue() {
                set.accept((int) Math.round(min + this.value * span));
                saveOnRelease(() -> cfg.save());
            }
        });
        return y + ROW + 4;
    }

    private static Component keyText(String label, int code, boolean capturing) {
        if (capturing) {
            return Component.literal(label + ": §ePress any key...");
        }
        return Component.literal(label + ": " + (code == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(code)));
    }

    private static Component modeText(BlinkConfig cfg) {
        return Component.literal("Charge Mode: " + cfg.getChargeMode().label);
    }

    private static Component ticksText(String label, int t) {
        return Component.literal(String.format(Locale.US, "%s: %d (%.2fs)", label, t, t / 20.0));
    }
}
