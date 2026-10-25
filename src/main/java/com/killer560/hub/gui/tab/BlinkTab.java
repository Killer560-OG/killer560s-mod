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

/**
 * Blink - cheat-only (see {@link com.killer560.hub.blink.BlinkFeature}). The switch, the key, Toggle/Hold, Max Ticks,
 * what the limit does, and the HUD line, server box and chat lines. Only added to its category behind
 * {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 */
public class BlinkTab extends BaseTab implements KeyCaptureTab {

    private static final int ROW = 18;

    private boolean capturing;

    public BlinkTab() {
        super("Blink");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
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
        cfg.setKeyCode(code);
        cfg.save();
        capturing = false;
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
        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Blink", true), font));
        y += 16;
        String[] warning = {
                "§cHolds every packet you send and releases them in one burst. To the",
                "§cserver it is a lag spike; anticheats look for exactly that. Ban risk.",
                "§7Tested against GrimAC only - that says nothing about Watchdog.",
                "§7GrimAC accepts holds up to 22 ticks; every tick past that is a flag."
        };
        for (String line : warning) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 6;

        w.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Blink Key: §ePress any key..."));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), dir));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;

        double min = BlinkConfig.MIN_TICKS;
        double span = BlinkConfig.MAX_TICKS - min;
        w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, ticksText(cfg),
                Math.max(0.0, Math.min(1.0, (cfg.getMaxTicks() - min) / span))) {
            @Override
            protected void updateMessage() {
                setMessage(ticksText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setMaxTicks((int) Math.round(min + this.value * span));
                saveOnRelease(() -> cfg.save());
            }
        });
        y += ROW + 4;
        w.add(SettingsButtonWidget.cycling(limitText(cfg), (btn, dir) -> {
                    cfg.setLimitAction(SettingsButtonWidget.step(cfg.getLimitAction(), dir));
                    cfg.save();
                    btn.setMessage(limitText(cfg));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;

        w.add(SettingsButtonWidget.builder(onOff("Release On Hit", cfg.isReleaseOnHit()), btn -> {
                    cfg.setReleaseOnHit(!cfg.isReleaseOnHit());
                    cfg.save();
                    btn.setMessage(onOff("Release On Hit", cfg.isReleaseOnHit()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Show HUD", cfg.isShowHud()), btn -> {
                    cfg.setShowHud(!cfg.isShowHud());
                    cfg.save();
                    btn.setMessage(onOff("Show HUD", cfg.isShowHud()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Show Server Position", cfg.isShowServerBox()), btn -> {
                    cfg.setShowServerBox(!cfg.isShowServerBox());
                    cfg.save();
                    btn.setMessage(onOff("Show Server Position", cfg.isShowServerBox()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Chat Messages", cfg.isChatMessages()), btn -> {
                    cfg.setChatMessages(!cfg.isChatMessages());
                    cfg.save();
                    btn.setMessage(onOff("Chat Messages", cfg.isChatMessages()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        return w;
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
    }

    private Component keyText(BlinkConfig cfg) {
        if (capturing) {
            return Component.literal("Blink Key: §ePress any key...");
        }
        int code = cfg.getKeyCode();
        return Component.literal("Blink Key: " + (code == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(code)));
    }

    private static Component modeText(BlinkConfig cfg) {
        return Component.literal("Mode: " + cfg.getMode().label);
    }

    private static Component limitText(BlinkConfig cfg) {
        return Component.literal("At Max Ticks: " + cfg.getLimitAction().label);
    }

    private static Component ticksText(BlinkConfig cfg) {
        int t = cfg.getMaxTicks();
        String note = t > BlinkConfig.GRIM_MAX_TICKS ? " §c- over GrimAC's " + BlinkConfig.GRIM_MAX_TICKS
                : t == BlinkConfig.GRIM_MAX_TICKS ? " §7- GrimAC's max" : "";
        return Component.literal(String.format(Locale.US, "Max Ticks: %d (%.2fs)", t, t / 20.0) + note);
    }
}
