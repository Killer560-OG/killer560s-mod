package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.secretwaypoints.SecretWaypointsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Secret Waypoints settings - see
 *  {@link com.killer560.hub.secretwaypoints.SecretWaypointsFeature}'s class doc for the real room
 *  database this is built on (same one Live Map uses). */
public class SecretWaypointsTab extends BaseTab {

    public SecretWaypointsTab() {
        super("Secret Waypoints");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Secret Waypoints", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    SecretWaypointsConfig.Style[] values = SecretWaypointsConfig.Style.values();
                    cfg.setStyle(values[(cfg.getStyle().ordinal() + 1) % values.length]);
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Mimic Detection", cfg.isMimicDetection()), btn -> {
                    cfg.setMimicDetection(!cfg.isMimicDetection());
                    cfg.save();
                    btn.setMessage(onOff("Mimic Detection", cfg.isMimicDetection()));
                }).bounds(contentX, y, 220, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Real per-room secret positions from the room database (chests,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7items, wither skulls, bats, redstone keys) - color-coded, shown"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7once a room's identity AND rotation are both detected. Mimic"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Detection flags an extra trapped chest beyond what's expected."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component styleText(SecretWaypointsConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().name());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
