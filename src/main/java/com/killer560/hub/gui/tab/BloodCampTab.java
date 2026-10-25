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

        widgets.add(onOffButton("Blood Camp", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(onOffButton("Show Overlay", cfg::getShowOverlayRaw, cfg::setShowOverlay, cfg::save,
                null, col2aX, y, col2W, 18));

        widgets.add(onOffButton("Kill Popup", cfg::getKillPopupRaw, cfg::setKillPopup, cfg::save,
                requestRebuild, col2bX, y, col2W, 18));
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
                    saveOnRelease(() -> cfg.save());
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
                    // tenths, the precision the label shows (so the knob stops on each one)
                    cfg.setTimerTextScale(Math.round((minScale + (float) this.value * (maxScale - minScale)) * 10f) / 10f);
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += 22;

            // killer560, 2026-10-18: a color for Blood Camp's text - the countdown on each box. White by default.
            widgets.add(SettingsButtonWidget.builder(
                    com.killer560.hub.gui.ColorSwatch.label("Timer Text Color", cfg.getTimerTextColor()), btn -> {
                        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                        com.killer560.hub.compat.McCompat.setScreen(mc, new com.killer560.hub.gui.ColorPickerScreen(
                                com.killer560.hub.compat.McCompat.screen(mc), "Timer Text Color", cfg.getTimerTextColor(),
                                BloodCampConfig.DEFAULT_TIMER_TEXT_COLOR, argb -> {
                            cfg.setTimerTextColor(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;

            widgets.add(onOffButton("Spawn Line", cfg::getSpawnLineRaw, cfg::setSpawnLine, cfg::save,
                    requestRebuild, contentX, y, contentWidth, 18));
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
                        saveOnRelease(() -> cfg.save());
                    }
                });
                y += 22;
            }
        }

        // Kill Sound (killer560, 2026-10-07; 2026-10-18 "The sound should play only on the tick the trigger bot should
        // hit to kill a mob, not every tick while a mob is on the wall or starting to spawn"): one sound, on the hit tick.
        // The Countdown Start Sound was that "starting to spawn" sound and is gone; its saved value is still read.
        widgets.add(onOffButton("Kill Sound", cfg::isKillSound, cfg::setKillSound, cfg::save,
                requestRebuild, contentX, y, contentWidth, 18));
        y += 22;

        if (cfg.isKillSound()) {
            widgets.add(SettingsButtonWidget.cycling(killSoundText(cfg), (btn, dir) -> {
                        cfg.setKillSoundId(nextSound(dir, cfg.getKillSoundId()));
                        cfg.save();
                        btn.setMessage(killSoundText(cfg));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, soundVolumeText(cfg), cfg.getSoundVolume()) {
                @Override
                protected void updateMessage() {
                    setMessage(soundVolumeText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setSoundVolume(Math.round(this.value * 20) / 20f);
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += 22;
        }

        return widgets;
    }

    private static String nextSound(int dir, String id) {
        return SettingsButtonWidget.step(com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(id), dir).name();
    }

    private static Component killSoundText(BloodCampConfig cfg) {
        return Component.literal("Kill Sound Type: "
                + com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(cfg.getKillSoundId()).label);
    }

    private static Component soundVolumeText(BloodCampConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Sound Volume: %.2f", cfg.getSoundVolume()));
    }

    private static Component timerScaleText(BloodCampConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Timer Text Scale: %.1fx", cfg.getTimerTextScale()));
    }

    private static Component spawnLineWidthText(BloodCampConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Spawn Line Width: %.1f", cfg.getSpawnLineWidth()));
    }
}
