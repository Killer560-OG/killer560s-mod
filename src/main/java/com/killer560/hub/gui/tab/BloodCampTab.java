package com.killer560.hub.gui.tab;

import com.killer560.hub.bloodcamp.BloodCampConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Blood Camp settings - see {@link com.killer560.hub.bloodcamp.BloodCampFeature}'s class doc for the
 *  real Noamm-ported mechanic this is built on. Every label reads a {@code ...Raw()} getter: the gated
 *  getters are false outside Skyblock, so reading them here made every row show OFF and made a click
 *  write true no matter what the saved value was. The cheat build's Trigger Bot and Aura rows moved to their
 *  own {@link AutoBloodCampTab} on 2026-10-07 (killer560: "make auto blood camp its own cheat tab"). */
public class BloodCampTab extends BaseTab {

    public BloodCampTab() {
        super("Blood Camp");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Blood Camp", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Overlay", cfg.getShowOverlayRaw()), btn -> {
                    cfg.setShowOverlay(!cfg.getShowOverlayRaw());
                    cfg.save();
                    btn.setMessage(onOff("Show Overlay", cfg.getShowOverlayRaw()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Kill Popup", cfg.getKillPopupRaw()), btn -> {
                    cfg.setKillPopup(!cfg.getKillPopupRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.getKillPopupRaw()) {
            double leadNorm = cfg.getKillPopupLeadTicks() / (double) BloodCampConfig.MAX_KILL_POPUP_LEAD_TICKS;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal("Kill Popup Lead: " + cfg.getKillPopupLeadTicks() + "t"), leadNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Kill Popup Lead: " + cfg.getKillPopupLeadTicks() + "t"));
                }

                @Override
                protected void applyValue() {
                    cfg.setKillPopupLeadTicks((int) Math.round(this.value * BloodCampConfig.MAX_KILL_POPUP_LEAD_TICKS));
                    cfg.save();
                }
            });
            y += 22;
        }

        if (cfg.getShowOverlayRaw()) {
            float minScale = BloodCampConfig.MIN_TIMER_TEXT_SCALE;
            float maxScale = BloodCampConfig.MAX_TIMER_TEXT_SCALE;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, timerScaleText(cfg),
                    (cfg.getTimerTextScale() - minScale) / (maxScale - minScale)) {
                @Override
                protected void updateMessage() {
                    setMessage(timerScaleText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setTimerTextScale(minScale + (float) this.value * (maxScale - minScale));
                    cfg.save();
                }
            });
            y += 22;

            widgets.add(SettingsButtonWidget.builder(onOff("Spawn Line", cfg.getSpawnLineRaw()), btn -> {
                        cfg.setSpawnLine(!cfg.getSpawnLineRaw());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;

            if (cfg.getSpawnLineRaw()) {
                float minW = BloodCampConfig.MIN_SPAWN_LINE_WIDTH;
                float maxW = BloodCampConfig.MAX_SPAWN_LINE_WIDTH;
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, spawnLineWidthText(cfg),
                        (cfg.getSpawnLineWidth() - minW) / (maxW - minW)) {
                    @Override
                    protected void updateMessage() {
                        setMessage(spawnLineWidthText(cfg));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setSpawnLineWidth(minW + (float) this.value * (maxW - minW));
                        cfg.save();
                    }
                });
                y += 22;
            }
        }

        // Countdown sounds (killer560, 2026-10-07): legit, both builds.
        widgets.add(SettingsButtonWidget.builder(onOff("Countdown Start Sound", cfg.isCountdownStartSound()), btn -> {
                    cfg.setCountdownStartSound(!cfg.isCountdownStartSound());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Kill Sound", cfg.isKillSound()), btn -> {
                    cfg.setKillSound(!cfg.isKillSound());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.isCountdownStartSound() || cfg.isKillSound()) {
            if (cfg.isCountdownStartSound()) {
                widgets.add(SettingsButtonWidget.builder(startSoundText(cfg), btn -> {
                            cfg.setCountdownStartSoundId(nextSound(cfg.getCountdownStartSoundId()));
                            cfg.save();
                            btn.setMessage(startSoundText(cfg));
                        }).bounds(col2aX, y, col2W, 18).build());
            }
            if (cfg.isKillSound()) {
                widgets.add(SettingsButtonWidget.builder(killSoundText(cfg), btn -> {
                            cfg.setKillSoundId(nextSound(cfg.getKillSoundId()));
                            cfg.save();
                            btn.setMessage(killSoundText(cfg));
                        }).bounds(col2bX, y, col2W, 18).build());
            }
            y += 22;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, soundVolumeText(cfg), cfg.getSoundVolume()) {
                @Override
                protected void updateMessage() {
                    setMessage(soundVolumeText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setSoundVolume(Math.round(this.value * 20) / 20f);
                    cfg.save();
                }
            });
            y += 22;
        }

        return widgets;
    }

    private static String nextSound(String id) {
        com.killer560.hub.dungeonalerts.SecretSound.SoundChoice[] all =
                com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.values();
        return all[(com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(id).ordinal() + 1) % all.length].name();
    }

    private static Component startSoundText(BloodCampConfig cfg) {
        return Component.literal("Start Sound Type: "
                + com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(cfg.getCountdownStartSoundId()).label);
    }

    private static Component killSoundText(BloodCampConfig cfg) {
        return Component.literal("Kill Sound Type: "
                + com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(cfg.getKillSoundId()).label);
    }

    private static Component soundVolumeText(BloodCampConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Sound Volume: %.2f", cfg.getSoundVolume()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component timerScaleText(BloodCampConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Timer Text Scale: %.1fx", cfg.getTimerTextScale()));
    }

    private static Component spawnLineWidthText(BloodCampConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Spawn Line Width: %.1f", cfg.getSpawnLineWidth()));
    }
}
