package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.shorts.ShortsConfig;
import com.killer560.hub.shorts.ShortsFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** "YT Shorts" settings - see {@link ShortsFeature}: a companion Edge/Chrome app window pinned over Minecraft,
 *  controlled over the Chrome DevTools Protocol so keybinds never take focus away from the game. */
public class ShortsTab extends BaseTab implements KeyCaptureTab {

    private static final String[] KEY_LABELS = {"Show/Hide", "Next", "Previous", "Play/Pause", "Mute"};

    /** Index into {@link #KEY_LABELS} currently waiting for a key press, or -1. */
    private int capturing = -1;

    public ShortsTab() {
        super("YT Shorts");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ShortsConfig cfg = ShortsConfig.getInstance();

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("YT Shorts", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    if (!cfg.isEnabled()) {
                        ShortsFeature.closeBrowser("feature disabled", false);
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!ShortsFeature.isSupported()) {
            widgets.add(label(contentX, y, contentWidth, "§c" + ShortsFeature.getUnsupportedReason()));
            return widgets;
        }

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(label(contentX, y, contentWidth, "§7Browser: §6" + ShortsFeature.statusText()));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(Component.literal(ShortsFeature.isRunning() ? "Relaunch Browser" : "Launch Browser"), btn -> {
                    ShortsFeature.launch();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Close Browser"), btn -> {
                    ShortsFeature.closeBrowser("closed from settings", true);
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal(cfg.isHidden() ? "Overlay: §cHidden" : "Overlay: §aShown"), btn -> {
                    cfg.setHidden(!cfg.isHidden());
                    cfg.save();
                    btn.setMessage(Component.literal(cfg.isHidden() ? "Overlay: §cHidden" : "Overlay: §aShown"));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(anchorText(cfg), btn -> {
                    cfg.setAnchor(cfg.getAnchor().next());
                    cfg.save();
                    btn.setMessage(anchorText(cfg));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        widgets.add(slider(col2aX, y, col2W, cfg::getSizePercent, cfg::setSizePercent,
                ShortsConfig.MIN_SIZE_PERCENT, ShortsConfig.MAX_SIZE_PERCENT, v -> "Size: " + v + "% of height", cfg));
        widgets.add(slider(col2bX, y, col2W, cfg::getMargin, cfg::setMargin,
                0, ShortsConfig.MAX_MARGIN, v -> "Margin: " + v + "px", cfg));
        y += 20;

        widgets.add(slider(col2aX, y, col2W, cfg::getOpacity, cfg::setOpacity,
                ShortsConfig.MIN_OPACITY, 100, v -> "Opacity: " + v + "%", cfg));
        double volNorm = Math.max(0, cfg.getVolume()) / 100.0;
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, volumeText(cfg), volNorm) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setVolume((int) Math.round(this.value * 100));
                cfg.save();
                ShortsFeature.applyVolume();
            }
        });
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Unfocused", cfg.isHideWhenUnfocused()), btn -> {
                    cfg.setHideWhenUnfocused(!cfg.isHideWhenUnfocused());
                    cfg.save();
                    btn.setMessage(onOff("Hide Unfocused", cfg.isHideWhenUnfocused()));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Pause When Hidden", cfg.isPauseWhenHidden()), btn -> {
                    cfg.setPauseWhenHidden(!cfg.isPauseWhenHidden());
                    cfg.save();
                    btn.setMessage(onOff("Pause When Hidden", cfg.isPauseWhenHidden()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        // 2026-09-16, killer560: "make an option for dark or light mode. Otherwise it is perfect." Applies live
        // to a running window (no relaunch needed); a fresh launch also starts in the chosen scheme.
        widgets.add(SettingsButtonWidget.builder(themeText(cfg), btn -> {
                    cfg.setTheme(cfg.getTheme().next());
                    cfg.save();
                    btn.setMessage(themeText(cfg));
                    ShortsFeature.applyTheme();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        widgets.add(label(contentX, y, contentWidth, "§6Keybinds §7(in-game only, Esc clears)"));
        y += 14;
        for (int i = 0; i < KEY_LABELS.length; i++) {
            final int index = i;
            int x = i % 2 == 0 ? col2aX : col2bX;
            widgets.add(SettingsButtonWidget.builder(keyText(cfg, index), btn -> {
                        capturing = index;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(x, y, col2W, 18).build());
            if (i % 2 == 1 || i == KEY_LABELS.length - 1) {
                y += 20;
            }
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Sign in to YouTube"), btn -> {
                    ShortsFeature.signInToYouTube();
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static ThemedSliderButton slider(int x, int y, int w, IntSupplier getter, IntConsumer setter, int min, int max,
                                             java.util.function.IntFunction<String> text, ShortsConfig cfg) {
        double norm = (getter.getAsInt() - (double) min) / (max - min);
        return new ThemedSliderButton(x, y, w, 18, Component.literal(text.apply(getter.getAsInt())), norm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.apply(getter.getAsInt())));
            }

            @Override
            protected void applyValue() {
                setter.accept((int) Math.round(min + this.value * (max - min)));
                cfg.save();
            }
        };
    }

    private static Component volumeText(ShortsConfig cfg) {
        return Component.literal(cfg.getVolume() < 0 ? "Volume: §7YouTube's own" : "Volume: " + cfg.getVolume() + "%");
    }

    private static Component anchorText(ShortsConfig cfg) {
        return Component.literal("Position: §6" + cfg.getAnchor().label);
    }

    private static Component themeText(ShortsConfig cfg) {
        return Component.literal("Theme: §6" + cfg.getTheme().label);
    }

    private static Component keyText(ShortsConfig cfg, int index) {
        int code = keyCode(cfg, index);
        String name = code < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal(KEY_LABELS[index] + ": §6" + name);
    }

    private static int keyCode(ShortsConfig cfg, int index) {
        return switch (index) {
            case 0 -> cfg.getToggleKey();
            case 1 -> cfg.getNextKey();
            case 2 -> cfg.getPreviousKey();
            case 3 -> cfg.getPlayPauseKey();
            default -> cfg.getMuteKey();
        };
    }

    private static StringWidget label(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal(text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturing >= 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        ShortsConfig cfg = ShortsConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        switch (capturing) {
            case 0 -> cfg.setToggleKey(code);
            case 1 -> cfg.setNextKey(code);
            case 2 -> cfg.setPreviousKey(code);
            case 3 -> cfg.setPlayPauseKey(code);
            case 4 -> cfg.setMuteKey(code);
            default -> {
                return;
            }
        }
        capturing = -1;
        cfg.save();
    }
}
