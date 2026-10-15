package com.killer560.hub.gui;

import java.util.List;

/**
 * What a feature card's (or drawer's) switch stands for: one master row, or several independent ones.
 *
 * <p>A tab whose settings are really two or more features side by side (Dungeon Queue: Auto Requeue and Party Finder
 * Overlay) must not have the first row speak for the whole tab (killer560, 2026-10-14: "if i turn off auto reque i may
 * still want that portion of the mod on for party finder overlay. But if i have both of those larger settings off then
 * it needs to be off"). Such a tab lists each feature's own ON/OFF row in {@link
 * com.killer560.hub.gui.tab.BaseTab#masterToggles}, and the switch is ON while ANY of them is on. Pressing it with
 * something on turns every ON one off; pressing it with all off turns every one on. Each row also keeps working by
 * itself inside the tab.
 */
public final class MasterSwitch {

    private final List<SettingsButtonWidget> parts;

    private MasterSwitch(List<SettingsButtonWidget> parts) {
        this.parts = parts;
    }

    /** The switch for {@code rows} (one or more), or null when there are none. */
    public static MasterSwitch of(List<SettingsButtonWidget> rows) {
        return rows == null || rows.isEmpty() ? null : new MasterSwitch(List.copyOf(rows));
    }

    /** The independent rows behind the switch. */
    public List<SettingsButtonWidget> parts() {
        return parts;
    }

    /** ON while any of the rows is on: the tab is off only when all of them are. */
    public boolean isOn() {
        for (SettingsButtonWidget p : parts) {
            if (p.isOn()) {
                return true;
            }
        }
        return false;
    }

    /** Exactly a click on the single row; for several, all on turn off, or all off turn on. */
    public void press() {
        if (parts.size() == 1) {
            parts.get(0).press();
            return;
        }
        boolean anyOn = isOn();
        // Decide from the state before the first press: a press may rebuild or change what the others read.
        boolean[] on = new boolean[parts.size()];
        for (int i = 0; i < on.length; i++) {
            on[i] = parts.get(i).isOn();
        }
        for (int i = 0; i < on.length; i++) {
            if (anyOn ? on[i] : !on[i]) {
                parts.get(i).press();
            }
        }
    }
}
