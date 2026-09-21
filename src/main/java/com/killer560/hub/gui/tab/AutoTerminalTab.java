package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
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

    /** Only added to {@link DungeonTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        // Ban-risk warning already covered by the "Auto Terminals" tooltip on the toggle right below.
        widgets.add(SettingsButtonWidget.builder(autoEnabledText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setAutoTerminalsEnabled(!cfg.isAutoTerminalsEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        // Auto Terminals' own section collapses when its master toggle is off, but everything below it
        // (Hover Terminals) must stay reachable - hover is most useful precisely when auto-clicking is
        // OFF, so it can no longer live behind an early return on this toggle.
        if (TerminalSolverConfig.getInstance().isAutoTerminalsEnabled()) {
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

            widgets.add(SettingsButtonWidget.builder(blockInputText(), btn -> {
                        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                        cfg.setBlockInputWhileAutoClicking(!cfg.isBlockInputWhileAutoClicking());
                        cfg.save();
                        btn.setMessage(blockInputText());
                    }).bounds(contentX, y, 220, 20).build());
            y += 26;

            if (TerminalSolverConfig.getInstance().isAutoMelodyEnabled()) {
                widgets.add(SettingsButtonWidget.builder(melodySkipModeText(), btn -> {
                            TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                            cfg.setMelodySkipMode(cfg.getMelodySkipMode() == TerminalSolverConfig.MelodySkipMode.EDGES
                                    ? TerminalSolverConfig.MelodySkipMode.ALL
                                    : TerminalSolverConfig.MelodySkipMode.EDGES);
                            cfg.save();
                            requestRebuild.run();
                        }).bounds(contentX, y, 220, 20).build());
                y += 24;
                boolean skipAll = TerminalSolverConfig.getInstance().getMelodySkipMode() == TerminalSolverConfig.MelodySkipMode.ALL;

                if (!skipAll) {
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
                    // Already covered by the "Melody Lookahead Clicks" tooltip.
                }
            }
        }
        y += 8;

        // ---- Hover Terminals (2026-09-16) ----------------------------------------------------------
        // Lives in this tab rather than getting one of its own: it is the same cheat-build, same-package,
        // click-sending automation Auto Terminals is, it reuses that feature's own click path and pending-
        // click bookkeeping, and the two directly interact (auto takes precedence - see
        // HoverTerminalFeature). Keeping them on one screen is what makes that relationship visible.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Hover Terminals", true), Minecraft.getInstance().font));
        y += 16;
        // Description already covered by the "Hover Terminals" tooltip.

        widgets.add(SettingsButtonWidget.builder(hoverEnabledText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setHoverTerminalsEnabled(!cfg.isHoverTerminalsEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!TerminalSolverConfig.getInstance().isHoverTerminalsEnabledRaw()) {
            return widgets;
        }

        double hoverDelayNormalized = (TerminalSolverConfig.getInstance().getHoverDelayMs() - TerminalSolverConfig.MIN_HOVER_DELAY_MS)
                / (double) (TerminalSolverConfig.MAX_HOVER_DELAY_MS - TerminalSolverConfig.MIN_HOVER_DELAY_MS);
        widgets.add(new ThemedSliderButton(contentX, y, 220, 20, hoverDelayText(), hoverDelayNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(hoverDelayText());
            }

            @Override
            protected void applyValue() {
                TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                c.setHoverDelayMs((int) Math.round(TerminalSolverConfig.MIN_HOVER_DELAY_MS
                        + this.value * (TerminalSolverConfig.MAX_HOVER_DELAY_MS - TerminalSolverConfig.MIN_HOVER_DELAY_MS)));
                c.save();
            }
        });
        y += 26;

        double hoverJitterNormalized = (TerminalSolverConfig.getInstance().getHoverJitterMs() - TerminalSolverConfig.MIN_HOVER_JITTER_MS)
                / (double) (TerminalSolverConfig.MAX_HOVER_JITTER_MS - TerminalSolverConfig.MIN_HOVER_JITTER_MS);
        widgets.add(new ThemedSliderButton(contentX, y, 220, 20, hoverJitterText(), hoverJitterNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(hoverJitterText());
            }

            @Override
            protected void applyValue() {
                TerminalSolverConfig c = TerminalSolverConfig.getInstance();
                c.setHoverJitterMs((int) Math.round(TerminalSolverConfig.MIN_HOVER_JITTER_MS
                        + this.value * (TerminalSolverConfig.MAX_HOVER_JITTER_MS - TerminalSolverConfig.MIN_HOVER_JITTER_MS)));
                c.save();
            }
        });
        y += 26;

        widgets.add(SettingsButtonWidget.builder(hoverMelodyText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.setHoverMelodyEnabled(!cfg.isHoverMelodyEnabled());
                    cfg.save();
                    btn.setMessage(hoverMelodyText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        // Already covered by the "Hover Melody" tooltip.
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

    private static Component hoverEnabledText() {
        return Component.literal("Hover Terminals: " + (TerminalSolverConfig.getInstance().isHoverTerminalsEnabledRaw() ? "§aON" : "§cOFF"));
    }

    private static Component hoverDelayText() {
        return Component.literal("Hover Delay: " + TerminalSolverConfig.getInstance().getHoverDelayMs() + "ms");
    }

    private static Component hoverJitterText() {
        int jitter = TerminalSolverConfig.getInstance().getHoverJitterMs();
        return Component.literal("Hover Jitter: " + (jitter == 0 ? "Off" : "+0-" + jitter + "ms"));
    }

    private static Component hoverMelodyText() {
        return Component.literal("Hover Melody: " + (TerminalSolverConfig.getInstance().isHoverMelodyEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component melodySkipModeText() {
        return Component.literal("Melody Skip Mode: "
                + (TerminalSolverConfig.getInstance().getMelodySkipMode() == TerminalSolverConfig.MelodySkipMode.ALL ? "All" : "Edges"));
    }
}
