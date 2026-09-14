package com.killer560.hub.gui.tab;

import com.killer560.hub.abilitykeybinds.AbilityKeybindsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
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
        AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();
        int key = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        if (capturing == 1) {
            cfg.setAbilityKeyCode(key);
        } else if (capturing == 2) {
            cfg.setUltimateKeyCode(key);
        }
        capturing = 0;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AbilityKeybindsConfig cfg = AbilityKeybindsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Ability Keybinds", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
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
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Sends the exact real vanilla drop-item/drop-stack action Hypixel"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7already reads as Ability/Ultimate - only fires on your own real"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7key press, only while in a real dungeon. Esc clears a bind."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private Component keyText(String label, int key, int index) {
        if (capturing == index) {
            return Component.literal("Press any key...");
        }
        String name = key == -1 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(label + ": " + name);
    }
}
