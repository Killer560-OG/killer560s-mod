package com.killer560.hub.gui.tab;

import com.killer560.hub.clicksound.ClickSoundConfig;
import com.killer560.hub.clicksound.ClickSounds;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.ExternalOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Click Sounds (killer560, 2026-10-15): the sound every button click makes - Default (Minecraft's), one of the
 * bundled presets, or a sound of his own from the import folder - and how loud, from 100% to 500% (2026-10-18). It plays on
 * every screen. Import: drop a file into the folder (Open Sound Folder), or copy a file's path and press Import From
 * Clipboard (video included: only its audio is kept). See {@link ClickSounds}.
 */
public class ClickSoundsTab extends BaseTab {

    /** The outcome of the last import, shown under the buttons until the tab is rebuilt by another action. */
    private static String status = "";

    public ClickSoundsTab() {
        super("Click Sounds");
    }

    @Override
    public String cardDescription() {
        return "Pick the click sound or add yours.";
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        int y = contentY;

        widgets.add(SettingsButtonWidget.cycling(soundText(), (btn, dir) -> {
                    ClickSoundConfig cfg = ClickSoundConfig.getInstance();
                    cfg.setChoice(ClickSounds.step(cfg.getChoice(), dir));
                    cfg.save();
                    btn.setMessage(soundText());
                    ClickSounds.preview(cfg.getChoice(), cfg.getVolume());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, volumeText(),
                (ClickSoundConfig.getInstance().getVolume() - ClickSoundConfig.MIN_VOLUME)
                        / (ClickSoundConfig.MAX_VOLUME - ClickSoundConfig.MIN_VOLUME)) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                ClickSoundConfig cfg = ClickSoundConfig.getInstance();
                // 100% to 500% in steps of 5 (killer560, 2026-10-18: the slider starts at 100%).
                cfg.setVolume(ClickSoundConfig.MIN_VOLUME
                        + Math.round(this.value * (ClickSoundConfig.MAX_VOLUME - ClickSoundConfig.MIN_VOLUME) * 20) / 20f);
                cfg.save();
            }
        });
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Preview"), btn -> {
                    ClickSoundConfig cfg = ClickSoundConfig.getInstance();
                    ClickSounds.preview(cfg.getChoice(), cfg.getVolume());
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Import", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Sound Folder"), btn ->
                ExternalOpen.path(ClickSounds.importDir())).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Import From Clipboard"), btn -> {
                    status = importFromClipboard();
                    requestRebuild.run();
                }).bounds(contentX + half + gap, y, rightW, 18).build());
        y += 22;
        if (!status.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(status), font));
        }
        return widgets;
    }

    /** Copies the file whose path is on the clipboard into the import folder and selects it; the text to show. */
    static String importFromClipboard() {
        try {
            String name = ClickSounds.importClipboard(Minecraft.getInstance().keyboardHandler.getClipboard());
            ClickSoundConfig cfg = ClickSoundConfig.getInstance();
            cfg.setChoice(ClickSounds.IMPORT_PREFIX + name);
            cfg.save();
            return "§aImported " + name + " and selected it.";
        } catch (java.io.IOException e) {
            return "§c" + e.getMessage();
        }
    }

    private static Component soundText() {
        return Component.literal("Click Sound: §6" + ClickSounds.label(ClickSoundConfig.getInstance().getChoice()));
    }

    private static Component volumeText() {
        return Component.literal("Click Volume: §b" + Math.round(ClickSoundConfig.getInstance().getVolume() * 100) + "%");
    }
}
