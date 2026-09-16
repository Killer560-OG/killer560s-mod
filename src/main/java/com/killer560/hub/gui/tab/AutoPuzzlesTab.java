package com.killer560.hub.gui.tab;

import com.killer560.hub.autopuzzles.AutoPuzzlesConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
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

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
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
            y += 24;
        }
        y += 6;

        // ---- bow puzzles (QUOI shared "Bow settings") ----
        y = toggle(widgets, contentX, y, contentWidth, "Auto Blaze", cfg.isAutoBlazeEnabled(),
                () -> cfg.setAutoBlazeEnabled(!cfg.isAutoBlazeEnabled()), cfg, requestRebuild);
        y = toggle(widgets, contentX, y, contentWidth, "Auto Creeper Beams", cfg.isAutoBeamsEnabled(),
                () -> cfg.setAutoBeamsEnabled(!cfg.isAutoBeamsEnabled()), cfg, requestRebuild);
        y = toggle(widgets, contentX, y, contentWidth, "Auto Ice Path", cfg.isAutoIcePathEnabled(),
                () -> cfg.setAutoIcePathEnabled(!cfg.isAutoIcePathEnabled()), cfg, requestRebuild);
        if (cfg.isAutoBlazeEnabled() || cfg.isAutoBeamsEnabled() || cfg.isAutoIcePathEnabled()) {
            widgets.add(rangeSlider(contentX, y, contentWidth, "Shoot Cooldown", cfg.getShootCooldownMs(),
                    AutoPuzzlesConfig.SHOOT_CD_MIN, AutoPuzzlesConfig.SHOOT_CD_MAX, AutoPuzzlesConfig.COOLDOWN_STEP_MS, "ms",
                    v -> {
                        cfg.setShootCooldownMs(v);
                        cfg.save();
                    }));
            y += 22;
            widgets.add(rangeSlider(contentX, y, contentWidth, "Miss Cooldown", cfg.getMissCooldownMs(),
                    AutoPuzzlesConfig.MISS_CD_MIN, AutoPuzzlesConfig.MISS_CD_MAX, AutoPuzzlesConfig.COOLDOWN_STEP_MS, "ms",
                    v -> {
                        cfg.setMissCooldownMs(v);
                        cfg.save();
                    }));
            y += 24;
        }
        y += 6;

        // ---- click puzzles ----
        y = toggle(widgets, contentX, y, contentWidth, "Auto Boulder", cfg.isAutoBoulderEnabled(),
                () -> cfg.setAutoBoulderEnabled(!cfg.isAutoBoulderEnabled()), cfg, requestRebuild);
        if (cfg.isAutoBoulderEnabled()) {
            widgets.add(delaySlider(contentX, y, contentWidth, "Boulder Click Delay", cfg.getBoulderDelayMs(),
                    ms -> {
                        cfg.setBoulderDelayMs(ms);
                        cfg.save();
                    }));
            y += 24;
        }
        y = toggle(widgets, contentX, y, contentWidth, "Auto Water Board", cfg.isAutoWaterEnabled(),
                () -> cfg.setAutoWaterEnabled(!cfg.isAutoWaterEnabled()), cfg, requestRebuild);
        y = toggle(widgets, contentX, y, contentWidth, "Auto Tic Tac Toe", cfg.isAutoTicTacToeEnabled(),
                () -> cfg.setAutoTicTacToeEnabled(!cfg.isAutoTicTacToeEnabled()), cfg, requestRebuild);
        y += 6;

        // ---- movement puzzles ----
        y = toggle(widgets, contentX, y, contentWidth, "Auto Teleport Maze", cfg.isAutoTeleportMazeEnabled(),
                () -> cfg.setAutoTeleportMazeEnabled(!cfg.isAutoTeleportMazeEnabled()), cfg, requestRebuild);
        y = toggle(widgets, contentX, y, contentWidth, "Auto Ice Fill", cfg.isAutoIceFillEnabled(),
                () -> cfg.setAutoIceFillEnabled(!cfg.isAutoIceFillEnabled()), cfg, requestRebuild);
        if (cfg.isAutoIceFillEnabled()) {
            widgets.add(rangeSlider(contentX, y, contentWidth, "Ice Fill Delay", cfg.getIceFillDelayTicks(),
                    AutoPuzzlesConfig.ICE_FILL_DELAY_MIN, AutoPuzzlesConfig.ICE_FILL_DELAY_MAX, 1, "t",
                    v -> {
                        cfg.setIceFillDelayTicks(v);
                        cfg.save();
                    }));
            y += 24;
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(onOff("Etherwarp Reposition", cfg.getEtherwarpRepositionRaw()), btn -> {
                    cfg.setEtherwarpReposition(!cfg.getEtherwarpRepositionRaw());
                    cfg.save();
                    btn.setMessage(onOff("Etherwarp Reposition", cfg.getEtherwarpRepositionRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Every auto needs its puzzle's solver on. Bow autos shoot the held shortbow."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Etherwarp Reposition: AOTV-warps you to the puzzle's standing spots."),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static int toggle(List<AbstractWidget> widgets, int x, int y, int width, String label, boolean value,
                              Runnable flip, AutoPuzzlesConfig cfg, Runnable requestRebuild) {
        widgets.add(SettingsButtonWidget.builder(onOff(label, value), btn -> {
                    flip.run();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y, width, 20).build());
        return y + 24;
    }

    private static ThemedSliderButton rangeSlider(int x, int y, int width, String label, int current, int min, int max,
                                                  int step, String unit, IntConsumer onChange) {
        double norm = (current - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, Component.literal(label + ": §6" + current + unit), norm) {
            private int val() {
                int steps = (max - min) / step;
                return min + (int) Math.round(this.value * steps) * step;
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label + ": §6" + val() + unit));
            }

            @Override
            protected void applyValue() {
                onChange.accept(val());
            }
        };
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
