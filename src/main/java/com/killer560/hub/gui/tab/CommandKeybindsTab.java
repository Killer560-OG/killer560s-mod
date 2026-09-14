package com.killer560.hub.gui.tab;

import com.killer560.hub.commandkeybinds.CommandKeybindsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Command Keybinds settings - see
 *  {@link com.killer560.hub.commandkeybinds.CommandKeybindsFeature}'s class doc for the real
 *  Odin-ported menu-command shortcuts this is built on. {@code capturing}: 0 none, 1-8 one per bind. */
public class CommandKeybindsTab extends BaseTab implements KeyCaptureTab {

    private int capturing = 0;

    public CommandKeybindsTab() {
        super("Command Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();
        int key = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        switch (capturing) {
            case 1 -> cfg.setPetsKey(key);
            case 2 -> cfg.setStorageKey(key);
            case 3 -> cfg.setArmorKey(key);
            case 4 -> cfg.setEquipmentKey(key);
            case 5 -> cfg.setLoadoutsKey(key);
            case 6 -> cfg.setStatsKey(key);
            case 7 -> cfg.setDungeonHubKey(key);
            case 8 -> cfg.setPotionBagKey(key);
            default -> {
            }
        }
        capturing = 0;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Command Keybinds", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        y = addBind(widgets, contentX, y, contentWidth, "Pets", cfg.getPetsKey(), 1);
        y = addBind(widgets, contentX, y, contentWidth, "Storage", cfg.getStorageKey(), 2);
        y = addBind(widgets, contentX, y, contentWidth, "Armor Wardrobe", cfg.getArmorKey(), 3);
        y = addBind(widgets, contentX, y, contentWidth, "Equip Wardrobe", cfg.getEquipmentKey(), 4);
        y = addBind(widgets, contentX, y, contentWidth, "Loadouts", cfg.getLoadoutsKey(), 5);
        y = addBind(widgets, contentX, y, contentWidth, "Stats", cfg.getStatsKey(), 6);
        y = addBind(widgets, contentX, y, contentWidth, "Dungeon Hub", cfg.getDungeonHubKey(), 7);
        y = addBind(widgets, contentX, y, contentWidth, "Potion Bag", cfg.getPotionBagKey(), 8);
        y += 4;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Each bind sends the real command exactly as if you'd typed it."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Esc clears a bind."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private int addBind(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, String label, int key, int index) {
        widgets.add(SettingsButtonWidget.builder(keyText(label, key, index), btn -> {
                    capturing = index;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        return y + 22;
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
