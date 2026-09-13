package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.leapmenu.LeapMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Points to the real Leap Order menu (its own full screen - sorting, display mode, GUI scale, and
 *  the Class/Customize editors all live there since they need the whole screen's width, not a cramped
 *  mod-menu tab) rather than duplicating those controls here. Opened the same two ways: this button,
 *  or {@code /killer560 leaporder} directly from chat. */
public class LeapMenuTab extends BaseTab {

    public LeapMenuTab() {
        super("Leap Order");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Sort/display the party, assign classes, and set a custom GUI scale."),
                Minecraft.getInstance().font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Also opens directly with /killer560 leaporder."), Minecraft.getInstance().font));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Leap Order Menu"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreenAndShow(new LeapMenuScreen(client.screen));
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }
}
