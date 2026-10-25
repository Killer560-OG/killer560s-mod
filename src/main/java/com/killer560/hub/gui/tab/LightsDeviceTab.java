package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.lightsdevice.LightsDeviceConfig;
import com.killer560.hub.lightsdevice.LightsDeviceFeature;
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

/** Lights Device settings (F7/M7 P3 S2's Lights levers) - cheat build only, in the Dungeons category's cheat block. Its
 *  own feature since 2026-10-15 (killer560: "Make lights have its own aura trigger and own triggerbot stuff it should not
 *  be impacted by other auras. It should be its own cheat."), out of Secret Aura's In Boss. Two halves, each with its own
 *  switch and settings - the Aura and the Triggerbot - and no switch over the whole: the card is ON while either is.
 *  See {@link LightsDeviceFeature}. */
public class LightsDeviceTab extends BaseTab {

    private static final int BTN_W = 220;

    public LightsDeviceTab() {
        super("Lights Device");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    /** The card stands for both halves: ON while the Aura or the Triggerbot is, OFF only when both are. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Lights Aura", "Lights Triggerbot");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        LightsDeviceConfig cfg = LightsDeviceConfig.getInstance();
        int[] y = {contentY};

        header(w, contentX, y, contentWidth, "Aura");
        toggle(w, contentX, y, "Lights Aura", cfg::isAuraEnabledRaw, cfg::setAuraEnabled, requestRebuild);
        if (cfg.isAuraEnabledRaw()) {
            toggle(w, contentX, y, "Pre-Flick Before S2", cfg::isPreFlick, cfg::setPreFlick, null);
            toggle(w, contentX, y, "Finish When S2 Opens", cfg::isFinish, cfg::setFinish, null);
            slider(w, contentX, y[0], BTN_W, () -> String.format(Locale.US, "Aura Range: %.1f", cfg.getRange()),
                    (cfg.getRange() - LightsDeviceConfig.MIN_RANGE) / (LightsDeviceConfig.MAX_RANGE - LightsDeviceConfig.MIN_RANGE),
                    v -> cfg.setRange(LightsDeviceConfig.MIN_RANGE + v * (LightsDeviceConfig.MAX_RANGE - LightsDeviceConfig.MIN_RANGE)));
            y[0] += 24;
            slider(w, contentX, y[0], BTN_W, () -> "Aura CPS: " + cfg.getCps(),
                    (cfg.getCps() - LightsDeviceConfig.MIN_CPS) / (double) (LightsDeviceConfig.MAX_CPS - LightsDeviceConfig.MIN_CPS),
                    v -> cfg.setCps((int) Math.round(LightsDeviceConfig.MIN_CPS + v * (LightsDeviceConfig.MAX_CPS - LightsDeviceConfig.MIN_CPS))));
            y[0] += 24;
            toggle(w, contentX, y, "Aura Swing Hand", cfg::isAuraSwingHand, cfg::setAuraSwingHand, null);
            toggle(w, contentX, y, "Chat Feedback", cfg::isChatFeedback, cfg::setChatFeedback, null);
        }

        header(w, contentX, y, contentWidth, "Triggerbot");
        toggle(w, contentX, y, "Lights Triggerbot", cfg::isTriggerEnabledRaw, cfg::setTriggerEnabled, requestRebuild);
        if (cfg.isTriggerEnabledRaw()) {
            w.add(SettingsButtonWidget.cycling(delayText(cfg), (btn, dir) -> {
                cfg.setTriggerDelayMs((int) SettingsButtonWidget.step(cfg.getTriggerDelayMs(), 25, 0,
                        LightsDeviceConfig.MAX_TRIGGER_DELAY_MS, dir));
                cfg.save();
                btn.setMessage(delayText(cfg));
            }).bounds(contentX, y[0], BTN_W, 20).build());
            y[0] += 24;
            w.add(SettingsButtonWidget.cycling(cooldownText(cfg), (btn, dir) -> {
                cfg.setTriggerCooldownMs((int) SettingsButtonWidget.step(cfg.getTriggerCooldownMs(), 50, 0,
                        LightsDeviceConfig.MAX_TRIGGER_COOLDOWN_MS, dir));
                cfg.save();
                btn.setMessage(cooldownText(cfg));
            }).bounds(contentX, y[0], BTN_W, 20).build());
            y[0] += 24;
            toggle(w, contentX, y, "Triggerbot Swing Hand", cfg::isTriggerSwingHand, cfg::setTriggerSwingHand, null);
        }

        y[0] += 4;
        w.add(new StringWidget(contentX, y[0], contentWidth, 12, LightsDeviceFeature.statusText(), Minecraft.getInstance().font));
        y[0] += 16;
        return w;
    }

    private static Component delayText(LightsDeviceConfig cfg) {
        return Component.literal("Triggerbot Delay: " + cfg.getTriggerDelayMs() + "ms");
    }

    private static Component cooldownText(LightsDeviceConfig cfg) {
        return Component.literal("Triggerbot Cooldown: " + cfg.getTriggerCooldownMs() + "ms");
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(SectionHeaders.row(x, y[0], width, text, true));
        y[0] += 16;
    }

    /** ON/OFF toggle button; a non-null {@code rebuild} rebuilds the tab (used by the two switches). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            LightsDeviceConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
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
                saveOnRelease(() -> LightsDeviceConfig.getInstance().save());
            }
        });
    }
}
