package com.killer560.hub.gui.tab;

import com.killer560.hub.gfs.LegitGfs;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Legit GFS keybinds: one row per item. Click a row, then press the key; Escape clears it. */
public class LegitGfsTab extends BaseTab implements KeyCaptureTab {

    private LegitGfs.Item capturing = null;

    public LegitGfsTab() {
        super("Legit GFS");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturing != null) {
            LegitGfs.setKey(capturing, keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        }
        capturing = null;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        if (capturing != null) {
            LegitGfs.setKey(capturing, KeyUtil.codeForMouseButton(button));
        }
        capturing = null;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        for (LegitGfs.Item item : LegitGfs.Item.values()) {
            LegitGfs.Item row = item;
            int key = LegitGfs.keyFor(item);
            Component label = capturing == row
                    ? Component.literal(row.label + ": Press a key...")
                    : Component.literal(row.label + ": " + (key == KeyUtil.NONE ? "§7Not set" : "§b" + KeyUtil.bindDisplayName(key)));
            widgets.add(SettingsButtonWidget.builder(label, btn -> {
                        capturing = row;
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Type /k gfs pearl, boom, leap or jerry"), btn -> {
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }
}
