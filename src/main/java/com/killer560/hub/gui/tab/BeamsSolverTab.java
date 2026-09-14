package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.BeamsSolverConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Creeper Beams Solver settings - see {@link com.killer560.hub.puzzlesolvers.BeamsSolverFeature}'s
 *  class doc for the real Odin-ported lantern-pair database this is built on. */
public class BeamsSolverTab extends BaseTab {

    public BeamsSolverTab() {
        super("Creeper Beams Solver");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BeamsSolverConfig cfg = BeamsSolverConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Creeper Beams Solver", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Tracer", cfg.isShowTracer()), btn -> {
                    cfg.setShowTracer(!cfg.isShowTracer());
                    cfg.save();
                    btn.setMessage(onOff("Show Tracer", cfg.isShowTracer()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Highlights real currently-connected Sea Lantern pairs with matching"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7colors, updating live as you rotate panes. Never touches anything."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
