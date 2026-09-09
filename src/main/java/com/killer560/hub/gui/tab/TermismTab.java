package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.termism.TermismMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Opens {@link TermismMenuScreen} - killer560's practice-mode request (2026-09-09): "I should be able
 *  to run /termism or be able to open it through the settings." This tab is that settings entry point;
 *  {@code /termism} (registered in Killer560ModClient) opens the same screen directly. */
public class TermismTab extends BaseTab {

    public TermismTab() {
        super("Termism");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Generates a fake terminal to practice solving - never a real one."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("No highlighting here - the whole point is reading it yourself."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Termism"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new TermismMenuScreen(client.screen));
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }
}
