package com.killer560.hub.gui.tab;

import com.killer560.hub.gifplayer.GifAudioFeature;
import com.killer560.hub.gifplayer.GifPlayerConfig;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;

/** GIF Player settings: separate master toggles for GIFs and audio (so either half can be muted
 *  independently of the other and of the per-file toggles), speed/volume sliders that can also be
 *  typed in directly, a shortcut to open the drop folder, and a per-file toggle row for every gif and
 *  audio file found in it - several can be enabled at once, each independently. Position/size of each
 *  gif overlay are adjusted through the same HUD editor the RNG Meter overlay uses, not here. */
public class GifPlayerTab extends BaseTab {

    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 4.0f;
    private static final int FILE_ROW_H = 18;

    public GifPlayerTab() {
        super("GIF Player");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(gifEnabledText(), btn -> {
                    GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(gifEnabledText());
                }).bounds(contentX, y, 220, 20).build());
        widgets.add(SettingsButtonWidget.builder(audioEnabledText(), btn -> {
                    GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                    cfg.setAudioEnabled(!cfg.isAudioEnabled());
                    cfg.save();
                    GifAudioFeature.setEnabled(cfg.isAudioEnabled());
                    btn.setMessage(audioEnabledText());
                }).bounds(contentX + 226, y, contentWidth - 226, 20).build());
        y += 26;

        double speedNormalized = (GifPlayerConfig.getInstance().getSpeedMultiplier() - MIN_SPEED) / (MAX_SPEED - MIN_SPEED);
        widgets.add(new ThemedSliderButton(contentX, y, 160, 20, speedText(), speedNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(speedText());
            }

            @Override
            protected void applyValue() {
                GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                cfg.setSpeedMultiplier(MIN_SPEED + (float) this.value * (MAX_SPEED - MIN_SPEED));
                cfg.save();
            }
        });
        EditBox speedField = new EditBox(Minecraft.getInstance().font, contentX + 166, y, 60, 20,
                Component.literal("Speed"));
        speedField.setMaxLength(8);
        speedField.setValue(String.format("%.2f", GifPlayerConfig.getInstance().getSpeedMultiplier()));
        widgets.add(speedField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    Float parsed = parseFloat(speedField.getValue());
                    if (parsed == null) {
                        ModOverlayMessage.show("§c[Killer560's Mod] Invalid speed - enter a number like 1.5", 3000);
                        return;
                    }
                    GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                    cfg.setSpeedMultiplier(Math.max(MIN_SPEED, Math.min(MAX_SPEED, parsed)));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + 230, y, contentWidth - 230, 20).build());
        y += 26;

        widgets.add(new ThemedSliderButton(contentX, y, 160, 20, volumeText(), GifPlayerConfig.getInstance().getVolume()) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                cfg.setVolume((float) this.value);
                cfg.save();
                GifAudioFeature.applyVolume();
            }
        });
        EditBox volumeField = new EditBox(Minecraft.getInstance().font, contentX + 166, y, 60, 20,
                Component.literal("Volume"));
        volumeField.setMaxLength(8);
        volumeField.setValue(String.format("%.0f", GifPlayerConfig.getInstance().getVolume() * 100));
        widgets.add(volumeField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    Float parsed = parseFloat(volumeField.getValue());
                    if (parsed == null) {
                        ModOverlayMessage.show("§c[Killer560's Mod] Invalid volume - enter a number like 50 (%)", 3000);
                        return;
                    }
                    GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                    cfg.setVolume(Math.max(0f, Math.min(100f, parsed)) / 100f);
                    cfg.save();
                    GifAudioFeature.applyVolume();
                    requestRebuild.run();
                }).bounds(contentX + 230, y, contentWidth - 230, 20).build());
        y += 30;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open GIF Folder"), btn -> openFolder())
                .bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Rescan Folder"), btn -> {
                    GifPlayerFeature.reload();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 28;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("GIFs (toggle which ones show at once):"), Minecraft.getInstance().font));
        y += 14;
        List<String> gifFiles = GifPlayerFeature.discoverFileNames();
        if (gifFiles.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No .gif files found."), Minecraft.getInstance().font));
            y += FILE_ROW_H;
        }
        for (String name : gifFiles) {
            boolean on = GifPlayerConfig.getInstance().isGifFileEnabled(name);
            widgets.add(SettingsButtonWidget.builder(fileToggleText(name, on), btn -> {
                        GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                        cfg.setGifFileEnabled(name, !cfg.isGifFileEnabled(name));
                        cfg.save();
                        GifPlayerFeature.reload();
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, FILE_ROW_H).build());
            y += FILE_ROW_H + 2;
        }
        y += 8;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Audio (WAV/AIFF/AU native, .mp3 auto-converted):"), Minecraft.getInstance().font));
        y += 14;
        List<String> audioFiles = GifAudioFeature.discoverFileNames();
        if (audioFiles.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No audio files found."), Minecraft.getInstance().font));
            y += FILE_ROW_H;
        }
        for (String name : audioFiles) {
            boolean on = GifPlayerConfig.getInstance().isAudioFileEnabled(name);
            widgets.add(SettingsButtonWidget.builder(fileToggleText(name, on), btn -> {
                        GifPlayerConfig cfg = GifPlayerConfig.getInstance();
                        cfg.setAudioFileEnabled(name, !cfg.isAudioFileEnabled(name));
                        cfg.save();
                        GifPlayerFeature.reload();
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, FILE_ROW_H).build());
            y += FILE_ROW_H + 2;
        }
        return widgets;
    }

    private static Float parseFloat(String text) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void openFolder() {
        try {
            Desktop.getDesktop().open(GifPlayerFeature.folder().toFile());
        } catch (Exception e) {
            ModOverlayMessage.show("§c[Killer560's Mod] Couldn't open GIF folder: " + e.getMessage(), 4000);
        }
    }

    private static Component gifEnabledText() {
        return Component.literal("GIFs: "
                + (GifPlayerConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component audioEnabledText() {
        return Component.literal("Audio: "
                + (GifPlayerConfig.getInstance().isAudioEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component speedText() {
        return Component.literal(String.format("Speed: §b%.2fx", GifPlayerConfig.getInstance().getSpeedMultiplier()));
    }

    private static Component volumeText() {
        return Component.literal(String.format("Volume: §b%.0f%%", GifPlayerConfig.getInstance().getVolume() * 100));
    }

    private static Component fileToggleText(String filename, boolean on) {
        return Component.literal((on ? "§a[ON] " : "§c[OFF] ") + "§f" + filename);
    }
}
