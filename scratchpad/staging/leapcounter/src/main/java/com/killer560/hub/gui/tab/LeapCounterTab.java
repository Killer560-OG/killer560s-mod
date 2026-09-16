package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.leapcounter.LeapCounterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Leap Counter settings - how many teammates must leap to you per F7/M7 section before the alert fires (see
 * {@link com.killer560.hub.leapcounter.LeapTracker}). Informational, so not cheat-only: orange headers, ships on both
 * jars. Master ships OFF; every change saves immediately. Lives in the New tab until killer560 confirms it in a run.
 */
public class LeapCounterTab extends BaseTab {

    public LeapCounterTab() {
        super("Leap Counter");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        LeapCounterConfig cfg = LeapCounterConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Leap Counter");
        w.add(SettingsButtonWidget.builder(onOff("Leap Counter", cfg.getEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.getEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.getEnabledRaw()) {
            note(w, contentX, y, contentWidth,
                    "Counts how many teammates have leaped TO you in F7/M7 P3 and alerts when the expected number arrived.");
            note(w, contentX, y, contentWidth,
                    "Someone who walks over is not counted - only arrivals by leap.");
            return w;
        }

        // Per-section targets - killer560's numbers for S2/S3/S4, S1 and Core/Relic from NoammAddons (0 = off).
        header(w, contentX, y, contentWidth, "Expected Leaps");
        countSlider(w, contentX, y[0], half, "S1 Leaps", cfg::getCountS1, cfg::setCountS1, cfg);
        countSlider(w, colB, y[0], half, "S2 Leaps", cfg::getCountS2, cfg::setCountS2, cfg);
        y[0] += 22;
        countSlider(w, contentX, y[0], half, "S3 Leaps", cfg::getCountS3, cfg::setCountS3, cfg);
        countSlider(w, colB, y[0], half, "S4 Leaps", cfg::getCountS4, cfg::setCountS4, cfg);
        y[0] += 22;
        countSlider(w, contentX, y[0], half, "Core Leaps", cfg::getCountCore, cfg::setCountCore, cfg);
        countSlider(w, colB, y[0], half, "Relic Leaps", cfg::getCountRelic, cfg::setCountRelic, cfg);
        y[0] += 24;
        note(w, contentX, y, contentWidth, "Capped to your party size - a 4-man never waits for a 5th leap. 0 turns a section off.");

        header(w, contentX, y, contentWidth, "Detection");
        w.add(new ThemedSliderButton(contentX, y[0], half, 18, radiusText(cfg),
                (cfg.getRadius() - LeapCounterConfig.MIN_RADIUS)
                        / (LeapCounterConfig.MAX_RADIUS - LeapCounterConfig.MIN_RADIUS)) {
            @Override
            protected void updateMessage() {
                setMessage(radiusText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setRadius((float) (LeapCounterConfig.MIN_RADIUS
                        + this.value * (LeapCounterConfig.MAX_RADIUS - LeapCounterConfig.MIN_RADIUS)));
                cfg.save();
            }
        });
        w.add(new ThemedSliderButton(colB, y[0], half, 18, jumpText(cfg),
                (cfg.getJumpDistance() - LeapCounterConfig.MIN_JUMP_DISTANCE)
                        / (LeapCounterConfig.MAX_JUMP_DISTANCE - LeapCounterConfig.MIN_JUMP_DISTANCE)) {
            @Override
            protected void updateMessage() {
                setMessage(jumpText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setJumpDistance((float) (LeapCounterConfig.MIN_JUMP_DISTANCE
                        + this.value * (LeapCounterConfig.MAX_JUMP_DISTANCE - LeapCounterConfig.MIN_JUMP_DISTANCE)));
                cfg.save();
            }
        });
        y[0] += 24;
        note(w, contentX, y, contentWidth, "Nothing counts for 3s after your own leap, or while you are walking.");

        header(w, contentX, y, contentWidth, "Alert");
        toggle(w, contentX, y[0], half, "Alert Title", cfg::isAlert, cfg::setAlert, cfg);
        toggle(w, colB, y[0], half, "Alert Sound", cfg::isSound, cfg::setSound, cfg);
        y[0] += 22;
        w.add(new StringWidget(contentX, y[0] + 4, contentWidth, 12, Component.literal("§7Alert Text (& colour codes)"), mc.font));
        y[0] += 16;
        EditBox text = new EditBox(mc.font, contentX, y[0], contentWidth, 18, Component.literal("Alert Text"));
        text.setMaxLength(100);
        text.setValue(cfg.getAlertText());
        text.setResponder(value -> {
            cfg.setAlertText(value);
            cfg.save();
        });
        w.add(text);
        y[0] += 24;

        header(w, contentX, y, contentWidth, "HUD");
        toggle(w, contentX, y[0], half, "Leap HUD", cfg::isHud, cfg::setHud, cfg);
        toggle(w, colB, y[0], half, "Show At Zero", cfg::isHudShowAtZero, cfg::setHudShowAtZero, cfg);
        y[0] += 24;
        note(w, contentX, y, contentWidth, "Move it in the HUD editor (element: Leap Counter).");
        return w;
    }

    private static Component radiusText(LeapCounterConfig cfg) {
        return Component.literal(String.format(Locale.US, "Leap Radius: %.1f", cfg.getRadius()));
    }

    private static Component jumpText(LeapCounterConfig cfg) {
        return Component.literal(String.format(Locale.US, "Jump Distance: %.0f", cfg.getJumpDistance()));
    }

    private static Component countText(String label, int value) {
        return Component.literal(label + ": " + (value <= 0 ? "§cOFF" : "§b" + value));
    }

    private static void countSlider(List<AbstractWidget> w, int x, int y, int width, String label,
                                    IntSupplier get, IntConsumer set, LeapCounterConfig cfg) {
        w.add(new ThemedSliderButton(x, y, width, 18, countText(label, get.getAsInt()),
                get.getAsInt() / (double) LeapCounterConfig.MAX_COUNT) {
            @Override
            protected void updateMessage() {
                setMessage(countText(label, get.getAsInt()));
            }

            @Override
            protected void applyValue() {
                set.accept((int) Math.round(this.value * LeapCounterConfig.MAX_COUNT));
                cfg.save();
            }
        });
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void note(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        w.add(new StringWidget(x, y[0], width, 12, Component.literal("§7" + text), Minecraft.getInstance().font));
        y[0] += 12;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                               Supplier<Boolean> get, Consumer<Boolean> set, LeapCounterConfig cfg) {
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
