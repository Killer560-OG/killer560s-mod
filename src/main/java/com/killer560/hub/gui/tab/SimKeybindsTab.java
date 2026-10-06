package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.roomsim.SimRoomCycle;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Sim route-practice keys: Next Room and Previous Room, the same as {@code /next} and {@code /back}, only
 * inside the sim. The same two binds as the sim menu's All Rooms page (one store, {@link SimRoomCycle}), listed here
 * beside the mod's other keybind tabs. killer560 (2026-10-06).
 */
public class SimKeybindsTab extends BaseTab implements KeyCaptureTab {

    /** 0 none, 1 Next Room, 2 Previous Room. */
    private int capturing;

    public SimKeybindsTab() {
        super("Sim Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        apply(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        apply(KeyUtil.codeForMouseButton(button));
    }

    private void apply(int code) {
        if (capturing == 1) {
            SimRoomCycle.setNextKey(code);
        } else if (capturing == 2) {
            SimRoomCycle.setBackKey(code);
        }
        capturing = 0;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(text("Next Room", 1, SimRoomCycle.getNextKey(), "/next"), b -> {
            capturing = 1;
            b.setMessage(Component.literal("Next Room Key: §ePress any key..."));
        }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        w.add(SettingsButtonWidget.builder(text("Previous Room", 2, SimRoomCycle.getBackKey(), "/back"), b -> {
            capturing = 2;
            b.setMessage(Component.literal("Previous Room Key: §ePress any key..."));
        }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                "§7Dungeon Sim only. Pick a set in Dungeon Sim > All Rooms (route practice)."),
                Minecraft.getInstance().font));
        return w;
    }

    private Component text(String label, int which, int code, String command) {
        if (capturing == which) {
            return Component.literal(label + " Key: §ePress any key...");
        }
        String name = code == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(code);
        return Component.literal(label + " Key: " + name + " §8" + command);
    }
}
