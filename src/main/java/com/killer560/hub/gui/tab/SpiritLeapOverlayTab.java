package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.spiritleap.SpiritLeapOverlayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Custom Leap Menu settings - see {@link com.killer560.hub.spiritleap.SpiritLeapOverlayFeature}'s
 *  class doc for the real Odin-ported overlay this is built on. Not to be confused with the "Leap
 *  Order" tab, which is a separate reference/organizer menu, not an overlay on the real Spirit Leap
 *  GUI itself. */
public class SpiritLeapOverlayTab extends BaseTab {

    public SpiritLeapOverlayTab() {
        super("Custom Leap Menu");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SpiritLeapOverlayConfig cfg = SpiritLeapOverlayConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Leap Menu", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Use Class Colors", cfg.isUseClassColors()), btn -> {
                    cfg.setUseClassColors(!cfg.isUseClassColors());
                    cfg.save();
                    btn.setMessage(onOff("Use Class Colors", cfg.isUseClassColors()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        double scaleNormalized = (cfg.getScale() - 0.5) / 1.5;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                Component.literal(String.format(Locale.US, "Scale: %.0f%%", cfg.getScale() * 100)), scaleNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.format(Locale.US, "Scale: %.0f%%", cfg.getScale() * 100)));
            }

            @Override
            protected void applyValue() {
                cfg.setScale((float) (0.5 + this.value * 1.5));
                cfg.save();
            }
        });
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Draws 4 big clickable boxes over the real Spirit Leap GUI, one"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7per real teammate - click anywhere in a quarter of the screen"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7to leap to that quadrant's player instead of the tiny real slot."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
