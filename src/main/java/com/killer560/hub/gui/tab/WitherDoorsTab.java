package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.witherdoors.WitherDoorsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Wither Doors settings - see {@link com.killer560.hub.witherdoors.WitherDoorsFeature}'s class doc for
 *  how the closest-door search, the Wither/Blood distinction and the key-held colour switch all work. */
public class WitherDoorsTab extends BaseTab {

    public WitherDoorsTab() {
        super("Wither Doors");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        WitherDoorsConfig cfg = WitherDoorsConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Wither Doors", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Wither Door Color", cfg.getWitherLockedColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Wither Door Color",
                            cfg.getWitherLockedColor(), WitherDoorsConfig.DEFAULT_LOCKED_COLOR, argb -> {
                        cfg.setWitherLockedColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Wither Door (Key) Color", cfg.getWitherReadyColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Wither Door (Key) Color",
                            cfg.getWitherReadyColor(), WitherDoorsConfig.DEFAULT_READY_COLOR, argb -> {
                        cfg.setWitherReadyColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        int min = WitherDoorsConfig.MIN_RENDER_DISTANCE;
        int max = WitherDoorsConfig.MAX_RENDER_DISTANCE;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, distanceText(cfg),
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
        y += 26;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - ESP", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Show All Doors", cfg.isShowAllDoorsRaw()), btn -> {
                    cfg.setShowAllDoors(!cfg.isShowAllDoorsRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isShowAllDoorsRaw()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Blood Door Color", cfg.getBloodLockedColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Blood Door Color",
                                cfg.getBloodLockedColor(), WitherDoorsConfig.DEFAULT_LOCKED_COLOR, argb -> {
                            cfg.setBloodLockedColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2aX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Blood Door (Key) Color", cfg.getBloodReadyColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Blood Door (Key) Color",
                                cfg.getBloodReadyColor(), WitherDoorsConfig.DEFAULT_READY_COLOR, argb -> {
                            cfg.setBloodReadyColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 22;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.isThroughWallsRaw()), btn -> {
                    cfg.setThroughWalls(!cfg.isThroughWallsRaw());
                    cfg.save();
                    btn.setMessage(onOff("Through Walls", cfg.isThroughWallsRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component distanceText(WitherDoorsConfig cfg) {
        return Component.literal("Render Distance: " + cfg.getRenderDistance() + " blocks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
