package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.simonsays.SimonSaysConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/** Simon Says solver + automation settings - see {@link com.killer560.hub.simonsays.SimonSaysFeature}'s
 *  class doc for the real device layout/detection logic this is built on (ported from Odin/QUOI/
 *  NoammAddons, confirmed against this exact Minecraft version). The cheat-build rows (Trigger Bot, Auto
 *  Start, Auto Solve) moved to their own red section, {@link AutoSimonSaysTab}, on 2026-10-04 (killer560:
 *  "Move all the cheat stuff from Simon Says into its own red Simon Says portion"); the history below is
 *  theirs. Re-laid-out (2026-09-21, killer560's own explicit request) into one
 *  red divider per feature - Trigger Bot, then Auto Start, then Auto Solve, in that order - instead of
 *  one shared "Cheat Build - Automation" divider over all three; Auto Restart SS/Restart Key now nest
 *  under Auto Solve's own divider and only draw while it, respectively Auto Restart itself, is on. Same
 *  red-header convention every other cheat-only section in this mod uses (see SectionHeaders, and
 *  MaskInvincibilityTab/ObjectHiderTab for the header+toggle pattern this copies). Column widths are
 *  computed from the real {@code contentWidth} passed in rather than a hardcoded guess - a hardcoded
 *  108px column is what clipped "Announce Progress: ON" / "Announce Key: Not Set" in a real screenshot
 *  killer560 sent, since the real panel is noticeably wider than that. */
public class SimonSaysTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingAnnounceKey = false;

    public SimonSaysTab() {
        super("Simon Says");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;
        int col3W = (contentWidth - gap * 2) / 3;
        int col3aX = contentX;
        int col3bX = contentX + col3W + gap;
        int col3cX = contentX + (col3W + gap) * 2;

        widgets.add(SettingsButtonWidget.builder(onOff("Simon Says", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Highlights", cfg.isSolverEnabled()), btn -> {
                    cfg.setSolverEnabled(!cfg.isSolverEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Show Highlights", cfg.isSolverEnabled()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Numbers", cfg.isNumberOverlay()), btn -> {
                    cfg.setNumberOverlay(!cfg.isNumberOverlay());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setStyle(nextStyle(cfg.getStyle()));
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(col2aX, y, col2W, 18).build());

        if (cfg.isNumberOverlay()) {
            double scaleNorm = (cfg.getNumberScale() - 0.25) / (3.0 - 0.25);
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18,
                    Component.literal(String.format(java.util.Locale.US, "Scale: %.2fx", cfg.getNumberScale())), scaleNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal(String.format(java.util.Locale.US, "Scale: %.2fx", cfg.getNumberScale())));
                }

                @Override
                protected void applyValue() {
                    cfg.setNumberScale((float) (0.25 + this.value * (3.0 - 0.25)));
                    cfg.save();
                }
            });
        }
        y += 20;

        // Opens a real color-picker screen (hue bar + saturation/value square + alpha) instead of
        // cycling a fixed palette - killer560's explicit request. Each button's own click handler
        // captures a fresh Minecraft/Screen reference each press so re-opening after a rebuild still
        // points at the current tab's own screen instance.
        widgets.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label("1st Color", cfg.getFirstColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "First Color",
                            cfg.getFirstColor(), SimonSaysConfig.DEFAULT_FIRST_COLOR, argb -> {
                        cfg.setFirstColor(argb);
                        cfg.save();
                    }));
                }).bounds(col3aX, y, col3W, 18).build());

        widgets.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label("2nd Color", cfg.getSecondColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Second Color",
                            cfg.getSecondColor(), SimonSaysConfig.DEFAULT_SECOND_COLOR, argb -> {
                        cfg.setSecondColor(argb);
                        cfg.save();
                    }));
                }).bounds(col3bX, y, col3W, 18).build());

        widgets.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label("3rd Color", cfg.getThirdColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Third Color+",
                            cfg.getThirdColor(), SimonSaysConfig.DEFAULT_THIRD_COLOR, argb -> {
                        cfg.setThirdColor(argb);
                        cfg.save();
                    }));
                }).bounds(col3cX, y, col3W, 18).build());
        y += 30;

        widgets.add(SettingsButtonWidget.builder(onOff("Prevent Misclicks", cfg.isPreventMisclicksEnabled()), btn -> {
                    cfg.setPreventMisclicksEnabled(!cfg.isPreventMisclicksEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Prevent Misclicks", cfg.isPreventMisclicksEnabled()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 28;

        widgets.add(SettingsButtonWidget.builder(onOff("Announce Progress", cfg.isAnnounceProgress()), btn -> {
                    cfg.setAnnounceProgress(!cfg.isAnnounceProgress());
                    cfg.save();
                    btn.setMessage(onOff("Announce Progress", cfg.isAnnounceProgress()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()), btn -> {
                    cfg.setPartyProgressTrackerEnabled(!cfg.isPartyProgressTrackerEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        Component keyLabel = capturingAnnounceKey ? Component.literal("Press any key...") : announceKeyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingAnnounceKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Message", cfg.isAutoSendResetMessage()), btn -> {
                    cfg.setAutoSendResetMessage(!cfg.isAutoSendResetMessage());
                    cfg.save();
                    btn.setMessage(onOff("Auto Message", cfg.isAutoSendResetMessage()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        EditBox resetMsgField = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                Component.literal("Reset message"));
        resetMsgField.setMaxLength(100);
        resetMsgField.setValue(cfg.getResetMessageText());
        resetMsgField.setResponder(text -> {
            cfg.setResetMessageText(text);
            cfg.save();
        });
        widgets.add(resetMsgField);
        y += 30;

        return widgets;
    }

    private static SimonSaysConfig.Style nextStyle(SimonSaysConfig.Style style) {
        SimonSaysConfig.Style[] values = SimonSaysConfig.Style.values();
        return values[(style.ordinal() + 1) % values.length];
    }

    private static Component styleText(SimonSaysConfig cfg) {
        return Component.literal("Style: " + switch (cfg.getStyle()) {
            case FILLED -> "Filled";
            case OUTLINE -> "Outline";
            case FILLED_OUTLINE -> "Filled+Outline";
        });
    }

    private static Component announceKeyText(SimonSaysConfig cfg) {
        String name = cfg.getAnnounceKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getAnnounceKeyCode()).getDisplayName().getString();
        return Component.literal("Announce Key: §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingAnnounceKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        cfg.setAnnounceKeyCode(code);
        capturingAnnounceKey = false;
        cfg.save();
    }
}
