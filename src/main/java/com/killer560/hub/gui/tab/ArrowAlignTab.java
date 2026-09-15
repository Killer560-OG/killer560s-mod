package com.killer560.hub.gui.tab;

import com.killer560.hub.arrowalign.ArrowAlignConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Arrow Align (F7/M7 P3 third device) settings - see {@link com.killer560.hub.arrowalign.ArrowAlignFeature}. Laid out
 *  like {@link SimonSaysTab}: legit rows first, Trigger Bot/Aura only on the cheat build behind the red divider. */
public class ArrowAlignTab extends BaseTab {

    public ArrowAlignTab() {
        super("Arrow Align");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Solver", cfg.getSolverRaw()), btn -> {
                    cfg.setSolverEnabled(!cfg.getSolverRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.getSolverRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Frames", cfg.isHighlightFrames()), btn -> {
                        cfg.setHighlightFrames(!cfg.isHighlightFrames());
                        cfg.save();
                        btn.setMessage(onOff("Highlight Frames", cfg.isHighlightFrames()));
                    }).bounds(col2aX, y, col2W, 18).build());

            float minScale = ArrowAlignConfig.MIN_NUMBER_SCALE;
            float maxScale = ArrowAlignConfig.MAX_NUMBER_SCALE;
            double scaleNorm = (cfg.getNumberScale() - minScale) / (double) (maxScale - minScale);
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, scaleText(cfg), scaleNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(scaleText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setNumberScale((float) (minScale + this.value * (maxScale - minScale)));
                    cfg.save();
                }
            });
            y += 22;
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(onOff("Prevent Misclicks", cfg.getPreventMisclicksRaw()), btn -> {
                    cfg.setPreventMisclicksEnabled(!cfg.getPreventMisclicksRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(cfg.getPreventMisclicksRaw() ? col2aX : contentX, y,
                cfg.getPreventMisclicksRaw() ? col2W : contentWidth, 18).build());

        if (cfg.getPreventMisclicksRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Crouch To Override", cfg.isCrouchOverride()), btn -> {
                        cfg.setCrouchOverride(!cfg.isCrouchOverride());
                        cfg.save();
                        btn.setMessage(onOff("Crouch To Override", cfg.isCrouchOverride()));
                    }).bounds(col2bX, y, col2W, 18).build());
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Solve Time", cfg.getSolveTimeRaw()), btn -> {
                    cfg.setSolveTimeEnabled(!cfg.getSolveTimeRaw());
                    cfg.save();
                    btn.setMessage(onOff("Solve Time", cfg.getSolveTimeRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 28;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lCheat Build - Automation"), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.getTriggerBotRaw()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.getTriggerBotRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Aura", cfg.getAuraRaw()), btn -> {
                    cfg.setAuraEnabled(!cfg.getAuraRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.getTriggerBotRaw()) {
            int maxTrigger = ArrowAlignConfig.MAX_TRIGGER_BOT_DELAY_MS;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal("Trigger Bot Delay: " + cfg.getTriggerBotDelayMs() + "ms"),
                    cfg.getTriggerBotDelayMs() / (double) maxTrigger) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Trigger Bot Delay: " + cfg.getTriggerBotDelayMs() + "ms"));
                }

                @Override
                protected void applyValue() {
                    cfg.setTriggerBotDelayMs((int) Math.round(this.value * maxTrigger));
                    cfg.save();
                }
            });
            y += 22;
        }

        if (cfg.getAuraRaw()) {
            int maxAura = ArrowAlignConfig.MAX_AURA_DELAY_MS;
            // Min/Max push each other (see the config setters), so a rebuild keeps both slider handles honest.
            widgets.add(new ThemedSliderButton(col2aX, y, col2W, 18,
                    Component.literal("Min Delay: " + cfg.getAuraMinDelayMs() + "ms"),
                    cfg.getAuraMinDelayMs() / (double) maxAura) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Min Delay: " + cfg.getAuraMinDelayMs() + "ms"));
                }

                @Override
                protected void applyValue() {
                    cfg.setAuraMinDelayMs((int) Math.round(this.value * maxAura));
                    cfg.save();
                }

                @Override
                public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
                    super.onRelease(event);
                    requestRebuild.run();
                }
            });

            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18,
                    Component.literal("Max Delay: " + cfg.getAuraMaxDelayMs() + "ms"),
                    cfg.getAuraMaxDelayMs() / (double) maxAura) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Max Delay: " + cfg.getAuraMaxDelayMs() + "ms"));
                }

                @Override
                protected void applyValue() {
                    cfg.setAuraMaxDelayMs((int) Math.round(this.value * maxAura));
                    cfg.save();
                }

                @Override
                public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
                    super.onRelease(event);
                    requestRebuild.run();
                }
            });
            y += 22;

            double minRange = ArrowAlignConfig.MIN_AURA_RANGE;
            double maxRange = ArrowAlignConfig.MAX_AURA_RANGE;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, rangeText(cfg),
                    (cfg.getAuraRange() - minRange) / (maxRange - minRange)) {
                @Override
                protected void updateMessage() {
                    setMessage(rangeText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setAuraRange(minRange + this.value * (maxRange - minRange));
                    cfg.save();
                }
            });
        }
        return widgets;
    }

    private static Component scaleText(ArrowAlignConfig cfg) {
        return Component.literal(String.format(Locale.US, "Scale: %.2fx", cfg.getNumberScale()));
    }

    private static Component rangeText(ArrowAlignConfig cfg) {
        return Component.literal(String.format(Locale.US, "Aura Range: %.1f", cfg.getAuraRange()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
