package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Secret Sound settings - split out of {@code DungeonAlertsTab} 2026-09-21 into the "Secrets" folder per
 *  killer560's menu-structure request ("secret sound, messages, secret waypoints, etherwarp waypoints,
 *  Secret Aura, Secret Triggerbot and Lever Aura all in one place"). Still backed by
 *  {@link DungeonAlertsConfig} - same JSON keys, unchanged - so this is a GUI-only move, not a config
 *  split. See {@link SecretSound} for the actual detection logic. */
public class SecretSoundTab extends BaseTab {

    public SecretSoundTab() {
        super("Secret Sound");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int colB = contentX + half + gap;
        int y = contentY;

        w.add(SettingsButtonWidget.builder(onOff("Secret Sound", cfg.secretSoundEnabled), btn -> {
                    cfg.secretSoundEnabled = !cfg.secretSoundEnabled;
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.secretSoundEnabled) {
            return w;
        }

        w.add(SettingsButtonWidget.builder(soundLabel(cfg), btn -> {
                    SecretSound.SoundChoice[] all = SecretSound.SoundChoice.values();
                    SecretSound.SoundChoice next = all[(SecretSound.SoundChoice.byName(cfg.secretSoundId).ordinal() + 1) % all.length];
                    cfg.secretSoundId = next.name();
                    cfg.save();
                    btn.setMessage(soundLabel(cfg));
                }).bounds(contentX, y, half, 18).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Play Sound"), btn -> SecretSound.play())
                .bounds(colB, y, half, 18).build());
        y += 22;

        w.add(new ThemedSliderButton(contentX, y, half, 18, volumeLabel(cfg), cfg.secretSoundVolume) {
            @Override
            protected void updateMessage() {
                setMessage(volumeLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.secretSoundVolume = Math.round(this.value * 10) / 10f;
                cfg.save();
            }
        });
        w.add(new ThemedSliderButton(colB, y, half, 18, pitchLabel(cfg), cfg.secretSoundPitch / 2.0) {
            @Override
            protected void updateMessage() {
                setMessage(pitchLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.secretSoundPitch = Math.round(this.value * 20) / 10f;
                cfg.save();
            }
        });

        return w;
    }

    private static Component soundLabel(DungeonAlertsConfig cfg) {
        return Component.literal("Sound: " + SecretSound.SoundChoice.byName(cfg.secretSoundId).label);
    }

    private static Component volumeLabel(DungeonAlertsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Volume: %.1f", cfg.secretSoundVolume));
    }

    private static Component pitchLabel(DungeonAlertsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Pitch: %.1f", cfg.secretSoundPitch));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
