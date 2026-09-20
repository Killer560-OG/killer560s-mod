package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.secretwaypoints.SecretWaypointsConfig;
import net.minecraft.client.gui.components.AbstractWidget;
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

        widgets.add(SettingsButtonWidget.builder(boxSizeText(cfg), btn -> {
                    SecretWaypointsConfig.BoxSize[] values = SecretWaypointsConfig.BoxSize.values();
                    cfg.setBoxSize(values[(cfg.getBoxSize().ordinal() + 1) % values.length]);
                    cfg.save();
                    btn.setMessage(boxSizeText(cfg));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.isThroughWalls()), btn -> {
                    cfg.setThroughWalls(!cfg.isThroughWalls());
                    cfg.save();
                    btn.setMessage(onOff("Through Walls", cfg.isThroughWalls()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        int min = SecretWaypointsConfig.MIN_RENDER_DISTANCE;
        int max = SecretWaypointsConfig.MAX_RENDER_DISTANCE;
        widgets.add(new ThemedSliderButton(contentX, y, 220, 18, distanceText(cfg),
                (cfg.getRenderDistance() - min) / (double) (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(distanceText(cfg));
            }

            @Override
            protected void applyValue() {
                // Snap to 8 so the label reads in round blocks.
                cfg.setRenderDistance((int) (Math.round((min + this.value * (max - min)) / 8.0) * 8));
                cfg.save();
            }
        });

        return widgets;
    }

    private static Component styleText(SecretWaypointsConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().name());
    }

    private static Component boxSizeText(SecretWaypointsConfig cfg) {
        return Component.literal("Waypoint Box: "
                + (cfg.getBoxSize() == SecretWaypointsConfig.BoxSize.FULL_BLOCK ? "Full Block" : "Hitbox Only"));
    }

    private static Component distanceText(SecretWaypointsConfig cfg) {
        return Component.literal("Render Distance: " + cfg.getRenderDistance() + " blocks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
