package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeonqol.DungeonQolConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Iced Mob Boxes settings - see {@link com.killer560.hub.dungeonqol.IcedMobsFeature}. */
public class IcedMobsTab extends BaseTab {

    public IcedMobsTab() {
        super("Iced Mob Boxes");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        DungeonQolConfig cfg = DungeonQolConfig.getInstance();
        List<AbstractWidget> widgets = new ArrayList<>();
        widgets.add(onOffButton("Iced Mob Boxes", cfg::isIcedBoxesRaw, cfg::setIcedBoxes, cfg::save,
                null, contentX, contentY, contentWidth, 20));
        if (cfg.isIcedBoxesRaw() || isSearchScan()) {
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Box Color", cfg.getIcedColor()), btn -> {
                Minecraft client = Minecraft.getInstance();
                McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Box Color",
                        cfg.getIcedColor(), 0xFF55FFFF, argb -> {
                            cfg.setIcedColor(argb);
                            cfg.save();
                        }));
            }).bounds(contentX, contentY + 24, contentWidth, 18).build());
        }
        return widgets;
    }
}
