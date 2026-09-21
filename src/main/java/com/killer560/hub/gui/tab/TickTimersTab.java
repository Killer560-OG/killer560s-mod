package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.ticktimers.TickTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Tick Timers settings - see {@link com.killer560.hub.ticktimers.TickTimersFeature}'s class doc for
 *  the real Odin-ported chat triggers/tick counts this is built on.
 *  <p>
 *  <b>2026-09-21:</b> Storm's crush timer (HUD, title, purple pad highlight, pad cycle) moved here from the
 *  F7 Spots tab - killer560: "Move them to Tick Timers. One home per timer." See
 *  {@code com.killer560.hub.ticktimers.CrushTimer} for the countdown logic and the migration from
 *  {@code killer560smod-f7spots.json} in {@link TickTimersConfig#migrateFromF7SpotsCrush()}. */
public class TickTimersTab extends BaseTab {

    public TickTimersTab() {
        super("Tick Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff("Tick Timers", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Necron Start", cfg.isNecronTimer()), btn -> {
                    cfg.setNecronTimer(!cfg.isNecronTimer());
                    cfg.save();
                    btn.setMessage(onOff("Necron Start", cfg.isNecronTimer()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Goldor", cfg.isGoldorTimer()), btn -> {
                    cfg.setGoldorTimer(!cfg.isGoldorTimer());
                    cfg.save();
                    btn.setMessage(onOff("Goldor", cfg.isGoldorTimer()));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Storm", cfg.isStormTimer()), btn -> {
                    cfg.setStormTimer(!cfg.isStormTimer());
                    cfg.save();
                    btn.setMessage(onOff("Storm", cfg.isStormTimer()));
                }).bounds(col3, y, 108, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Ticks (vs Seconds)", cfg.isDisplayInTicks()), btn -> {
                    cfg.setDisplayInTicks(!cfg.isDisplayInTicks());
                    cfg.save();
                    btn.setMessage(onOff("Ticks (vs Seconds)", cfg.isDisplayInTicks()));
                }).bounds(col1, y, 160, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Symbol", cfg.isShowSymbol()), btn -> {
                    cfg.setShowSymbol(!cfg.isShowSymbol());
                    cfg.save();
                    btn.setMessage(onOff("Symbol", cfg.isShowSymbol()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Prefix", cfg.isShowPrefix()), btn -> {
                    cfg.setShowPrefix(!cfg.isShowPrefix());
                    cfg.save();
                    btn.setMessage(onOff("Show Prefix", cfg.isShowPrefix()));
                }).bounds(col1, y, 160, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Goldor Start", cfg.isGoldorStartTimer()), btn -> {
                    cfg.setGoldorStartTimer(!cfg.isGoldorStartTimer());
                    cfg.save();
                    btn.setMessage(onOff("Goldor Start", cfg.isGoldorStartTimer()));
                }).bounds(col3, y, 108, 18).build());
        y += 22;

        // Folded in from the old Goldor Frenzy Timer tab (2026-09-20 merge) - see TickTimersFeature's class doc.
        widgets.add(SettingsButtonWidget.builder(onOff("Show Total", cfg.isGoldorShowTotal()), btn -> {
                    cfg.setGoldorShowTotal(!cfg.isGoldorShowTotal());
                    cfg.save();
                    btn.setMessage(onOff("Show Total", cfg.isGoldorShowTotal()));
                }).bounds(col1, y, 108, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Death Tick", cfg.isClearDeathTick()), btn -> {
                    cfg.setClearDeathTick(!cfg.isClearDeathTick());
                    cfg.save();
                    btn.setMessage(onOff("Death Tick", cfg.isClearDeathTick()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        if (cfg.isClearDeathTick()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Stop At Boss", cfg.isDeathTickStopsAtBoss()), btn -> {
                        cfg.setDeathTickStopsAtBoss(!cfg.isDeathTickStopsAtBoss());
                        cfg.save();
                        btn.setMessage(onOff("Stop At Boss", cfg.isDeathTickStopsAtBoss()));
                    }).bounds(col3, y, 108, 18).build());
            y += 20;
        }
        y += 6;

        // ---- Storm Crush Timer (P2) - moved in from F7 Spots 2026-09-21, see class doc ----
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Storm Crush Timer (P2)", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Pad Cycle Timer", cfg::isPadCycleTimer, cfg::setPadCycleTimer));
        widgets.add(toggle(col2X, y, colW, "Crush Timer HUD", cfg::isCrushTimerEnabled, cfg::setCrushTimer));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Crush Title", cfg::isCrushTitleEnabled, cfg::setCrushTitle));
        widgets.add(toggle(col2X, y, colW, "Purple Pad Highlight", cfg::isCrushPadHighlightEnabled, cfg::setCrushPadHighlight));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Show All Pads", cfg::isCrushAllPads, cfg::setCrushAllPads));
        widgets.add(colorButton(col2X, y, colW, "Pad Color", cfg.getCrushPadColor(), TickTimersConfig.DEFAULT_PAD_COLOR,
                argb -> cfg.setCrushPadColor(argb)));
        y += 22;
        widgets.add(new ThemedSliderButton(contentX, y, colW, 18, intervalText(cfg),
                cfg.getCrushIntervalSeconds() / TickTimersConfig.MAX_CRUSH_INTERVAL) {
            @Override
            protected void updateMessage() {
                setMessage(intervalText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setCrushIntervalSeconds((float) (this.value * TickTimersConfig.MAX_CRUSH_INTERVAL));
                cfg.save();
            }
        });
        widgets.add(new ThemedSliderButton(col2X, y, colW, 18, warnText(cfg),
                cfg.getCrushWarnSeconds() / TickTimersConfig.MAX_CRUSH_WARN) {
            @Override
            protected void updateMessage() {
                setMessage(warnText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setCrushWarnSeconds((float) (this.value * TickTimersConfig.MAX_CRUSH_WARN));
                cfg.save();
            }
        });
        y += 22;
        widgets.add(new StringWidget(contentX, y + 4, colW, 12, Component.literal("§7Crush Trigger Text (optional)"), mc.font));
        y += 18;
        EditBox trigger = new EditBox(mc.font, contentX, y, contentWidth, 18, Component.literal("Crush Trigger Text"));
        trigger.setMaxLength(120);
        trigger.setValue(cfg.getCrushExtraTrigger());
        trigger.setResponder(text -> {
            cfg.setCrushExtraTrigger(text);
            cfg.save();
        });
        widgets.add(trigger);

        return widgets;
    }

    private static Component intervalText(TickTimersConfig cfg) {
        float v = cfg.getCrushIntervalSeconds();
        return Component.literal(v <= 0f ? "Crush Interval: §7Count Up"
                : String.format(Locale.US, "Crush Interval: %.1fs", v));
    }

    private static Component warnText(TickTimersConfig cfg) {
        return Component.literal(String.format(Locale.US, "Crush Warning: %.1fs", cfg.getCrushWarnSeconds()));
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                                java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, name, current, defaultColor, argb -> {
                setter.accept(argb);
                TickTimersConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
            setter.accept(!getter.getAsBoolean());
            TickTimersConfig.getInstance().save();
            btn.setMessage(onOff(label, getter.getAsBoolean()));
        }).bounds(x, y, width, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
