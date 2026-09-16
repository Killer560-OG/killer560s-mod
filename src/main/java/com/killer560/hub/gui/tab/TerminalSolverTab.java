package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.terminals.TerminalQolConfig;
import com.killer560.hub.terminals.TerminalSolverConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Terminal Solver settings: master toggle, a scale slider for the highlight overlay, and a per-type
 *  toggle for each of the 5 covered terminals - per killer560's explicit request (2026-09-08) for
 *  "a toggleable option for gui scale size, and selecting which terminals it works on." Auto Terminals
 *  (2026-09-09) - real auto-clicking, cheat build only - moved out to its own {@link AutoTerminalTab}
 *  per killer560's explicit "make auto terms into its own section in dungeons" follow-up request; this
 *  tab's own solving/highlighting works identically on both builds regardless.
 *  <p>
 *  Overlay Colours (2026-09-15, killer560: "add the option to set custom colors for terminal overlays")
 *  - one picker button per real drawing role (see {@link TerminalSolverConfig.OverlayColor}), plus a
 *  Reset Colours button. Everything is legit-build behaviour, so every header here is the normal orange
 *  {@link SectionHeaders} one, not the cheat red.
 *  <p>
 *  Terminal QoL (2026-09-16) - five small terminal-adjacent features ported from Devonian, see
 *  {@link com.killer560.hub.terminals.TerminalQolFeature}. They live under this tab because they are all
 *  terminal-scoped, but none of them touches the solver: every one works with the solver toggle off.
 *  Legit-build behaviour too (Melody Keys sends one click per keypress, the same class as the mod's existing
 *  Slot Binds / Loadout Keybinds / Custom Leap Menu keys), so orange headers throughout. */
public class TerminalSolverTab extends BaseTab implements KeyCaptureTab {

    /** True while the Drop Key button is waiting for the next keypress - see {@link #onKeyCaptured}. */
    private boolean capturingDropKey = false;

    public TerminalSolverTab() {
        super("Terminal Solver");
    }

    @Override
    public boolean isListeningForKey() {
        return capturingDropKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        // Escape clears the binding, same convention DoorHelpersTab/AbilityTimersTab already use. -1 here means
        // "unbound", which for this feature is a real, useful setting: nothing at all can drop an item while a
        // terminal is open (Devonian's own default for TerminalDropKey is GLFW_KEY_UNKNOWN too).
        cfg.setDropKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingDropKey = false;
        cfg.save();
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
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        // "It should have an option to also send coords on open mel" (killer560, 2026-09-16) - took over
        // from the Posmsg "Mel" preset. Only shown while Melody detection is on, because that detection
        // is what fires it.
        if (TerminalSolverConfig.getInstance().isMelodyEnabled()) {
            widgets.add(SettingsButtonWidget.builder(melodySendCoordsText(), btn -> {
                        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                        cfg.setMelodySendCoordsOnOpen(!cfg.isMelodySendCoordsOnOpen());
                        cfg.save();
                        btn.setMessage(melodySendCoordsText());
                    }).bounds(contentX, y, 220, 20).build());
            y += 24;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("Types \"Mel at x, y, z\" into party chat when the Melody terminal opens."),
                    Minecraft.getInstance().font));
            y += 18;
        }
        y += 6;

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
        y += 24;

        // ------------------------------------------------------------------ overlay colours
        y = buildColorSection(widgets, contentX, y, contentWidth, requestRebuild);

        // ------------------------------------------------------------------ terminal QoL
        y = buildQolSection(widgets, contentX, y, contentWidth, requestRebuild);

        return widgets;
    }

    /** Terminal QoL - Protection / Drop Key / Melody Keys / GUI Scale / Hide Completion. Each master toggle
     *  reveals its own sub-settings on a rebuild, the same collapse pattern {@code DoorHelpersTab} uses, so the
     *  section is five buttons tall until something is actually turned on. */
    private int buildQolSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth,
                                Runnable requestRebuild) {
        Minecraft mc = Minecraft.getInstance();
        TerminalQolConfig cfg = TerminalQolConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Terminal QoL", false), mc.font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Works with the solver above on or off."), mc.font));
        y += 16;

        // ---- Terminal Protection ----
        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Protection", cfg.isProtectionEnabledRaw()), btn -> {
                    cfg.setProtectionEnabled(!cfg.isProtectionEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isProtectionEnabledRaw()) {
            int span = TerminalQolConfig.MAX_PROTECTION_MS - TerminalQolConfig.MIN_PROTECTION_MS;
            widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, thresholdText(cfg),
                    (cfg.getProtectionThresholdMs() - TerminalQolConfig.MIN_PROTECTION_MS) / (double) span) {
                @Override
                protected void updateMessage() {
                    setMessage(thresholdText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setProtectionThresholdMs(
                            (int) Math.round(TerminalQolConfig.MIN_PROTECTION_MS + this.value * span));
                    cfg.save();
                }
            });
            widgets.add(SettingsButtonWidget.builder(onOff("Subtract Ping", cfg.isProtectionSubtractPing()), btn -> {
                        cfg.setProtectionSubtractPing(!cfg.isProtectionSubtractPing());
                        cfg.save();
                        btn.setMessage(onOff("Subtract Ping", cfg.isProtectionSubtractPing()));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("Eats the first click that lands this soon after a terminal"), mc.font));
            y += 11;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("opens. 400ms minus your ping is the usual safe value."), mc.font));
            y += 16;
        }

        // ---- Drop Key ----
        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Drop Key", cfg.isDropKeyEnabledRaw()), btn -> {
                    cfg.setDropKeyEnabled(!cfg.isDropKeyEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isDropKeyEnabledRaw()) {
            widgets.add(SettingsButtonWidget.builder(dropKeyText(cfg), btn -> {
                        capturingDropKey = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, col2W, 18).build());
            y += 20;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("Your Drop key becomes this while a terminal is open, so"), mc.font));
            y += 11;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("Q can't throw your blade. Escape = nothing can drop."), mc.font));
            y += 16;
        }

        // ---- Melody Keys ----
        widgets.add(SettingsButtonWidget.builder(onOff("Melody Keys 1-4", cfg.isMelodyKeysEnabledRaw()), btn -> {
                    cfg.setMelodyKeysEnabled(!cfg.isMelodyKeysEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Melody Keys 1-4", cfg.isMelodyKeysEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Press 1-4 to click Melody's four rows instead of mousing."), mc.font));
        y += 18;

        // ---- Terminal GUI Scale ----
        int scaleSpan = TerminalQolConfig.MAX_GUI_SCALE - TerminalQolConfig.MIN_GUI_SCALE;
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, terminalScaleText(cfg),
                (cfg.getTerminalGuiScaleRaw() - TerminalQolConfig.MIN_GUI_SCALE) / (double) scaleSpan) {
            @Override
            protected void updateMessage() {
                setMessage(terminalScaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setTerminalGuiScale((int) Math.round(TerminalQolConfig.MIN_GUI_SCALE + this.value * scaleSpan));
                cfg.save();
            }
        });
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, melodyScaleText(cfg),
                (cfg.getMelodyGuiScaleRaw() - TerminalQolConfig.MIN_GUI_SCALE) / (double) scaleSpan) {
            @Override
            protected void updateMessage() {
                setMessage(melodyScaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setMelodyGuiScale((int) Math.round(TerminalQolConfig.MIN_GUI_SCALE + this.value * scaleSpan));
                cfg.save();
            }
        });
        y += 20;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Minecraft's GUI scale while a terminal is open. Auto = off."), mc.font));
        y += 18;

        // ---- Hide Completion ----
        widgets.add(SettingsButtonWidget.builder(onOff("Hide Completion Titles", cfg.isHideCompletionTitlesRaw()), btn -> {
                    cfg.setHideCompletionTitles(!cfg.isHideCompletionTitlesRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Completion Chat", cfg.isHideCompletionChatRaw()), btn -> {
                    cfg.setHideCompletionChat(!cfg.isHideCompletionChatRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;

        if (cfg.isHideCompletionChatRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§eNote: this also hides Terminal Timers' own split times,"), mc.font));
            y += 11;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§ewhich are appended to these same lines."), mc.font));
            y += 15;
        }

        if (cfg.isHideCompletionTitlesRaw() || cfg.isHideCompletionChatRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Only Hide Others'", cfg.isHideCompletionOnlyOthers()), btn -> {
                        cfg.setHideCompletionOnlyOthers(!cfg.isHideCompletionOnlyOthers());
                        cfg.save();
                        btn.setMessage(onOff("Only Hide Others'", cfg.isHideCompletionOnlyOthers()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }

        return y;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component thresholdText(TerminalQolConfig cfg) {
        return Component.literal("Threshold: " + cfg.getProtectionThresholdMs() + "ms");
    }

    private static Component terminalScaleText(TerminalQolConfig cfg) {
        int v = cfg.getTerminalGuiScaleRaw();
        return Component.literal("Terminal Scale: " + (v == 0 ? "Auto" : String.valueOf(v)));
    }

    private static Component melodyScaleText(TerminalQolConfig cfg) {
        int v = cfg.getMelodyGuiScaleRaw();
        return Component.literal("Melody Scale: " + (v == 0 ? "Auto" : String.valueOf(v)));
    }

    private Component dropKeyText(TerminalQolConfig cfg) {
        if (capturingDropKey) {
            return Component.literal("Press any key...");
        }
        int key = cfg.getDropKeyCode();
        String name = key == -1 ? "Unbound" : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal("Drop Key: " + name);
    }

    /** One colour-picker button per real drawing role, grouped the same way the overlay itself is:
     *  per-terminal-type highlights first, then Melody's own per-role board palette, then the shared
     *  panel/text chrome. Every button opens the existing {@link ColorPickerScreen} (live preview while
     *  dragging) and saves on every change, so a colour survives a restart like every other setting in
     *  this mod. Single 220-wide column, matching the toggles above - two 108-wide columns would clip
     *  labels like "Melody Moving Piece Colour". */
    private static int buildColorSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth,
                                         Runnable requestRebuild) {
        Minecraft mc = Minecraft.getInstance();

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Overlay Colours", false), mc.font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Defaults are the stock orange theme. Alpha is adjustable;"), mc.font));
        y += 11;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("the panel background stays opaque so nothing bleeds through."), mc.font));
        y += 16;

        TerminalSolverConfig.OverlayColor[] order = {
                TerminalSolverConfig.OverlayColor.PANES,
                TerminalSolverConfig.OverlayColor.STARTS_WITH,
                TerminalSolverConfig.OverlayColor.SELECT,
                TerminalSolverConfig.OverlayColor.NUMBERS_NEXT,
                TerminalSolverConfig.OverlayColor.NUMBERS_AFTER_NEXT,
                TerminalSolverConfig.OverlayColor.NUMBERS_THIRD,
                TerminalSolverConfig.OverlayColor.RUBIX_LEFT_CLICK,
                TerminalSolverConfig.OverlayColor.RUBIX_RIGHT_CLICK,
                TerminalSolverConfig.OverlayColor.MELODY_ENDPOINT,
                TerminalSolverConfig.OverlayColor.MELODY_MOVING,
                TerminalSolverConfig.OverlayColor.MELODY_BUTTON,
                TerminalSolverConfig.OverlayColor.MELODY_TRACK,
                TerminalSolverConfig.OverlayColor.PANEL_BACKGROUND,
                TerminalSolverConfig.OverlayColor.PANEL_BORDER,
                TerminalSolverConfig.OverlayColor.LABEL_TEXT,
                TerminalSolverConfig.OverlayColor.RUBIX_COUNT_TEXT,
        };

        for (TerminalSolverConfig.OverlayColor key : order) {
            widgets.add(colorButton(key, contentX, y, 220));
            y += 20;
        }
        y += 4;

        widgets.add(SettingsButtonWidget.builder(resetText(), btn -> {
                    TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
                    cfg.resetOverlayColors();
                    cfg.save();
                    // Full rebuild - every swatch above has to redraw in its restored colour, and a
                    // button can't reach its siblings' labels on its own.
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        return y;
    }

    /** One colour row. The swatch on the button label is redrawn live from the picker's own callback, so
     *  the menu matches the in-game colour the moment you let go of the slider (2026-09-14 rule - see
     *  {@link ColorSwatch}), without needing a whole tab rebuild per drag. */
    private static AbstractWidget colorButton(TerminalSolverConfig.OverlayColor key, int x, int y, int width) {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        return SettingsButtonWidget.builder(ColorSwatch.label(key.label(), cfg.getOverlayColor(key)), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, key.label(),
                            cfg.getOverlayColor(key), key.defaultArgb(), argb -> {
                        cfg.setOverlayColor(key, argb);
                        cfg.save();
                        btn.setMessage(ColorSwatch.label(key.label(), cfg.getOverlayColor(key)));
                    }));
                }).bounds(x, y, width, 18).build();
    }

    /** The "already default" hint goes AFTER a colon on purpose - {@code SettingTooltips#key} cuts the
     *  label at the first ':', so the tooltip key stays "reset colours" in both states instead of
     *  changing out from under {@code SettingTooltipsData} whenever the colours are stock. */
    private static Component resetText() {
        return Component.literal("Reset Colours"
                + (TerminalSolverConfig.getInstance().isOverlayColorsDefault() ? ": §7default" : ""));
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

    private static Component melodySendCoordsText() {
        return Component.literal("Send Mel Coords On Open: "
                + (TerminalSolverConfig.getInstance().isMelodySendCoordsOnOpen() ? "§aON" : "§cOFF"));
    }

    private static Component melodyText() {
        return Component.literal("Melody: " + (TerminalSolverConfig.getInstance().isMelodyEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component numbersThreeTierText() {
        return Component.literal("Numbers 3-Tier Reveal: " + (TerminalSolverConfig.getInstance().isNumbersThreeTierReveal() ? "§aON" : "§cOFF"));
    }
}
