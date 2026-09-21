package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.supporters.SupportersConfig;
import com.killer560.hub.supporters.SupportersFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for killer560's item 8.5 ("mod-wide custom IGNs for supporters"). There is exactly one setting -
 * who gets a custom name/scale and what it is comes from Discord (the staff-only {@code /supporter} command
 * described in {@code SUPPORTERS-CONTRACT.md}), not this screen.
 * <p>
 * "Toggle Custom Cosmetics" ships ON by default - killer560's own explicit spec for this one feature (item
 * 8.5 literally lists it as a requirement), the deliberate exception to every other New-tab feature's
 * "ships OFF until confirmed" rule. See this feature's staging notes.
 */
public class SupportersTab extends BaseTab {

    public SupportersTab() {
        super("Supporters");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        SupportersConfig cfg = SupportersConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Cosmetics", cfg.isCustomCosmeticsEnabled()), btn -> {
                    cfg.setCustomCosmeticsEnabled(!cfg.isCustomCosmeticsEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Custom Cosmetics", cfg.isCustomCosmeticsEnabled()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        int count = SupportersFeature.supporterCount();
        String status = count == 0
                ? "§7No supporters loaded yet."
                : "§7" + count + " supporter" + (count == 1 ? "" : "s") + " loaded.";
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(status), font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
