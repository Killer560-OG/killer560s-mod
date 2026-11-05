package com.killer560.hub.gui.tab;

import com.killer560.hub.bazaar.BazaarOrderHelperConfig;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Bazaar Order Helper ({@link com.killer560.hub.bazaar.BazaarOrderHelper}): the switch, Highlight Outbid and Highlight
 *  Filled, and their two colors. */
public class BazaarOrderHelperTab extends BaseTab {

    public BazaarOrderHelperTab() {
        super("Bazaar Order Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        BazaarOrderHelperConfig cfg = BazaarOrderHelperConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(onOffButton("Bazaar Order Helper", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isEnabledRaw()) {
            return widgets;
        }
        widgets.add(onOffButton("Highlight Outbid", cfg::isHighlightOutbid, cfg::setHighlightOutbid, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(colorButton("Outbid Color", cfg.getOutbidColor(), BazaarOrderHelperConfig.DEFAULT_OUTBID_COLOR, argb -> {
            cfg.setOutbidColor(argb);
            cfg.save();
        }, colBX, y, colW));
        y += 20;
        widgets.add(onOffButton("Highlight Filled", cfg::isHighlightFilled, cfg::setHighlightFilled, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(colorButton("Filled Color", cfg.getFilledColor(), BazaarOrderHelperConfig.DEFAULT_FILLED_COLOR, argb -> {
            cfg.setFilledColor(argb);
            cfg.save();
        }, colBX, y, colW));
        y += 20;
        widgets.add(onOffButton("Highlight Expired", cfg::isHighlightExpired, cfg::setHighlightExpired, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(colorButton("Expired Color", cfg.getExpiredColor(), BazaarOrderHelperConfig.DEFAULT_EXPIRED_COLOR, argb -> {
            cfg.setExpiredColor(argb);
            cfg.save();
        }, colBX, y, colW));
        return widgets;
    }

    static AbstractWidget colorButton(String label, int argb, int defaultArgb, IntConsumer set, int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, argb), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, argb, defaultArgb,
                    set::accept));
        }).bounds(x, y, w, 18).build();
    }
}
