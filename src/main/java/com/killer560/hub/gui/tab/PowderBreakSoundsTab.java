package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.SecretSound.SoundChoice;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.powdermining.PowderBreakSound;
import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Powder Break Sounds (Mining): while powder mining, the blocks he breaks himself are silent (Mute) or play a sound he
 * picks (Custom, with volume and pitch and a Test button). OFF is the normal Minecraft break sound.
 */
public class PowderBreakSoundsTab extends BaseTab {

    public PowderBreakSoundsTab() {
        super("Powder Break Sounds");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Powder Break Sounds", cfg::isBreakSoundRaw, cfg::setBreakSound, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        if (!cfg.isBreakSoundRaw() && !isSearchScan()) {
            return w;
        }
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Mode", () -> cfg.isBreakSoundMute() ? "Mute" : "Custom",
                dir -> cfg.setBreakSoundMute(!cfg.isBreakSoundMute()), cfg::save);
        y += MiningQolRows.STEP;
        if (cfg.isBreakSoundMute() && !isSearchScan()) {
            return w;
        }
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Sound", () -> cfg.getBreakSoundChoice().label,
                dir -> cfg.setBreakSoundChoice(SettingsButtonWidget.step(cfg.getBreakSoundChoice(), dir)), cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Volume",
                () -> String.format(Locale.US, "%.1f", cfg.getBreakSoundVolume()), cfg::stepBreakSoundVolume, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.cycle(w, contentX, y, contentWidth, "Pitch",
                () -> String.format(Locale.US, "%.1f", cfg.getBreakSoundPitch()), cfg::stepBreakSoundPitch, cfg::save);
        y += MiningQolRows.STEP;
        w.add(SettingsButtonWidget.builder(net.minecraft.network.chat.Component.literal("Test"),
                btn -> PowderBreakSound.play(cfg)).bounds(contentX, y, contentWidth, MiningQolRows.ROW).build());
        return w;
    }
}
