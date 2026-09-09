package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.terminals.TerminalSolverConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Terminal Solver settings: master toggle, a scale slider for the highlight overlay, and a per-type
 *  toggle for each of the 5 covered terminals - per killer560's explicit request (2026-09-08) for
 *  "a toggleable option for gui scale size, and selecting which terminals it works on." Solver Only -
 *  no auto-clicking toggle here on purpose, per his explicit "Not auto terminals yet just the solver." */
public class TerminalSolverTab extends BaseTab {

    public TerminalSolverTab() {
        super("Terminal Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        double scaleNormalized = (TerminalSolverConfig.getInstance().getScale() - TerminalSolverConfig.MIN_SCALE)
                / (TerminalSolverConfig.MAX_SCALE - TerminalSolverConfig.MIN_SCALE);
        widgets.add(new ThemedSliderButton(contentX, y, 220, 20, scaleText(), scaleNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText());
            }

            @Override
            protected void applyValue() {
                TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                float newScale = (float) (TerminalSolverConfig.MIN_SCALE
                        + this.value * (TerminalSolverConfig.MAX_SCALE - TerminalSolverConfig.MIN_SCALE));
                c.setScale(newScale);
                c.save();
            }
        });
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Terminals to solve:"), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(panesText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setPanesEnabled(!cfg.isPanesEnabled());
                    cfg.save();
                    btn.setMessage(panesText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(rubixText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setRubixEnabled(!cfg.isRubixEnabled());
                    cfg.save();
                    btn.setMessage(rubixText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(numbersText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setNumbersEnabled(!cfg.isNumbersEnabled());
                    cfg.save();
                    btn.setMessage(numbersText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(startsWithText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setStartsWithEnabled(!cfg.isStartsWithEnabled());
                    cfg.save();
                    btn.setMessage(startsWithText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(selectText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setSelectEnabled(!cfg.isSelectEnabled());
                    cfg.save();
                    btn.setMessage(selectText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Highlights the correct slot(s) to click - never clicks for you."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Terminal Solver: "
                + (TerminalSolverConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component scaleText() {
        return Component.literal(String.format("Highlight Scale: %.0f%%", TerminalSolverConfig.getInstance().getScale() * 100));
    }

    private static Component panesText() {
        return Component.literal("Panes: " + (TerminalSolverConfig.getInstance().isPanesEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component rubixText() {
        return Component.literal("Rubix: " + (TerminalSolverConfig.getInstance().isRubixEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component numbersText() {
        return Component.literal("Numbers: " + (TerminalSolverConfig.getInstance().isNumbersEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component startsWithText() {
        return Component.literal("Starts With: " + (TerminalSolverConfig.getInstance().isStartsWithEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component selectText() {
        return Component.literal("Select: " + (TerminalSolverConfig.getInstance().isSelectEnabled() ? "§aON" : "§cOFF"));
    }
}
