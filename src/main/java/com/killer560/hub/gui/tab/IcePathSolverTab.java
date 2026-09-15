package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.IcePathSolverConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Ice Path (silverfish) Solver settings - see {@link com.killer560.hub.puzzlesolvers.IcePathSolverFeature}. */
public class IcePathSolverTab extends BaseTab {

    public IcePathSolverTab() {
        super("Ice Path Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        IcePathSolverConfig cfg = IcePathSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Ice Path Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Outline Next Stop", cfg.isShowNextBox()), btn -> {
                    cfg.setShowNextBox(!cfg.isShowNextBox());
                    cfg.save();
                    btn.setMessage(onOff("Outline Next Stop", cfg.isShowNextBox()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Draws the shortest silverfish push path to the exit (green) and"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7outlines its next stop (red). Never hits the silverfish."),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
