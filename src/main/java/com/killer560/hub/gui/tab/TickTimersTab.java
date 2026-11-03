package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.ticktimers.TickTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.killer560.hub.compat.McCompat;

/** Tick Timers settings - see {@link com.killer560.hub.ticktimers.TickTimersFeature}'s class doc for
 *  the real Odin-ported chat triggers/tick counts this is built on.
 *  <p>
 *  <b>2026-09-21:</b> Storm's pad highlight moved here from the F7 Spots tab - killer560: "Move them to Tick Timers. One
 *  home per timer." <b>2026-11-02:</b> Storm has one line ("Storm Line", {@code ticktimers.StormLine}); the
 *  old "Storm", "Pad Cycle Timer", crush HUD, crush title, interval, warning and trigger rows are gone (killer560: "for
 *  storm there should be no option for crush or storm there, remove those as settings"). */
public class TickTimersTab extends BaseTab {

    public TickTimersTab() {
        super("Tick Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(onOffButton("Tick Timers", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Necron Start", cfg::isNecronTimer, cfg::setNecronTimer, cfg::save,
                null, col1, y, 100, 18));

        widgets.add(onOffButton("Goldor", cfg::isGoldorTimer, cfg::setGoldorTimer, cfg::save, null, col2, y, 100, 18));

        widgets.add(onOffButton("Storm Line", cfg::isStormLine, cfg::setStormLine, cfg::save, null, col3, y, 108, 18));
        y += 22;

        widgets.add(onOffButton("Ticks (vs Seconds)", cfg::isDisplayInTicks, cfg::setDisplayInTicks, cfg::save,
                null, col1, y, 160, 18));

        widgets.add(onOffButton("Symbol", cfg::isShowSymbol, cfg::setShowSymbol, cfg::save, null, col3, y, 108, 18));
        y += 20;

        widgets.add(onOffButton("Show Prefix", cfg::isShowPrefix, cfg::setShowPrefix, cfg::save,
                null, col1, y, 160, 18));

        widgets.add(onOffButton("Goldor Start", cfg::isGoldorStartTimer, cfg::setGoldorStartTimer, cfg::save,
                null, col3, y, 108, 18));
        y += 22;

        // Folded in from the old Goldor Frenzy Timer tab (2026-09-20 merge) - see TickTimersFeature's class doc.
        widgets.add(onOffButton("Show Total", cfg::isGoldorShowTotal, cfg::setGoldorShowTotal, cfg::save,
                null, col1, y, 108, 18));

        // NoammAddons 1.2.9 floor7/TickTimers.kt "Maxor Start" - in the slot the removed clear Death Tick had.
        widgets.add(onOffButton("Maxor Start", cfg::isMaxorStartTimer, cfg::setMaxorStartTimer, cfg::save,
                null, col3, y, 108, 18));
        y += 26;

        // ---- Storm's pads (P2): the outlines only ----
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Storm Pads (P2)", false));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Purple Pad Highlight", cfg::isCrushPadHighlightEnabled, cfg::setCrushPadHighlight));
        widgets.add(toggle(col2X, y, colW, "Show All Pads", cfg::isCrushAllPads, cfg::setCrushAllPads));
        y += 22;
        widgets.add(colorButton(contentX, y, colW, "Pad Color", cfg.getCrushPadColor(), TickTimersConfig.DEFAULT_PAD_COLOR,
                argb -> cfg.setCrushPadColor(argb)));

        return widgets;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                                java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), name, current, defaultColor, argb -> {
                setter.accept(argb);
                TickTimersConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return onOffButton(label, getter, setter, () -> TickTimersConfig.getInstance().save(), null, x, y, width, 18);
    }
}
