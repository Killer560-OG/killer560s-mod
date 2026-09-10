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

/** Auto Terminals settings - real auto-clicking, cheat build only. Split out to its own tab
 *  (2026-09-09) per killer560's explicit "make auto terms into its own section in dungeons" follow-up
 *  request - previously lived as a lower section of {@link TerminalSolverTab}. Only ever added to
 *  {@link DungeonTab}'s tab list at all on the cheat build (see that class) - the legit build has no
 *  Auto Terminals functionality whatsoever, not even a visible-but-disabled tab. */
public class AutoTerminalTab extends BaseTab {

    public AutoTerminalTab() {
        super("Auto Terminals");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("A real macro against Hypixel's rules - auto-clicks Floor 7 terminals."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(autoEnabledText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoTerminalsEnabled(!cfg.isAutoTerminalsEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!TerminalSolverConfig.getInstance().isAutoTerminalsEnabled()) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Terminals to auto-click:"), Minecraft.getInstance().font));
        y += 16;

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

        int half = (contentWidth - 8) / 2;
        double minNormalized = (TerminalSolverConfig.getInstance().getAutoClickMinDelayMs() - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS)
                / (double) (TerminalSolverConfig.MAX_AUTO_CLICK_DELAY_MS - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(contentX, y, half, 20, minDelayText(), minNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(minDelayText());
            }

            @Override
            protected void applyValue() {
                TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                int newDelay = (int) Math.round(TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS
                        + this.value * (TerminalSolverConfig.MAX_AUTO_CLICK_DELAY_MS - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS));
                c.setAutoClickMinDelayMs(newDelay);
                c.save();
            }
        });

        double maxNormalized = (TerminalSolverConfig.getInstance().getAutoClickMaxDelayMs() - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS)
                / (double) (TerminalSolverConfig.MAX_AUTO_CLICK_DELAY_MS - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS);
        widgets.add(new ThemedSliderButton(contentX + half + 8, y, half, 20, maxDelayText(), maxNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(maxDelayText());
            }

            @Override
            protected void applyValue() {
                TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                int newDelay = (int) Math.round(TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS
                        + this.value * (TerminalSolverConfig.MAX_AUTO_CLICK_DELAY_MS - TerminalSolverConfig.MIN_AUTO_CLICK_DELAY_MS));
                c.setAutoClickMaxDelayMs(newDelay);
                c.save();
            }
        });
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("A fresh random delay between Min and Max is picked for every click."),
                Minecraft.getInstance().font));
        y += 22;

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

        return widgets;
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

    private static Component minDelayText() {
        return Component.literal("Min Delay: " + TerminalSolverConfig.getInstance().getAutoClickMinDelayMs() + "ms");
    }

    private static Component maxDelayText() {
        return Component.literal("Max Delay: " + TerminalSolverConfig.getInstance().getAutoClickMaxDelayMs() + "ms");
    }

    private static Component blockInputText() {
        return Component.literal("Block Input While Auto-Clicking: " + (TerminalSolverConfig.getInstance().isBlockInputWhileAutoClicking() ? "§aON" : "§cOFF"));
    }

    private static Component melodyLookaheadText() {
        return Component.literal("Melody Lookahead Clicks: " + TerminalSolverConfig.getInstance().getMelodyLookaheadClicks());
    }
}
