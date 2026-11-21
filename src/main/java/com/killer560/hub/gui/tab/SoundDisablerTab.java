package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.sounddisabler.CustomSoundsScreen;
import com.killer560.hub.sounddisabler.MutedSound;
import com.killer560.hub.sounddisabler.SoundDisablerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Misc's Sound Disabler (killer560, 2026-11-02: "add a sound disabler to hide stuff like bonzo staff sound effect"): the
 * master switch first, then the Volume Sliders option (a 0..200% slider under each preset row), one switch per
 * {@link MutedSound} (all off by default), the Custom Sounds button (a screen to mute or set the volume of any sound) and
 * Show Sound IDs, which names every sound in chat so an id can be found. Off by default. See
 * {@link com.killer560.hub.sounddisabler.SoundDisabler}.
 */
public class SoundDisablerTab extends BaseTab {

    public SoundDisablerTab() {
        super("Sound Disabler");
    }

    @Override
    public String cardDescription() {
        return "Mutes or turns down sounds.";
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(onOffButton("Sound Disabler", () -> cfg().isEnabled(), v -> cfg().setEnabled(v), () -> cfg().save(), null,
                contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(onOffButton("Volume Sliders", () -> cfg().isVolumeSliders(), v -> cfg().setVolumeSliders(v),
                () -> cfg().save(), requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Sounds", false));
        y += 16;
        boolean sliders = cfg().isVolumeSliders() || isSearchScan();
        for (MutedSound s : MutedSound.values()) {
            widgets.add(onOffButton(s.label, () -> cfg().isOn(s), v -> cfg().setOn(s, v), () -> cfg().save(), null,
                    contentX, y, contentWidth, 20));
            y += 24;
            if (sliders) {
                widgets.add(valueSlider(contentX, y, contentWidth, s.label + " Volume", "%", 0,
                        SoundDisablerConfig.MAX_PERCENT, SoundDisablerConfig.STEP_PERCENT, 0,
                        () -> cfg().getPresetPercent(s), v -> cfg().setPresetPercent(s, (int) Math.round(v)),
                        () -> cfg().save()));
                y += 22;
            }
        }
        y += 4;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Custom", false));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Custom Sounds"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            McCompat.setScreen(mc, new CustomSoundsScreen(McCompat.screen(mc)));
        }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(onOffButton("Show Sound IDs", () -> cfg().isShowIds(), v -> cfg().setShowIds(v), () -> cfg().save(), null,
                contentX, y, contentWidth, 20));
        return widgets;
    }

    /** Read on every click: a profile load replaces the instance. */
    private static SoundDisablerConfig cfg() {
        return SoundDisablerConfig.getInstance();
    }
}
