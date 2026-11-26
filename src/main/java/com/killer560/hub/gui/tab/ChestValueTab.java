package com.killer560.hub.gui.tab;

import com.killer560.hub.chestvalue.ChestValue;
import com.killer560.hub.chestvalue.ChestValueConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chest Value settings - see {@link ChestValue}: the switch, sort, how many lines, and which containers it covers. */
public class ChestValueTab extends BaseTab {

    public ChestValueTab() {
        super("Chest Value");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ChestValueConfig cfg = ChestValueConfig.getInstance();

        if (masterClosed(widgets, "Chest Value", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 26;

        widgets.add(SettingsButtonWidget.cycling(Component.literal("Sort: " + cfg.getSort().label), (btn, dir) -> {
                    cfg.setSort(SettingsButtonWidget.step(cfg.getSort(), dir));
                    cfg.save();
                    btn.setMessage(Component.literal("Sort: " + cfg.getSort().label));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        int span = ChestValueConfig.MAX_LINES - ChestValueConfig.MIN_LINES + 1;
        widgets.add(SettingsButtonWidget.cycling(Component.literal("Max Lines: " + cfg.getMaxLines()), (btn, dir) -> {
                    cfg.setMaxLines(ChestValueConfig.MIN_LINES + SettingsButtonWidget.step(
                            cfg.getMaxLines() - ChestValueConfig.MIN_LINES, dir, span));
                    cfg.save();
                    btn.setMessage(Component.literal("Max Lines: " + cfg.getMaxLines()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(onOffButton("Chests", cfg::isChests, cfg::setChests, cfg::save, null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Ender Chest", cfg::isEnderChest, cfg::setEnderChest, cfg::save, null, contentX, y,
                contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Backpacks", cfg::isBackpacks, cfg::setBackpacks, cfg::save, null, contentX, y,
                contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Minions", cfg::isMinions, cfg::setMinions, cfg::save, null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Other Containers", cfg::isOther, cfg::setOther, cfg::save, null, contentX, y,
                contentWidth, 18));
        return widgets;
    }
}
