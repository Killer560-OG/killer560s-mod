package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.maxor.MaxorConfig;
import com.killer560.hub.maxor.MaxorCrystalsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Maxor's Crystals settings - the F7/M7 Phase 1 crystal respawn timer, placement timer, unplaced-crystal alert,
 * active counter and the optional crystal highlight (see {@link MaxorCrystalsFeature}). Everything defaults OFF.
 */
public class MaxorTab extends BaseTab {

    /** Two-click confirm for the only destructive button on this page, same pattern as
     *  {@code PathfindingTab}'s "Sure? Reset Island" and {@code WaypointRoutesTab}'s "Sure?" - it used to
     *  wipe the personal best on a single click (2026-09-20 tooltip/configurability sweep). */
    private boolean confirmResetBest = false;

    public MaxorTab() {
        super("Maxor's Crystals");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MaxorConfig cfg = MaxorConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Crystal Timers (P1)", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Respawn Timer", cfg::getSpawnTimerRaw, cfg::setSpawnTimer));
        widgets.add(toggle(col2X, y, colW, "Place Timer", cfg::getPlaceTimerRaw, cfg::setPlaceTimer));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Unplaced Crystal Alert", cfg::getPlaceAlertRaw, cfg::setPlaceAlert));
        widgets.add(toggle(col2X, y, colW, "Active Crystal Counter", cfg::getActiveCounterRaw, cfg::setActiveCounter));
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal(bestText(cfg, confirmResetBest)), btn -> {
                    if (cfg.getBestPlaceMs() != 0L && !confirmResetBest) {
                        confirmResetBest = true;
                    } else {
                        cfg.setBestPlaceMs(0L);
                        cfg.save();
                        confirmResetBest = false;
                    }
                    btn.setMessage(Component.literal(bestText(cfg, confirmResetBest)));
                }).bounds(contentX, y, colW, 18).build());
        y += 28;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Crystal Highlight", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Highlight Crystals", cfg::getHighlightRaw, cfg::setHighlight));
        widgets.add(colorButton(col2X, y, colW, "Highlight Color", cfg.getHighlightColor(),
                MaxorConfig.DEFAULT_HIGHLIGHT_COLOR, cfg::setHighlightColor));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Filled Boxes", cfg::isHighlightFilled, cfg::setHighlightFilled));

        return widgets;
    }

    private static String bestText(MaxorConfig cfg, boolean confirming) {
        long best = cfg.getBestPlaceMs();
        if (best == 0L) {
            return "Reset Best Place Time: §7none";
        }
        // Fixed wording while confirming so the hover tooltip still resolves ("sure? reset best time").
        return confirming
                ? "§cSure? Reset Best Time"
                : String.format(Locale.US, "Reset Best Place Time: §e%.3fs", best / 1000.0);
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, name, current, defaultColor, argb -> {
                setter.accept(argb);
                MaxorConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
            setter.accept(!getter.getAsBoolean());
            MaxorConfig.getInstance().save();
            btn.setMessage(onOff(label, getter.getAsBoolean()));
        }).bounds(x, y, width, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
