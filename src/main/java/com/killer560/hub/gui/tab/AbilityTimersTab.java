package com.killer560.hub.gui.tab;

import com.killer560.hub.abilitytimers.AbilityTimerEntry;
import com.killer560.hub.abilitytimers.AbilityTimersConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Ability/cooldown timer settings - killer560's "tick timers from Odin/noamm" and "mask invulnerability
 *  cooldown timers" requests, one generic list-of-named-timers system (see {@link AbilityTimerEntry}'s
 *  own doc for why). Each timer: a name, a duration, a color, and a keybind that starts/restarts its
 *  countdown - press it the moment you use the real ability. */
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

        widgets.add(SettingsButtonWidget.builder(Component.literal("Duration: " + (e.durationMs / 1000) + "s"), btn -> {
                    int next = e.durationMs + 5000;
                    e.durationMs = next > 300_000 ? 5000 : next;
                    AbilityTimersConfig.getInstance().save();
                    btn.setMessage(Component.literal("Duration: " + (e.durationMs / 1000) + "s"));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Test Start"), btn ->
                    e.start(System.currentTimeMillis())
                ).bounds(col2, y, 100, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Press the bound key the instant you actually use this ability."),
                Minecraft.getInstance().font));
        y += 16;

        return y;
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
