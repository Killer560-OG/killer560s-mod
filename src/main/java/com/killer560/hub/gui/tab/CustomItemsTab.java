package com.killer560.hub.gui.tab;

import com.killer560.hub.armourdye.ArmourDye;
import com.killer560.hub.armourdye.ArmourDyeConfig;
import com.killer560.hub.armourdye.CustomItemsScreen;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom Items (Armour Recolour until 2026-10-08) in the Cosmetics category. killer560: "it should just have the
 * toggle, a reset all button, and a way to open the menu" - everything else (picking items, colours, skins, removing
 * them) happens in {@link CustomItemsScreen}, also {@code /customitems}. Reset All asks for a second press.
 */
public class CustomItemsTab extends BaseTab {

    private static final long CONFIRM_MS = 3000L;

    private long resetArmedAt;

    public CustomItemsTab() {
        super("Custom Items");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Items", cfg.isEnabledRaw()), btn -> {
            cfg.setEnabled(!cfg.isEnabledRaw());
            if (cfg.isEnabledRaw()) {
                // Turning it back on clears the session kill-switch, so a one-off render failure isn't permanent.
                ArmourDye.resetFailures();
            }
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        int gap = 8;
        int half = (contentWidth - gap) / 2;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Custom Items"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.setScreenAndShow(new CustomItemsScreen(McCompat.screen(mc)));
            }
        }).bounds(contentX, y, half, 20).build());
        boolean armed = System.currentTimeMillis() - resetArmedAt < CONFIRM_MS;
        int count = cfg.entryCount();
        widgets.add(SettingsButtonWidget.builder(Component.literal(armed
                ? "§cReset All: click again (" + count + ")" : "Reset All"), btn -> {
            if (System.currentTimeMillis() - resetArmedAt < CONFIRM_MS) {
                cfg.clear();
                cfg.save();
                resetArmedAt = 0L;
            } else {
                resetArmedAt = System.currentTimeMillis();
            }
            requestRebuild.run();
        }).bounds(contentX + half + gap, y, contentWidth - half - gap, 20).build());
        y += 24;

        if (ArmourDye.hasFailed()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                    "§cDisabled after repeated render errors - toggle it off and on to retry."), Minecraft.getInstance().font));
        }
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
