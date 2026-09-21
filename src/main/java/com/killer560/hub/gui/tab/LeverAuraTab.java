package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.leveraura.LeverAuraConfig;
import com.killer560.hub.leveraura.LeverAuraFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/** Lever Aura settings - see {@link LeverAuraFeature} (F7/M7 P3 Section 2 Lights device + section levers).
 *  Cheat build only; collapses to its master toggle while OFF (CheatUtilsTab pattern). Moved from a
 *  top-level {@code NewTab} entry into the "Secrets" folder 2026-09-21 (see {@link SecretsTab}) per
 *  killer560's menu-structure request - no behaviour change, just a different accordion home. The
 *  top-of-tab explanatory paragraph and the two per-toggle explanatory lines were dropped the same day
 *  (mod-wide in-panel-paragraph cleanup): the master toggle's own tooltip already covers the paragraph,
 *  and "pre-flick lights before s2"/"finish lights when s2 opens" already had matching tooltip entries. */
public class LeverAuraTab extends BaseTab {

    private static final int BTN_W = 220;

    public LeverAuraTab() {
        super("Lever Aura");
    }

    /** Only added to {@link SecretsTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        LeverAuraConfig cfg = LeverAuraConfig.getInstance();
        int half = (contentWidth - 8) / 2;
        int[] y = {contentY};

        header(w, contentX, y, contentWidth, "Lever Aura");
        toggle(w, contentX, y, "Lever Aura", cfg::isEnabledRaw, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabledRaw()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Lights Device");
        toggle(w, contentX, y, "Pre-Flick Lights Before S2", cfg::isLightsPreFlickRaw, cfg::setLightsPreFlick, null);
        toggle(w, contentX, y, "Finish Lights When S2 Opens", cfg::isLightsFinishRaw, cfg::setLightsFinish, null);

        header(w, contentX, y, contentWidth, "Section Levers");
        toggle(w, contentX, y, "S2 Section Levers", cfg::isSectionLeversRaw, cfg::setSectionLevers, requestRebuild);
        if (cfg.isSectionLeversRaw()) {
            toggle(w, contentX, y, "S2 Section Levers Early", cfg::isSectionLeversEarlyRaw, cfg::setSectionLeversEarly, null);
        }

        header(w, contentX, y, contentWidth, "Timing & Range");
        slider(w, contentX, y[0], BTN_W, () -> String.format(Locale.US, "Range: %.1f", cfg.getRange()),
                (cfg.getRange() - LeverAuraConfig.MIN_RANGE) / (LeverAuraConfig.MAX_RANGE - LeverAuraConfig.MIN_RANGE),
                v -> cfg.setRange(LeverAuraConfig.MIN_RANGE + v * (LeverAuraConfig.MAX_RANGE - LeverAuraConfig.MIN_RANGE)));
        y[0] += 24;
        slider(w, contentX, y[0], half, () -> "Min Delay: " + cfg.getMinDelayMs() + "ms",
                norm(cfg.getMinDelayMs(), LeverAuraConfig.MIN_DELAY_MS, LeverAuraConfig.MAX_DELAY_MS),
                v -> cfg.setMinDelayMs(denorm(v, LeverAuraConfig.MIN_DELAY_MS, LeverAuraConfig.MAX_DELAY_MS)));
        slider(w, contentX + half + 8, y[0], half, () -> "Max Delay: " + cfg.getMaxDelayMs() + "ms",
                norm(cfg.getMaxDelayMs(), LeverAuraConfig.MIN_DELAY_MS, LeverAuraConfig.MAX_DELAY_MS),
                v -> cfg.setMaxDelayMs(denorm(v, LeverAuraConfig.MIN_DELAY_MS, LeverAuraConfig.MAX_DELAY_MS)));
        y[0] += 24;
        toggle(w, contentX, y, "Swing Hand", cfg::isSwingHand, cfg::setSwingHand, null);
        toggle(w, contentX, y, "Chat Feedback", cfg::isChatFeedback, cfg::setChatFeedback, null);

        y[0] += 4;
        w.add(new StringWidget(contentX, y[0], contentWidth, 12, LeverAuraFeature.statusText(), Minecraft.getInstance().font));
        y[0] += 16;
        return w;
    }

    private static double norm(int value, int min, int max) {
        return (value - min) / (double) (max - min);
    }

    private static int denorm(double v, int min, int max) {
        return (int) Math.round(min + v * (max - min));
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, true), Minecraft.getInstance().font));
        y[0] += 16;
    }

    /** ON/OFF toggle button; a non-null {@code rebuild} rebuilds the tab (used by master toggles). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            LeverAuraConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, width, 20, Component.literal(text.get()), Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
                LeverAuraConfig.getInstance().save();
            }
        });
    }
}
