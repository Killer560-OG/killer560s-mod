package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.quiver.QuiverDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Quiver Display settings - see {@link com.killer560.hub.quiver.QuiverDisplayFeature}'s class doc for
 *  the real Odin-ported lore-reading this is built on. The explanation lines that sat at the bottom were removed
 *  (killer560, 2026-10-11: "remove the extra text at the bottom of the quiver display"); the description lives in the
 *  tooltips. Labels double as tooltip keys, so the alert ones carry a "Quiver" / "Low Arrow" prefix to stay clear of
 *  keys other tabs own. */
public class QuiverDisplayTab extends BaseTab {

    public QuiverDisplayTab() {
        super("Quiver Display");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        QuiverDisplayConfig cfg = QuiverDisplayConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();

        widgets.add(onOffButton("Quiver Display", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Show Item Name", cfg::isShowName, cfg::setShowName, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Quiver Text Color", cfg.getTextColor()), btn ->
                McCompat.setScreen(mc, new ColorPickerScreen(McCompat.screen(mc), "Quiver Text Color",
                        cfg.getTextColor(), QuiverDisplayConfig.DEFAULT_TEXT_COLOR, argb -> {
                            cfg.setTextColor(argb);
                            cfg.save();
                        }))).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Low Arrow Alert", false));
        y += 14;
        int half = (contentWidth - 4) / 2;
        widgets.add(onOffButton("Low Arrow Alert", cfg::isAlertEnabled, cfg::setAlertEnabled, cfg::save,
                null, contentX, y, half, 18));
        widgets.add(onOffButton("Low Arrow Sound", cfg::isAlertSound, cfg::setAlertSound, cfg::save,
                null, contentX + half + 4, y, contentWidth - half - 4, 18));
        y += 22;

        int steps = (QuiverDisplayConfig.MAX_THRESHOLD - QuiverDisplayConfig.MIN_THRESHOLD) / QuiverDisplayConfig.THRESHOLD_STEP;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, thresholdLabel(cfg),
                (cfg.getAlertThreshold() - QuiverDisplayConfig.MIN_THRESHOLD)
                        / (double) (QuiverDisplayConfig.MAX_THRESHOLD - QuiverDisplayConfig.MIN_THRESHOLD)) {
            @Override
            protected void updateMessage() {
                setMessage(thresholdLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setAlertThreshold(QuiverDisplayConfig.MIN_THRESHOLD
                        + (int) Math.round(this.value * steps) * QuiverDisplayConfig.THRESHOLD_STEP);
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 22;

        widgets.add(new StringWidget(contentX, y + 2, contentWidth, 12,
                Component.literal("§7Low Arrow Text (& color codes, {count} = arrows left)"), mc.font));
        y += 14;
        EditBox text = new EditBox(mc.font, contentX, y, contentWidth, 18, Component.literal("Low Arrow Text"));
        text.setMaxLength(100);
        text.setValue(cfg.getAlertText());
        text.setResponder(value -> {
            cfg.setAlertText(value);
            cfg.save();
        });
        widgets.add(text);

        return widgets;
    }

    private static Component thresholdLabel(QuiverDisplayConfig cfg) {
        return Component.literal("Low Arrow Threshold: §b" + cfg.getAlertThreshold());
    }
}
