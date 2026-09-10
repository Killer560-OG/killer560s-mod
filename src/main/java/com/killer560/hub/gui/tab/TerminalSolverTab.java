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
 *  "a toggleable option for gui scale size, and selecting which terminals it works on." Auto Terminals
 *  (2026-09-09) - real auto-clicking, cheat build only - is a separate section further down, per
 *  killer560's later explicit "Next thing I want to develop is auto terminals" request; the base
 *  solving/highlighting above still works identically on both builds. */
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

        widgets.add(SettingsButtonWidget.builder(customGuiText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setCustomGuiEnabled(!cfg.isCustomGuiEnabled());
                    cfg.save();
                    btn.setMessage(customGuiText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Custom GUI replaces the terminal with a bigger panel showing"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("only the slot(s) you actually need to click."),
                Minecraft.getInstance().font));
        y += 22;

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
        y += 24;

        // Per killer560's "there is no melody toggle in the terminals to solve box" report (2026-09-09,
        // round 11) - Melody has no solving logic of its own, but this still controls whether it's
        // detected at all (Custom GUI's hide-inventory treatment).
        widgets.add(SettingsButtonWidget.builder(melodyText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setMelodyEnabled(!cfg.isMelodyEnabled());
                    cfg.save();
                    btn.setMessage(melodyText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        widgets.add(SettingsButtonWidget.builder(numbersThreeTierText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setNumbersThreeTierReveal(!cfg.isNumbersThreeTierReveal());
                    cfg.save();
                    btn.setMessage(numbersThreeTierText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Numbers: also reveal the 3rd click, a fainter shade again."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Highlights the correct slot(s) to click - never clicks for you."),
                Minecraft.getInstance().font));
        y += 26;

        // Auto Terminals (2026-09-09) - a real macro (auto-clicking), cheat build only. Omitted entirely
        // on the legit build rather than shown-but-broken, same precedent ExperimentsTab's own Mode
        // toggle already sets for Auto ETable - TerminalSolverConfig#isAutoTerminalsEnabled can never
        // return true there regardless of what's saved, so a visible-but-nonfunctional toggle would
        // just look broken.
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y = buildAutoTerminalsSection(widgets, contentX, y, contentWidth, requestRebuild);
        }

        return widgets;
    }

    private int buildAutoTerminalsSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Runnable requestRebuild) {
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Auto Terminals - a real macro against Hypixel's rules."),
                Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(autoEnabledText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoTerminalsEnabled(!cfg.isAutoTerminalsEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!TerminalSolverConfig.getInstance().isAutoTerminalsEnabled()) {
            return y;
        }

        widgets.add(SettingsButtonWidget.builder(autoPanesText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoPanesEnabled(!cfg.isAutoPanesEnabled());
                    cfg.save();
                    btn.setMessage(autoPanesText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(autoRubixText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoRubixEnabled(!cfg.isAutoRubixEnabled());
                    cfg.save();
                    btn.setMessage(autoRubixText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(autoNumbersText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoNumbersEnabled(!cfg.isAutoNumbersEnabled());
                    cfg.save();
                    btn.setMessage(autoNumbersText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(autoStartsWithText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoStartsWithEnabled(!cfg.isAutoStartsWithEnabled());
                    cfg.save();
                    btn.setMessage(autoStartsWithText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(autoSelectText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoSelectEnabled(!cfg.isAutoSelectEnabled());
                    cfg.save();
                    btn.setMessage(autoSelectText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(autoMelodyText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoMelodyEnabled(!cfg.isAutoMelodyEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        double delayNormalized = (TerminalSolverConfig.getInstance().getAutoClickDelayMs() - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS)
                / (double) (TerminalSolverConfig.MAX_AUTO_CLICK_DELAY_MS - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(contentX, y, 220, 20, autoDelayText(), delayNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(autoDelayText());
            }

            @Override
            protected void applyValue() {
                TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                int newDelay = (int) Math.round(TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS
                        + this.value * (TerminalSolverConfig.MAX_AUTO_CLICK_DELAY_MS - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS));
                c.setAutoClickDelayMs(newDelay);
                c.save();
            }
        });
        y += 30;

        widgets.add(SettingsButtonWidget.builder(blockInputText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setBlockInputWhileAutoClicking(!cfg.isBlockInputWhileAutoClicking());
                    cfg.save();
                    btn.setMessage(blockInputText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (TerminalSolverConfig.getInstance().isAutoMelodyEnabled()) {
            double lookaheadNormalized = (TerminalSolverConfig.getInstance().getMelodyLookaheadClicks() - TerminalSolverConfig.MIN_MELODY_LOOKAHEAD)
                    / (double) (TerminalSolverConfig.MAX_MELODY_LOOKAHEAD - TerminalSolverConfig.MIN_MELODY_LOOKAHEAD);
            widgets.add(new ThemedSliderButton(contentX, y, 220, 20, melodyLookaheadText(), lookaheadNormalized) {
                @Override
                protected void updateMessage() {
                    setMessage(melodyLookaheadText());
                }

                @Override
                protected void applyValue() {
                    TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                    int newLookahead = (int) Math.round(TerminalSolverConfig.MIN_MELODY_LOOKAHEAD
                            + this.value * (TerminalSolverConfig.MAX_MELODY_LOOKAHEAD - TerminalSolverConfig.MIN_MELODY_LOOKAHEAD));
                    c.setMelodyLookaheadClicks(newLookahead);
                    c.save();
                }
            });
            y += 26;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("Melody: how many rows to click ahead once one matches (0 = none)."),
                    Minecraft.getInstance().font));
            y += 16;
        }

        return y;
    }

    private static Component enabledText() {
        return Component.literal("Terminal Solver: "
                + (TerminalSolverConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component scaleText() {
        return Component.literal(String.format("Highlight Scale: %.0f%%", TerminalSolverConfig.getInstance().getScale() * 100));
    }

    private static Component customGuiText() {
        return Component.literal("Custom GUI: " + (TerminalSolverConfig.getInstance().isCustomGuiEnabled() ? "§aON" : "§cOFF"));
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

    private static Component melodyText() {
        return Component.literal("Melody: " + (TerminalSolverConfig.getInstance().isMelodyEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component numbersThreeTierText() {
        return Component.literal("Numbers 3-Tier Reveal: " + (TerminalSolverConfig.getInstance().isNumbersThreeTierReveal() ? "§aON" : "§cOFF"));
    }

    private static Component autoEnabledText() {
        return Component.literal("Auto Terminals: " + (TerminalSolverConfig.getInstance().isAutoTerminalsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoPanesText() {
        return Component.literal("Auto Panes: " + (TerminalSolverConfig.getInstance().isAutoPanesEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoRubixText() {
        return Component.literal("Auto Rubix: " + (TerminalSolverConfig.getInstance().isAutoRubixEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoNumbersText() {
        return Component.literal("Auto Numbers: " + (TerminalSolverConfig.getInstance().isAutoNumbersEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoStartsWithText() {
        return Component.literal("Auto Starts With: " + (TerminalSolverConfig.getInstance().isAutoStartsWithEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoSelectText() {
        return Component.literal("Auto Select: " + (TerminalSolverConfig.getInstance().isAutoSelectEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoMelodyText() {
        return Component.literal("Auto Melody: " + (TerminalSolverConfig.getInstance().isAutoMelodyEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component autoDelayText() {
        return Component.literal("Auto-Click Delay: " + TerminalSolverConfig.getInstance().getAutoClickDelayMs() + "ms");
    }

    private static Component blockInputText() {
        return Component.literal("Block Input While Auto-Clicking: " + (TerminalSolverConfig.getInstance().isBlockInputWhileAutoClicking() ? "§aON" : "§cOFF"));
    }

    private static Component melodyLookaheadText() {
        return Component.literal("Melody Lookahead Clicks: " + TerminalSolverConfig.getInstance().getMelodyLookaheadClicks());
    }
}
