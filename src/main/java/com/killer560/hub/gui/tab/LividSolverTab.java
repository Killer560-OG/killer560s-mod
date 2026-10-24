package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.boss.LividSolverConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Livid Solver settings - see {@link com.killer560.hub.boss.LividSolverFeature}'s class doc for the
 *  real Odin-ported wool/entity identification this is built on. */
public class LividSolverTab extends BaseTab {

    private static final com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver SOLVER =
            com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.LIVID;

    public LividSolverTab() {
        super("Livid Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        LividSolverConfig cfg = LividSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Livid Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Invuln Timer", cfg.isShowTimer()), btn -> {
                    cfg.setShowTimer(!cfg.isShowTimer());
                    cfg.save();
                    btn.setMessage(onOff("Show Invuln Timer", cfg.isShowTimer()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(onOff("Draw Line To Livid", cfg.isShowLine()), btn -> {
                    cfg.setShowLine(!cfg.isShowLine());
                    cfg.save();
                    btn.setMessage(onOff("Draw Line To Livid", cfg.isShowLine()));
                }).bounds(contentX, y, contentWidth, 18).build());

        // Solver Highlight, and its Style (outline or full block). The Through Walls row is cheat-only and sits at the bottom.
        SolverEspRows.append(widgets, SOLVER, contentX, contentWidth, y, true, false);
        y = below(widgets, y);

        // killer560, 2026-10-18: the color of the highlight and the tracer - the Livid's own, or the two set here.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Livid Color", false),
                Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(colourModeText(cfg), (btn, dir) -> {
                    cfg.setColourMode(SettingsButtonWidget.step(cfg.getColourMode(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        if (cfg.getColourMode() == LividSolverConfig.ColourMode.CUSTOM) {
            int half = (contentWidth - 6) / 2;
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Highlight Color", cfg.getHighlightColour()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Highlight Color",
                                cfg.getHighlightColour(), LividSolverConfig.DEFAULT_CUSTOM_COLOUR, argb -> {
                            cfg.setHighlightColour(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Tracer Color", cfg.getTracerColour()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Tracer Color",
                                cfg.getTracerColour(), LividSolverConfig.DEFAULT_CUSTOM_COLOUR, argb -> {
                            cfg.setTracerColour(argb);
                            cfg.save();
                        }));
                    }).bounds(contentX + half + 6, y, contentWidth - half - 6, 18).build());
            y += 22;
        }
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, thicknessText(cfg),
                (cfg.getTracerThickness() - LividSolverConfig.MIN_TRACER_THICKNESS)
                        / (LividSolverConfig.MAX_TRACER_THICKNESS - LividSolverConfig.MIN_TRACER_THICKNESS)) {
            @Override
            protected void updateMessage() {
                setMessage(thicknessText(cfg));
            }

            @Override
            protected void applyValue() {
                float span = LividSolverConfig.MAX_TRACER_THICKNESS - LividSolverConfig.MIN_TRACER_THICKNESS;
                float raw = LividSolverConfig.MIN_TRACER_THICKNESS + (float) this.value * span;
                cfg.setTracerThickness(Math.round(raw * 2f) / 2f);
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 26;

        // Cheat only (killer560, 2026-10-18): a legit build never draws Livid's highlight through blocks. Red header, bottom of the tab.
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Through Walls", true),
                    Minecraft.getInstance().font));
            y += 16;
            SolverEspRows.throughWallsRow(widgets, SOLVER, contentX, contentWidth, y);
        }

        return widgets;
    }

    /** The y under everything in {@code widgets} so far, with the same 6-unit gap the shared rows use. */
    private static int below(List<AbstractWidget> widgets, int fallback) {
        int y = fallback;
        for (AbstractWidget w : widgets) {
            y = Math.max(y, w.getY() + w.getHeight() + 6);
        }
        return y;
    }

    private static Component colourModeText(LividSolverConfig cfg) {
        return Component.literal("Livid Color: §6" + cfg.getColourMode().label);
    }

    private static Component thicknessText(LividSolverConfig cfg) {
        return Component.literal(String.format(Locale.US, "Tracer Thickness: %.1f", cfg.getTracerThickness()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
