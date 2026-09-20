package com.killer560.hub.gui.tab;

import com.killer560.hub.abilitykeybinds.AbilityKeybindsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Ability Keybinds settings - see {@link com.killer560.hub.abilitykeybinds.AbilityKeybindsFeature}'s
 *  class doc for the real Noamm-ported ability-trigger this is built on. {@code capturing}: 0 none,
 *  1 ability key, 2 ultimate key. */
public class AbilityKeybindsTab extends BaseTab implements KeyCaptureTab {

    private int capturing = 0;

    public AbilityKeybindsTab() {
        super("Ability Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
    }

    /** Lets {@code ModScreen} route the next mouse press here instead of to the widget under the cursor.
     *  Not yet an interface method - the {@code KeyCaptureTab}/{@code ModScreen} patch is in this wave's
     *  staging notes; it becomes an override the moment that lands. */
    public boolean supportsMouseCapture() {
        return true;
    }

    /** Mouse half of the capture (killer560: "make all of the keybind things compatible with mouse buttons
     *  and middle mouse buttons"). Not yet an interface method - {@code ModScreen}'s routing patch is in this
     *  wave's staging notes; until it lands this simply never gets called. */
    public void onMouseCaptured(int button) {
        applyCapture(AbilityKeybindsConfig.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();
        if (capturing == 1) {
            cfg.setAbilityKeyCode(code);
        } else if (capturing == 2) {
            cfg.setUltimateKeyCode(code);
        }
        capturing = 0;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Ability Keybinds", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(keyText("Ability Key", cfg.getAbilityKeyCode(), 1), btn -> {
                    capturing = 1;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(keyText("Ultimate Key", cfg.getUltimateKeyCode(), 2), btn -> {
                    capturing = 2;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());

        // The three explanatory lines that used to sit here are hover tooltips now (2026-09-20 mod-wide rule).
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private Component keyText(String label, int key, int index) {
        if (capturing == index) {
            return Component.literal("Press any key...");
        }
        return Component.literal(label + ": " + CommandKeybindsTab.bindName(key));
    }
}
