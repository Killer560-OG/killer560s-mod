package com.killer560.hub.gui.tab;

import com.killer560.hub.abilitytimers.AbilityTimerEntry;
import com.killer560.hub.abilitytimers.AbilityTimersConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Ability/cooldown timer settings - killer560's "tick timers from Odin/noamm" and "mask invulnerability
 *  cooldown timers" requests, one generic list-of-named-timers system (see {@link AbilityTimerEntry}'s
 *  own doc for why). Each timer: a name, a duration, a color, and a keybind that starts/restarts its
 *  countdown - press it the moment you use the real ability. Everything non-essential lives in this
 *  tab's hover tooltips rather than as in-panel text (killer560, 2026-09-20 sweep: "any questions I have
 *  that are not absolutely essential to know before turning something on should come from hovering it"). */
public class AbilityTimersTab extends BaseTab implements KeyCaptureTab {

    private String capturingId = null;

    public AbilityTimersTab() {
        super("Ability Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(masterText(), btn -> {
                    AbilityTimersConfig cfg = AbilityTimersConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(masterText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Timer"), btn -> {
                    AbilityTimersConfig.getInstance().addNew();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 28;

        for (AbilityTimerEntry e : new ArrayList<>(AbilityTimersConfig.getInstance().entries())) {
            y = buildEntryRows(widgets, e, contentX, y, contentWidth, requestRebuild);
            y += 10;
        }

        return widgets;
    }

    private int buildEntryRows(List<AbstractWidget> widgets, AbilityTimerEntry e, int contentX, int y,
                                int contentWidth, Runnable requestRebuild) {
        EditBox nameField = new EditBox(Minecraft.getInstance().font, contentX, y, 200, 18, Component.literal("Name"));
        nameField.setMaxLength(40);
        nameField.setValue(e.name);
        nameField.setResponder(text -> {
            e.name = text;
            AbilityTimersConfig.getInstance().save();
        });
        widgets.add(nameField);
        y += 20;

        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff(e.enabled), btn -> {
                    e.enabled = !e.enabled;
                    AbilityTimersConfig.getInstance().save();
                    btn.setMessage(onOff(e.enabled));
                }).bounds(col1, y, 100, 18).build());

        Component keyLabel = e.id.equals(capturingId) ? Component.literal("Press any key...") : keyText(e);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingId = e.id;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                    AbilityTimersConfig.getInstance().remove(e.id);
                    requestRebuild.run();
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(durationSlider(col1, y, col2 + 100 - col1, e));

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", e.color()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Timer Color", e.color(),
                            0xFFCC6600, argb -> {
                                e.colorHex = String.format("%06X", argb & 0xFFFFFF);
                                AbilityTimersConfig.getInstance().save();
                            }));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Test Start"), btn ->
                    e.start(System.currentTimeMillis())
                ).bounds(col1, y, 100, 18).build());
        y += 20;

        return y;
    }

    /** Drag-to-set duration (2026-09-20 tab sweep fix: the old "+5s per click, wraps at 300s" button took
     *  48 clicks to dial in a 4-minute timer). Snaps to whole seconds. */
    private static ThemedSliderButton durationSlider(int x, int y, int w, AbilityTimerEntry e) {
        int min = AbilityTimerEntry.MIN_DURATION_MS;
        int max = AbilityTimerEntry.MAX_DURATION_MS;
        double normalized = (e.durationMs - min) / (double) (max - min);
        Supplier<Component> text = () -> Component.literal("Duration: " + (e.durationMs / 1000) + "s");
        return new ThemedSliderButton(x, y, w, 18, text.get(), Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(text.get());
            }

            @Override
            protected void applyValue() {
                double rawMs = min + this.value * (max - min);
                e.durationMs = (int) (Math.round(rawMs / 1000.0) * 1000);
                AbilityTimersConfig.getInstance().save();
            }
        };
    }

    private static Component onOff(boolean value) {
        return Component.literal("Enabled: " + (value ? "§aON" : "§cOFF"));
    }

    private static Component keyText(AbilityTimerEntry e) {
        String name = e.keyCode < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(e.keyCode).getDisplayName().getString();
        return Component.literal("Key: §b" + name);
    }

    private static Component masterText() {
        return Component.literal("Ability Timers: " + (AbilityTimersConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingId != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        String id = capturingId;
        capturingId = null;
        if (id == null) {
            return;
        }
        for (AbilityTimerEntry e : AbilityTimersConfig.getInstance().entries()) {
            if (e.id.equals(id)) {
                e.keyCode = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
                AbilityTimersConfig.getInstance().save();
                break;
            }
        }
    }
}
