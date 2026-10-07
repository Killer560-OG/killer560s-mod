package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.terminalaura.TerminalAuraConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminal Aura settings - cheat build only, so the whole tab only exists on the cheat jar and its
 *  headers are drawn red. See {@code TerminalAuraFeature} for what it actually does. */
public class TerminalAuraTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;

    public TerminalAuraTab() {
        super("Terminal Aura");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        TerminalAuraConfig cfg = TerminalAuraConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int halfW = (contentWidth - GAP) / 2;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Terminal Aura", true), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Aura", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        // Range is a SLIDER now, 0.5 to 4.5 - killer560 (2026-10-01). It was a click-to-step button that wrapped
        // back to 1.0 at the top, so reaching a particular value meant clicking round the whole range.
        //
        // The label turns red past TerminalAuraConfig.SAFE_RANGE, and that is not decoration: 3.0 is the largest
        // range his own GrimAC harness measured without drawing a Reach violation, because a terminal is an
        // ARMOUR STAND and an entity gets a tighter limit than a block's 4.5. The warning lives on the control
        // so it is in front of him while he sets it.
        widgets.add(rangeSlider(contentX, y, halfW, cfg));
        widgets.add(SettingsButtonWidget.builder(delayText(cfg), btn -> {
                    int next = cfg.getDelayMs() + 50;
                    cfg.setDelayMs(next > TerminalAuraConfig.MAX_DELAY_MS ? 0 : next);
                    cfg.save();
                    btn.setMessage(delayText(cfg));
                }).bounds(contentX + halfW + GAP, y, Math.max(1, contentWidth - halfW - GAP), ROW).build());
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.builder(onOff("Ground Only", cfg.isGroundOnly()), btn -> {
                    cfg.setGroundOnly(!cfg.isGroundOnly());
                    cfg.save();
                    btn.setMessage(onOff("Ground Only", cfg.isGroundOnly()));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Leap Delay", cfg.isLeapDelayEnabled()), btn -> {
                    cfg.setLeapDelayEnabled(!cfg.isLeapDelayEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + halfW + GAP, y, Math.max(1, contentWidth - halfW - GAP), ROW).build());
        y += ROW + GAP;

        // "Pause On Movement Keys", and the label says KEYS on purpose: it reads the keys, not the velocity.
        widgets.add(SettingsButtonWidget.builder(
                onOff("Pause On Movement Keys", cfg.isPauseOnMovementKeys()), btn -> {
                    cfg.setPauseOnMovementKeys(!cfg.isPauseOnMovementKeys());
                    cfg.save();
                    btn.setMessage(onOff("Pause On Movement Keys", cfg.isPauseOnMovementKeys()));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(fovSlider(contentX + halfW + GAP, y, Math.max(1, contentWidth - halfW - GAP), cfg));
        y += ROW + GAP;

        if (cfg.isLeapDelayEnabled()) {
            widgets.add(SettingsButtonWidget.builder(leapDelayText(cfg), btn -> {
                        double next = cfg.getLeapDelaySeconds() + 0.1;
                        cfg.setLeapDelaySeconds(next > 5.0 ? 0.1 : next);
                        cfg.save();
                        btn.setMessage(leapDelayText(cfg));
                    }).bounds(contentX, y, halfW, ROW).build());
            y += ROW + GAP;
        }

        return widgets;
    }

    /**
     * The range slider, {@value TerminalAuraConfig#MIN_RANGE} to {@value TerminalAuraConfig#MAX_RANGE} blocks.
     *
     * <p>Stored in quarter-block steps so a handle position maps to a value he can actually hit again, and
     * rounded the same way {@code CosmeticsTab}'s scale slider rounds: through the arithmetic once, not twice.
     * The normalised position is computed from the CLAMPED range, so the handle can never start off the track -
     * which is the failure CLAUDE.md records for the Breaker Aura cooldown slider, where a start position
     * computed negative was the tell that the widget and the setter disagreed.
     */
    private static com.killer560.hub.gui.ThemedSliderButton rangeSlider(int x, int y, int width,
                                                                        TerminalAuraConfig cfg) {
        double span = TerminalAuraConfig.MAX_RANGE - TerminalAuraConfig.MIN_RANGE;
        double normalized = Math.max(0.0, Math.min(1.0,
                (cfg.getRange() - TerminalAuraConfig.MIN_RANGE) / span));
        return new com.killer560.hub.gui.ThemedSliderButton(x, y, width, ROW, rangeText(cfg), normalized) {
            // Reads the span off the constants rather than closing over the local above, because
            // AbstractSliderButton's constructor calls updateMessage() - so this runs once before the
            // anonymous class is finished being built, and a captured local read there is not something to
            // rely on.
            private double valueAt() {
                double full = TerminalAuraConfig.MAX_RANGE - TerminalAuraConfig.MIN_RANGE;
                double raw = TerminalAuraConfig.MIN_RANGE + this.value * full;
                return Math.round(raw * 4.0) / 4.0;   // quarter-block steps
            }

            @Override
            protected void updateMessage() {
                setMessage(rangeLabel(valueAt()));
            }

            @Override
            protected void applyValue() {
                cfg.setRange(valueAt());
                cfg.save();
            }
        };
    }

    /** Aura FOV, {@value TerminalAuraConfig#MIN_FOV} to {@value TerminalAuraConfig#MAX_FOV} degrees in whole steps. */
    private static com.killer560.hub.gui.ThemedSliderButton fovSlider(int x, int y, int width, TerminalAuraConfig cfg) {
        double span = TerminalAuraConfig.MAX_FOV - TerminalAuraConfig.MIN_FOV;
        double normalized = Math.max(0.0, Math.min(1.0, (cfg.getFovDegrees() - TerminalAuraConfig.MIN_FOV) / span));
        return new com.killer560.hub.gui.ThemedSliderButton(x, y, width, ROW, fovLabel(cfg.getFovDegrees()), normalized) {
            private int valueAt() {
                return (int) Math.round(TerminalAuraConfig.MIN_FOV
                        + this.value * (TerminalAuraConfig.MAX_FOV - TerminalAuraConfig.MIN_FOV));
            }

            @Override
            protected void updateMessage() {
                setMessage(fovLabel(valueAt()));
            }

            @Override
            protected void applyValue() {
                cfg.setFovDegrees(valueAt());
                cfg.save();
            }
        };
    }

    private static Component fovLabel(int degrees) {
        return Component.literal("Aura FOV: " + (degrees >= TerminalAuraConfig.MAX_FOV ? "Any" : degrees + "°"));
    }

    private static Component rangeText(TerminalAuraConfig cfg) {
        return rangeLabel(cfg.getRange());
    }

    /** Red past the measured-safe range - see the comment at the slider and {@code TerminalAuraConfig}. */
    private static Component rangeLabel(double range) {
        String colour = range > TerminalAuraConfig.SAFE_RANGE ? "§c" : "";
        return Component.literal(String.format(Locale.US, "Range: %s%.2f", colour, range));
    }

    private static Component delayText(TerminalAuraConfig cfg) {
        return Component.literal("Delay: " + cfg.getDelayMs() + "ms");
    }

    private static Component leapDelayText(TerminalAuraConfig cfg) {
        return Component.literal(String.format(Locale.US, "Leap Delay Time: %.1fs", cfg.getLeapDelaySeconds()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
