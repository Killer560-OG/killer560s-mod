package com.killer560.hub.gui.tab;

import com.killer560.hub.clicksound.ClickSounds;
import com.killer560.hub.clicksound.SoundEffect;
import com.killer560.hub.clicksound.SoundEffectConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ExternalOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Misc's Sound Effects (killer560, 2026-10-18; in Home until 2026-10-20, when he asked for game sounds to be out of the
 * mod settings - none of its five sounds belongs to one feature, so the tab moved whole): one row per game sound the mod can replace - an arrow hitting, every left
 * click that hits a mob or a block, a mob dying, a block breaking. Each row is a switch and the sound it plays instead
 * (Default, a preset, or an imported file, the same list as the click). Off by default. See {@link SoundEffect}.
 */
public class SoundEffectsTab extends BaseTab {

    /** The outcome of the last import, shown under the buttons until the tab is rebuilt by another action. */
    private static String status = "";

    public SoundEffectsTab() {
        super("Sound Effects");
    }

    @Override
    public String cardDescription() {
        return "Swap hit and death sounds.";
    }

    /** Each effect is its own feature: the card is ON while any row is on (see {@link BaseTab#masterToggles}). */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, java.util.Arrays.stream(SoundEffect.values()).map(e -> e.label)
                .toArray(String[]::new));
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        var font = Minecraft.getInstance().font;
        int y = contentY;

        for (SoundEffect effect : SoundEffect.values()) {
            widgets.add(SettingsButtonWidget.builder(switchText(effect), btn -> {
                        SoundEffectConfig c = SoundEffectConfig.getInstance();
                        c.setOn(effect, !c.isOn(effect));
                        c.save();
                        btn.setMessage(switchText(effect));
                    }).bounds(contentX, y, half, 20).build());
            widgets.add(SettingsButtonWidget.cycling(choiceText(effect), (btn, dir) -> {
                        SoundEffectConfig c = SoundEffectConfig.getInstance();
                        c.setChoice(effect, ClickSounds.step(c.getChoice(effect), dir));
                        c.save();
                        btn.setMessage(choiceText(effect));
                        ClickSounds.preview(c.getChoice(effect), 1.0f);
                    }).bounds(contentX + half + gap, y, rightW, 20).build());
            y += 24;
        }
        y += 4;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Import", false));
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

    /** Imports the clipboard's file into the sound folder (it is then in every row's list); the text to show. */
    static String importFromClipboard() {
        try {
            String name = ClickSounds.importClipboard(Minecraft.getInstance().keyboardHandler.getClipboard());
            return "§aImported " + name + ". Step a row's Sound to it.";
        } catch (IOException e) {
            return "§c" + e.getMessage();
        }
    }

    private static Component switchText(SoundEffect effect) {
        boolean on = SoundEffectConfig.getInstance().isOn(effect);
        return Component.literal(effect.label + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static Component choiceText(SoundEffect effect) {
        return Component.literal("Sound: §6" + ClickSounds.label(SoundEffectConfig.getInstance().getChoice(effect)));
    }
}
