package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.namechanger.NameChangerFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Nickhider (killer560, 2026-10-04: "Make a Nickhider section its own tab and that should be where the randomize
 *  names lives"). Randomize Others gives every other player a stable fake name for the session; it runs on its own,
 *  without the Cosmetics tab's Name Changer toggle (see {@link NameChangerConfig#isActive}). Purely visual. */
public class NickhiderTab extends BaseTab {

    public NickhiderTab() {
        super("Nickhider");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Randomize Others", cfg.isRandomizeOthers()), btn -> {
                    cfg.setRandomizeOthers(!cfg.isRandomizeOthers());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (cfg.isRandomizeOthers()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Every other player gets a stable fake name for this session ("
                            + NameChangerFeature.seenPlayerCount() + " seen so far)."),
                    Minecraft.getInstance().font));
        }
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
