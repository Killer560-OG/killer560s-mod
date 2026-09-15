package com.killer560.hub.gui.tab;

import com.killer560.hub.autopuzzles.AutoPuzzlesConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Auto Puzzles settings - see {@link com.killer560.hub.autopuzzles.AutoPuzzlesFeature}. Cheat build only:
 *  on the legit build this tab builds no widgets at all. */
public class AutoPuzzlesTab extends BaseTab {

    public AutoPuzzlesTab() {
        super("Auto Puzzles");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Quiz", cfg.isAutoQuizEnabled()), btn -> {
                    cfg.setAutoQuizEnabled(!cfg.isAutoQuizEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isAutoQuizEnabled()) {
            widgets.add(delaySlider(contentX, y, contentWidth, "Quiz Click Delay", cfg.getQuizDelayMs(),
                    ms -> {
                        cfg.setQuizDelayMs(ms);
                        cfg.save();
                    }));
            y += 24;
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Three Weirdos", cfg.isAutoWeirdosEnabled()), btn -> {
                    cfg.setAutoWeirdosEnabled(!cfg.isAutoWeirdosEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isAutoWeirdosEnabled()) {
            widgets.add(delaySlider(contentX, y, contentWidth, "Chest Open Delay", cfg.getWeirdosDelayMs(),
                    ms -> {
                        cfg.setWeirdosDelayMs(ms);
                        cfg.save();
                    }));
            y += 22;
            widgets.add(SettingsButtonWidget.builder(onOff("Talk to NPCs", cfg.getWeirdosTalkToNpcsRaw()), btn -> {
                        cfg.setWeirdosTalkToNpcs(!cfg.getWeirdosTalkToNpcsRaw());
                        cfg.save();
                        btn.setMessage(onOff("Talk to NPCs", cfg.getWeirdosTalkToNpcsRaw()));
                    }).bounds(contentX, y, contentWidth, 18).build());
        }
        return widgets;
    }

    private static ThemedSliderButton delaySlider(int x, int y, int width, String label, int currentMs, IntConsumer onChange) {
        double norm = currentMs / (double) AutoPuzzlesConfig.MAX_DELAY_MS;
        return new ThemedSliderButton(x, y, width, 18, sliderText(label, currentMs), norm) {
            private int ms() {
                int steps = AutoPuzzlesConfig.MAX_DELAY_MS / AutoPuzzlesConfig.DELAY_STEP_MS;
                return (int) Math.round(this.value * steps) * AutoPuzzlesConfig.DELAY_STEP_MS;
            }

            @Override
            protected void updateMessage() {
                setMessage(sliderText(label, ms()));
            }

            @Override
            protected void applyValue() {
                onChange.accept(ms());
            }
        };
    }

    private static Component sliderText(String label, int ms) {
        return Component.literal(label + ": §6" + ms + "ms");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
