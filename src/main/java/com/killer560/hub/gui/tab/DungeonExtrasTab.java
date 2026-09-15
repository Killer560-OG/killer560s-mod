package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonextras.DungeonExtrasConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Custom Mage Beam (both builds), Auto Dialogue + Breaker Aura (cheat build only). */
public class DungeonExtrasTab extends BaseTab {

    public DungeonExtrasTab() {
        super("Dungeon Extras");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Mage Beam", cfg.isMageBeamEnabled()), btn -> {
                    cfg.setMageBeamEnabled(!cfg.isMageBeamEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (cfg.isMageBeamEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Hide Particles", cfg.isMageBeamHideParticles()), btn -> {
                        cfg.setMageBeamHideParticles(!cfg.isMageBeamHideParticles());
                        cfg.save();
                        btn.setMessage(onOff("Hide Particles", cfg.isMageBeamHideParticles()));
                    }).bounds(contentX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Fade", cfg.isMageBeamFade()), btn -> {
                        cfg.setMageBeamFade(!cfg.isMageBeamFade());
                        cfg.save();
                        btn.setMessage(onOff("Fade", cfg.isMageBeamFade()));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;

            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Beam Color", cfg.getMageBeamColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Beam Color",
                                cfg.getMageBeamColor(), DungeonExtrasConfig.DEFAULT_BEAM_COLOR, argb -> {
                            cfg.setMageBeamColor(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, col2W, 18).build());
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, widthText(cfg), (cfg.getMageBeamWidth() - 1f) / 5f) {
                @Override
                protected void updateMessage() {
                    setMessage(widthText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setMageBeamWidth(Math.round((1f + (float) this.value * 5f) * 2f) / 2f);
                    cfg.save();
                }
            });
            y += 20;

            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, durationText(cfg),
                    (cfg.getMageBeamDurationTicks() - 5) / 95.0) {
                @Override
                protected void updateMessage() {
                    setMessage(durationText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setMageBeamDurationTicks((int) Math.round(5 + this.value * 95));
                    cfg.save();
                }
            });
            y += 26;
        }

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lCheat Build - Automation"), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Dialogue", cfg.isAutoDialogueEnabledRaw()), btn -> {
                    cfg.setAutoDialogueEnabled(!cfg.isAutoDialogueEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isAutoDialogueEnabledRaw()) {
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, delayText(cfg),
                    cfg.getAutoDialogueDelayTicks() / 40.0) {
                @Override
                protected void updateMessage() {
                    setMessage(delayText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setAutoDialogueDelayTicks((int) Math.round(this.value * 40));
                    cfg.save();
                }
            });
            y += 20;

            EditBox filter = new EditBox(mc.font, contentX, y, contentWidth, 18, Component.literal("NPC filter"));
            filter.setMaxLength(200);
            filter.setHint(Component.literal("NPC names, comma-separated (blank = any)"));
            filter.setValue(cfg.getAutoDialogueNpcFilter());
            filter.setResponder(text -> {
                cfg.setAutoDialogueNpcFilter(text);
                cfg.save();
            });
            widgets.add(filter);
            y += 26;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Breaker Aura", cfg.isBreakerAuraEnabledRaw()), btn -> {
                    cfg.setBreakerAuraEnabled(!cfg.isBreakerAuraEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isBreakerAuraEnabledRaw()) {
            widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, reachText(cfg),
                    (cfg.getBreakerAuraReach() - 1.0) / 4.5) {
                @Override
                protected void updateMessage() {
                    setMessage(reachText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setBreakerAuraReach(Math.round((1.0 + this.value * 4.5) * 10.0) / 10.0);
                    cfg.save();
                }
            });
            widgets.add(SettingsButtonWidget.builder(onOff("Zero Ping", cfg.isBreakerAuraZeroPingRaw()), btn -> {
                        cfg.setBreakerAuraZeroPing(!cfg.isBreakerAuraZeroPingRaw());
                        cfg.save();
                        btn.setMessage(onOff("Zero Ping", cfg.isBreakerAuraZeroPingRaw()));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;

            widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, perCycleText(cfg),
                    (cfg.getBreakerAuraBlocksPerCycle() - 1) / 4.0) {
                @Override
                protected void updateMessage() {
                    setMessage(perCycleText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setBreakerAuraBlocksPerCycle((int) Math.round(1 + this.value * 4));
                    cfg.save();
                }
            });
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, cooldownText(cfg),
                    (cfg.getBreakerAuraCooldownTicks() - 1) / 19.0) {
                @Override
                protected void updateMessage() {
                    setMessage(cooldownText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setBreakerAuraCooldownTicks((int) Math.round(1 + this.value * 19));
                    cfg.save();
                }
            });
        }

        return widgets;
    }

    private static Component widthText(DungeonExtrasConfig cfg) {
        return Component.literal(String.format(Locale.US, "Width: %.1f", cfg.getMageBeamWidth()));
    }

    private static Component durationText(DungeonExtrasConfig cfg) {
        return Component.literal("Duration: " + cfg.getMageBeamDurationTicks() + " ticks");
    }

    private static Component delayText(DungeonExtrasConfig cfg) {
        return Component.literal("Delay: " + cfg.getAutoDialogueDelayTicks() + " ticks");
    }

    private static Component reachText(DungeonExtrasConfig cfg) {
        return Component.literal(String.format(Locale.US, "Reach: %.1f", cfg.getBreakerAuraReach()));
    }

    private static Component perCycleText(DungeonExtrasConfig cfg) {
        return Component.literal("Blocks/Cycle: " + cfg.getBreakerAuraBlocksPerCycle());
    }

    private static Component cooldownText(DungeonExtrasConfig cfg) {
        return Component.literal("Cooldown: " + cfg.getBreakerAuraCooldownTicks() + " ticks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
