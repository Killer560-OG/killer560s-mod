package com.killer560.hub.gui.tab;

import com.killer560.hub.automeow.AutoMeowConfig;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Meow settings - see {@link com.killer560.hub.automeow.AutoMeowFeature}. */
public class AutoMeowTab extends BaseTab {

    private static final float MAX_VOLUME = 2.0f;

    public AutoMeowTab() {
        super("Auto Meow");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    AutoMeowConfig cfg = AutoMeowConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(catNoisesText(), btn -> {
                    AutoMeowConfig cfg = AutoMeowConfig.getInstance();
                    cfg.setPlayCatNoises(!cfg.isPlayCatNoises());
                    cfg.save();
                    btn.setMessage(catNoisesText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(new ThemedSliderButton(contentX, y, 160, 20, volumeText(),
                AutoMeowConfig.getInstance().getCatVolume() / MAX_VOLUME) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                AutoMeowConfig cfg = AutoMeowConfig.getInstance();
                cfg.setCatVolume((float) this.value * MAX_VOLUME);
                cfg.save();
            }
        });
        EditBox volumeField = new EditBox(Minecraft.getInstance().font, contentX + 166, y, 60, 20,
                Component.literal("Volume"));
        volumeField.setMaxLength(8);
        volumeField.setValue(String.format("%.0f", AutoMeowConfig.getInstance().getCatVolume() * 100));
        widgets.add(volumeField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    Float parsed = parseFloat(volumeField.getValue());
                    if (parsed == null) {
                        ModOverlayMessage.show("§c[Killer560's Mod] Invalid volume - enter a number like 100 (%)", 3000);
                        return;
                    }
                    AutoMeowConfig cfg = AutoMeowConfig.getInstance();
                    cfg.setCatVolume(Math.max(0f, Math.min(200f, parsed)) / 100f);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + 230, y, contentWidth - 230, 20).build());

        return widgets;
    }

    private static Float parseFloat(String text) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Component enabledText() {
        return Component.literal("Auto Meow Enabled: "
                + (AutoMeowConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component catNoisesText() {
        return Component.literal("Play Cat Noises: "
                + (AutoMeowConfig.getInstance().isPlayCatNoises() ? "§aON" : "§cOFF"));
    }

    private static Component volumeText() {
        return Component.literal(String.format("Volume: §b%.0f%%", AutoMeowConfig.getInstance().getCatVolume() * 100));
    }
}
