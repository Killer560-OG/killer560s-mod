package com.killer560.hub.gui.tab;

import com.killer560.hub.diorite.DioriteGlassConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "I Hate Diorite" settings - see {@link com.killer560.hub.diorite.DioriteGlassFeature}'s class doc for
 *  the real Noamm-ported Storm-pillar glass swap this is built on, and why no legit variant exists. */
public class DioriteGlassTab extends BaseTab {

    public DioriteGlassTab() {
        super("I Hate Diorite");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DioriteGlassConfig cfg = DioriteGlassConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("I Hate Diorite", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Swaps Storm's real diorite pillars to see-through stained glass"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7client-side only - same collision, just lets you see through them."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
