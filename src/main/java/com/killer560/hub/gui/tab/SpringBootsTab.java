package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Spring Boots Overlay settings - split out of {@code DungeonAlertsTab} 2026-09-21 into its own tab per
 *  killer560's menu-structure request ("Terracotta, Spring Boots and Class Colors each get their own
 *  section"). Still backed by {@link DungeonAlertsConfig} - same JSON keys, unchanged - so this is a
 *  GUI-only move. See {@code com.killer560.hub.dungeonalerts.SpringBootsOverlay} for the real HUD/height
 *  logic. */
public class SpringBootsTab extends BaseTab {

    public SpringBootsTab() {
        super("Spring Boots");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int y = contentY;

        w.add(SettingsButtonWidget.builder(onOff("Spring Boots Overlay", cfg.springBootsEnabled), btn -> {
                    cfg.springBootsEnabled = !cfg.springBootsEnabled;
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.springBootsEnabled) {
            w.add(SettingsButtonWidget.builder(onOff("Height Box", cfg.springBootsBox), btn -> {
                        cfg.springBootsBox = !cfg.springBootsBox;
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            if (cfg.springBootsBox) {
                // Colour and style of the height box (killer560, 2026-10-11).
                Minecraft mc = Minecraft.getInstance();
                w.add(SettingsButtonWidget.builder(ColorSwatch.label("Height Box Colour", cfg.springBootsColor), btn ->
                        McCompat.setScreen(mc, new ColorPickerScreen(McCompat.screen(mc), "Height Box Colour",
                                cfg.springBootsColor, DungeonAlertsConfig.DEFAULT_SPRING_BOOTS_COLOR, argb -> {
                                    cfg.springBootsColor = argb;
                                    cfg.save();
                                }))).bounds(contentX, y, contentWidth, 18).build());
                y += 22;
                w.add(SettingsButtonWidget.cycling(styleLabel(cfg), (btn, dir) -> {
                            cfg.springBootsBoxStyle = SettingsButtonWidget.step(cfg.springBootsBoxStyle, dir);
                            cfg.save();
                            btn.setMessage(styleLabel(cfg));
                        }).bounds(contentX, y, contentWidth, 18).build());
            }
        }

        return w;
    }

    private static Component styleLabel(DungeonAlertsConfig cfg) {
        return Component.literal("Height Box Style: §b" + cfg.springBootsBoxStyle.label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
