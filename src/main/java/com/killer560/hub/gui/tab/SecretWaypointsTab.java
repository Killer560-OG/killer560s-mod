package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.secretwaypoints.SecretWaypointsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import com.killer560.hub.compat.McCompat;

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
        y += 22;

        // killer560, 2026-10-05: "There should be a toggle to show princes and a toggle to show crypts."
        widgets.add(SettingsButtonWidget.builder(onOff("Show Crypts", cfg.isShowCrypts()), btn -> {
                    cfg.setShowCrypts(!cfg.isShowCrypts());
                    cfg.save();
                    btn.setMessage(onOff("Show Crypts", cfg.isShowCrypts()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Princes", cfg.isShowPrinces()), btn -> {
                    cfg.setShowPrinces(!cfg.isShowPrinces());
                    cfg.save();
                    btn.setMessage(onOff("Show Princes", cfg.isShowPrinces()));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        // Per-type colours (killer560, 2026-09-27: "You can choose the color for levers" - and there was no
        // colour picker for any of the other five either until now, same gap EtherwarpOverlayTab/MobEspTab's
        // colorButton already closed for their own features).
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        widgets.add(colorButton(contentX, y, colW, "Chest Color", cfg.getChestColor(),
                SecretWaypointsConfig.DEFAULT_CHEST_COLOR, cfg::setChestColor));
        widgets.add(colorButton(col2X, y, colW, "Item Color", cfg.getItemColor(),
                SecretWaypointsConfig.DEFAULT_ITEM_COLOR, cfg::setItemColor));
        y += 22;
        widgets.add(colorButton(contentX, y, colW, "Wither Essence Color", cfg.getWitherColor(),
                SecretWaypointsConfig.DEFAULT_WITHER_COLOR, cfg::setWitherColor));
        widgets.add(colorButton(col2X, y, colW, "Bat Color", cfg.getBatColor(),
                SecretWaypointsConfig.DEFAULT_BAT_COLOR, cfg::setBatColor));
        y += 22;
        widgets.add(colorButton(contentX, y, colW, "Redstone Key Color", cfg.getRedstoneKeyColor(),
                SecretWaypointsConfig.DEFAULT_REDSTONE_KEY_COLOR, cfg::setRedstoneKeyColor));
        widgets.add(colorButton(col2X, y, colW, "Lever Color", cfg.getLeverColor(),
                SecretWaypointsConfig.DEFAULT_LEVER_COLOR, cfg::setLeverColor));
        y += 22;
        widgets.add(colorButton(contentX, y, colW, "Crypt Color", cfg.getCryptColor(),
                SecretWaypointsConfig.DEFAULT_CRYPT_COLOR, cfg::setCryptColor));
        widgets.add(colorButton(col2X, y, colW, "Prince Color", cfg.getPrinceColor(),
                SecretWaypointsConfig.DEFAULT_PRINCE_COLOR, cfg::setPrinceColor));

        return widgets;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), name, current, defaultColor, argb -> {
                setter.accept(argb);
                SecretWaypointsConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
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
