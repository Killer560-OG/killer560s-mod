package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.IceFillSolverConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Ice Fill Solver settings - see {@link com.killer560.hub.puzzlesolvers.IceFillSolverFeature}'s class
 *  doc for the real Odin-ported path database this is built on. */
public class IceFillSolverTab extends BaseTab {

    public IceFillSolverTab() {
        super("Ice Fill Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        IceFillSolverConfig cfg = IceFillSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Ice Fill Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Alternate Path", cfg.isOptimizedPath()), btn -> {
                    cfg.setOptimizedPath(!cfg.isOptimizedPath());
                    cfg.save();
                    btn.setMessage(onOff("Alternate Path", cfg.isOptimizedPath()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Draws the real safe walking path across all 3 real Ice Fill floors"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7once each floor's ice layout is identified. Never walks for you."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
