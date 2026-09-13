package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.i4sensors.I4SensorsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** I4 Sensors (diagnostic logger, not a solver) - see
 *  {@link com.killer560.hub.i4sensors.I4SensorsFeature}'s class doc for why. */
public class I4SensorsTab extends BaseTab {

    public I4SensorsTab() {
        super("I4 Sensors (Logger)");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("I4 Sensors", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(onOff("I4 Sensors", cfg.isEnabled()));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7This does NOT solve i4 - it only logs which blocks change state"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7in the real Pre4 area during a boss fight, so a real run's log can"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7be read to figure out what the device actually needs solved."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
