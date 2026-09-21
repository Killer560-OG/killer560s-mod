package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.lagdisplay.LagDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lag Display settings - "last server tick was N ms ago" plus ping, FPS and CPS in one movable HUD
 *  element. See {@link com.killer560.hub.lagdisplay.LagDisplayFeature} for the Devonian / NoammAddons
 *  sources and the server-tick caveats. Master ships OFF; every change saves immediately. */
public class LagDisplayTab extends BaseTab {

    public LagDisplayTab() {
        super("Lag Display");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        LagDisplayConfig cfg = LagDisplayConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Lag Display");
        w.add(SettingsButtonWidget.builder(onOff("Lag Display", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.isEnabled()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Lines");
        toggle(w, contentX, y[0], half, "Server Lag", cfg::isShowLag, cfg::setShowLag, cfg);
        toggle(w, colB, y[0], half, "Ping", cfg::isShowPing, cfg::setShowPing, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "FPS", cfg::isShowFps, cfg::setShowFps, cfg);
        // "CPS Counter", not "CPS": SettingTooltipsData already has a "cps" key (Auto i4's click-rate
        // slider), and tooltips are looked up by lower-cased label, so a bare "CPS" would show that text.
        toggle(w, colB, y[0], half, "CPS Counter", cfg::isShowCps, cfg::setShowCps, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], contentWidth, "Colour By Value", cfg::isColorByValue, cfg::setColorByValue, cfg);
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Lag Threshold");
        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, thresholdLabel(cfg),
                (cfg.getLagThresholdMs() - LagDisplayConfig.MIN_LAG_THRESHOLD_MS)
                        / (double) (LagDisplayConfig.MAX_LAG_THRESHOLD_MS
                        - LagDisplayConfig.MIN_LAG_THRESHOLD_MS)) {
            @Override
            protected void updateMessage() {
                setMessage(thresholdLabel(cfg));
            }

            @Override
            protected void applyValue() {
                int range = LagDisplayConfig.MAX_LAG_THRESHOLD_MS - LagDisplayConfig.MIN_LAG_THRESHOLD_MS;
                cfg.setLagThresholdMs((int) (Math.round(
                        (LagDisplayConfig.MIN_LAG_THRESHOLD_MS + this.value * range) / 10.0) * 10));
                cfg.save();
            }
        });
        y[0] += 24;
        return w;
    }

    private static Component thresholdLabel(LagDisplayConfig cfg) {
        return Component.literal("Lag Threshold: " + cfg.getLagThresholdMs() + "ms");
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

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
