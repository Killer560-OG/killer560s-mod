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
 * The switch and the display rows, then a Charge section (key, Toggle/Hold, ticks) and a Hold section (key, ticks). No
 * explanation lines: each row explains itself in its hover tooltip (gui/SettingTooltipsData, killer560 2026-10-26).
 * Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
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
        w.add(SettingsButtonWidget.cycling(Component.literal("HUD Style: " + cfg.getHudStyle().label), (btn, dir) -> {
                    cfg.setHudStyle(SettingsButtonWidget.step(cfg.getHudStyle(), dir));
                    cfg.save();
                    btn.setMessage(Component.literal("HUD Style: " + cfg.getHudStyle().label));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        y = toggle(w, contentX, y, contentWidth, "Show Server Position", cfg::isShowServerBox,
                v -> cfg.setShowServerBox(v), cfg);
        y = toggle(w, contentX, y, contentWidth, "Chat Messages", cfg::isChatMessages, v -> cfg.setChatMessages(v), cfg);
        y = toggle(w, contentX, y, contentWidth, "Debug Log", cfg::isDebugLog, v -> cfg.setDebugLog(v), cfg);
        y += 6;

        // ---- Charge
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Charge", true));
        y += 16;
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
        y += ROW + 4;
        y = slider(w, contentX, y, contentWidth, "Charge Ticks", cfg::getChargeTicks, cfg::setChargeTicks, cfg);
        y += 6;

        // ---- Hold
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Hold", true));
        y += 16;
        w.add(SettingsButtonWidget.builder(keyText("Hold Key", cfg.getHoldKeyCode(), capturing == 2), btn -> {
                    capturing = 2;
                    btn.setMessage(Component.literal("Hold Key: §ePress any key..."));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        slider(w, contentX, y, contentWidth, "Hold Ticks", cfg::getHoldTicks, cfg::setHoldTicks, cfg);
        return w;
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
