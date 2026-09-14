package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.slotbinds.SlotBindsConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Slot Binds settings - see {@link com.killer560.hub.slotbinds.SlotBindsFeature}'s class doc for the
 *  real Odin-ported swap mechanic this is built on. Set the bind key here, then in your real inventory
 *  screen hover a slot and press it, hover a second slot and press it again to link them. */
public class SlotBindsTab extends BaseTab implements KeyCaptureTab {

    private boolean listening = false;

    public SlotBindsTab() {
        super("Slot Binds");
    }

    @Override
    public boolean isListeningForKey() {
        return listening;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        listening = false;
        SlotBindsConfig.getInstance().setBindKey(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        SlotBindsConfig.getInstance().save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SlotBindsConfig cfg = SlotBindsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Slot Binds", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(bindKeyText(), btn -> {
                    listening = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7In your real inventory (E), hover a slot and press that key,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7hover a second slot and press it again to link them. One of"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7the two must be a hotbar slot. Shift-click either to swap."),
                Minecraft.getInstance().font));
        y += 20;

        Set<Integer> shown = new LinkedHashSet<>();
        for (Map.Entry<Integer, Integer> entry : cfg.getBinds().entrySet()) {
            int a = Math.min(entry.getKey(), entry.getValue());
            int b = Math.max(entry.getKey(), entry.getValue());
            if (!shown.add(a * 1000 + b)) {
                continue;
            }
            widgets.add(new StringWidget(contentX, y, 150, 12,
                    Component.literal("Slot " + a + " <-> Slot " + b), Minecraft.getInstance().font));
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cRemove"), btn -> {
                        cfg.removeBind(a);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX + 150, y - 2, 70, 16).build());
            y += 16;
        }
        if (shown.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No binds set yet."), Minecraft.getInstance().font));
        }

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component bindKeyText() {
        int key = SlotBindsConfig.getInstance().getBindKey();
        String name = key == -1 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal("Bind Key: " + name);
    }
}
