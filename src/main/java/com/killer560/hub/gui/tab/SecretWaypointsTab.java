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
 *  database this is built on (same one Live Map uses). Moved from a top-level {@code NewTab} entry into
 *  the "Secrets" folder 2026-09-21 (see {@link SecretsTab}) per killer560's menu-structure request - no
 *  behaviour change, just a different accordion home. */
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

        widgets.add(SettingsButtonWidget.builder(onOff("Show Names", cfg.isShowNames()), btn -> {
                    cfg.setShowNames(!cfg.isShowNames());
                    cfg.save();
                    btn.setMessage(onOff("Show Names", cfg.isShowNames()));
                }).bounds(contentX, y, 220, 18).build());

        return widgets;
    }

    private static Component styleText(SecretWaypointsConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().name());
    }

    private static Component boxSizeText(SecretWaypointsConfig cfg) {
        return Component.literal("Waypoint Box: "
                + (cfg.getBoxSize() == SecretWaypointsConfig.BoxSize.FULL_BLOCK ? "Full Block" : "Hitbox Only"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
