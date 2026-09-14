package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.quiver.QuiverDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Quiver Display settings - see {@link com.killer560.hub.quiver.QuiverDisplayFeature}'s class doc for
 *  the real Odin-ported lore-reading this is built on. */
public class QuiverDisplayTab extends BaseTab {

    public QuiverDisplayTab() {
        super("Quiver Display");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        QuiverDisplayConfig cfg = QuiverDisplayConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Quiver Display", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Item Name", cfg.isShowName()), btn -> {
                    cfg.setShowName(!cfg.isShowName());
                    cfg.save();
                    btn.setMessage(onOff("Show Item Name", cfg.isShowName()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Shows the real \"Arrows Remaining\" count from any real arrow/"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7quiver item anywhere in your inventory or off hand."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
