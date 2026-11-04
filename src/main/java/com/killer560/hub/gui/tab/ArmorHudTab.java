package com.killer560.hub.gui.tab;

import com.killer560.hub.armorhud.ArmorHudConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Armor HUD settings - see {@link com.killer560.hub.armorhud.ArmorHudFeature}. */
public class ArmorHudTab extends BaseTab {

    public ArmorHudTab() {
        super("Armor HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ArmorHudConfig cfg = ArmorHudConfig.getInstance();

        if (masterClosed(widgets, "Armor HUD", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 28;

        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(onOffButton("Show Equipment", cfg::isShowEquipment, cfg::setShowEquipment, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(SettingsButtonWidget.cycling(layoutText(cfg), (btn, dir) -> {
                    cfg.setLayout(cfg.getLayout().step(dir));
                    cfg.save();
                    btn.setMessage(layoutText(cfg));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(onOffButton("Durability Bars", cfg::isShowDurability, cfg::setShowDurability, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Background", cfg::isBackground, cfg::setBackground, cfg::save,
                null, colBX, y, colW, 18));
        return widgets;
    }

    private static Component layoutText(ArmorHudConfig cfg) {
        return Component.literal("Layout: " + cfg.getLayout().label);
    }
}
