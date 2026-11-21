package com.killer560.hub.gui.tab;

import com.killer560.hub.automeow.AutoMeowConfig;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
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
        AutoMeowConfig cfg = AutoMeowConfig.getInstance();
        int y = contentY;

        if (masterClosed(widgets, "Auto Meow", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 24;
        widgets.add(onOffButton("Play Cat Noises", cfg::isPlayCatNoises, cfg::setPlayCatNoises, cfg::save, null,
                contentX, y, contentWidth, 18));
        y += 22;

        int fieldW = 60;
        int sliderW = contentWidth - fieldW - 66;
        widgets.add(normSlider(contentX, y, sliderW, 20,
                () -> String.format("Volume: §b%.0f%%", cfg.getCatVolume() * 100),
                cfg.getCatVolume() / MAX_VOLUME, v -> cfg.setCatVolume((float) v * MAX_VOLUME), cfg::save));
        CommitEditBox volumeField = new CommitEditBox(Minecraft.getInstance().font, contentX + sliderW + 6, y, fieldW, 20,
                Component.literal("Volume"));
        volumeField.setMaxLength(8);
        volumeField.setValue(String.format("%.0f", cfg.getCatVolume() * 100));
        widgets.add(volumeField);
        SettingsButtonWidget volumeFieldSet = SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    Float parsed = parseFloat(volumeField.getValue());
                    if (parsed == null) {
                        ModOverlayMessage.show("§c[Killer560's Mod] Invalid volume - enter a number like 100 (%)", 3000);
                        return;
                    }
                    cfg.setCatVolume(Math.max(0f, Math.min(200f, parsed)) / 100f);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + sliderW + 6 + fieldW + 6, y, contentWidth - sliderW - fieldW - 12, 20).build();
        widgets.add(volumeFieldSet);
        volumeField.pressOnEnter(volumeFieldSet);

        return widgets;
    }

    private static Float parseFloat(String text) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
