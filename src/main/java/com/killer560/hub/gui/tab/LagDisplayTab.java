package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.lagdisplay.LagDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Performance HUD settings (renamed from "Lag Display", killer560 2026-09-27) - TPS, ping, FPS and CPS in
 *  one movable HUD element. See {@link com.killer560.hub.lagdisplay.LagDisplayFeature} for the Devonian /
 *  NoammAddons sources and the TPS/ping caveats. Master ships OFF; every change saves immediately. */
public class LagDisplayTab extends BaseTab {

    public LagDisplayTab() {
        super("Performance HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        LagDisplayConfig cfg = LagDisplayConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Performance HUD");
        w.add(SettingsButtonWidget.builder(onOff("Performance HUD", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.isEnabled()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Lines");
        toggle(w, contentX, y[0], half, "TPS", cfg::isShowTps, cfg::setShowTps, cfg);
        toggle(w, colB, y[0], half, "Ping", cfg::isShowPing, cfg::setShowPing, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "FPS", cfg::isShowFps, cfg::setShowFps, cfg);
        // "CPS Counter", not "CPS": SettingTooltipsData already has a "cps" key (Auto i4's click-rate
        // slider), and tooltips are looked up by lower-cased label, so a bare "CPS" would show that text.
        toggle(w, colB, y[0], half, "CPS Counter", cfg::isShowCps, cfg::setShowCps, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], contentWidth, "Color By Value", cfg::isColorByValue, cfg::setColorByValue, cfg);
        y[0] += 24;

        // Custom colors (killer560, 2026-10-18). The four value colors only matter with Color By Value on.
        header(w, contentX, y, contentWidth, "Colors");
        color(w, contentX, y[0], half, "Good Color", cfg::getColorGood, cfg::setColorGood,
                LagDisplayConfig.DEFAULT_GOOD, cfg);
        color(w, colB, y[0], half, "Okay Color", cfg::getColorOk, cfg::setColorOk,
                LagDisplayConfig.DEFAULT_OK, cfg);
        y[0] += 22;
        color(w, contentX, y[0], half, "Bad Color", cfg::getColorBad, cfg::setColorBad,
                LagDisplayConfig.DEFAULT_BAD, cfg);
        color(w, colB, y[0], half, "Awful Color", cfg::getColorAwful, cfg::setColorAwful,
                LagDisplayConfig.DEFAULT_AWFUL, cfg);
        y[0] += 22;
        color(w, contentX, y[0], contentWidth, "Text Color", cfg::getColorText, cfg::setColorText,
                LagDisplayConfig.DEFAULT_TEXT, cfg);
        y[0] += 24;
        return w;
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                               Supplier<Boolean> get, Consumer<Boolean> set, LagDisplayConfig cfg) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    cfg.save();
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static void color(List<AbstractWidget> w, int x, int y, int width, String label,
                              java.util.function.IntSupplier get, java.util.function.IntConsumer set, int def,
                              LagDisplayConfig cfg) {
        w.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label(label, get.getAsInt()), btn -> {
            Minecraft client = Minecraft.getInstance();
            com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                    com.killer560.hub.compat.McCompat.screen(client), "Performance HUD " + label, get.getAsInt(), def,
                    argb -> {
                        set.accept(argb);
                        cfg.save();
                    }));
        }).bounds(x, y, width, 18).build());
    }
}
