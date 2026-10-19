package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.streamer.StreamerMode;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Streamer Mode settings (cheat build only). Turned on and off with {@code /streamer}; this tab shows the same switch and
 *  picks the option that applies while it is on. */
public class StreamerModeTab extends BaseTab {

    public StreamerModeTab() {
        super("Streamer Mode");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        boolean on = StreamerMode.active();

        widgets.add(SettingsButtonWidget.builder(onOff("Streamer Mode", on), btn -> {
                    StreamerMode.toggle();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Option: " + StreamerMode.option().label), btn -> {
                    StreamerMode.setOption(StreamerMode.option().next());
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
