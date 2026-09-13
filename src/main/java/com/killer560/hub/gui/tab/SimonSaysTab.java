package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.simonsays.SimonSaysConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Simon Says diagnostic-logger settings - see {@link com.killer560.hub.simonsays.SimonSaysFeature}'s
 *  class doc for why this is a logging tool rather than an auto-solver in this first pass, and what to
 *  search the log for ("[SimonSays]") after running a real puzzle with this enabled. */
public class SimonSaysTab extends BaseTab {

    public SimonSaysTab() {
        super("Simon Says (Logger)");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("This does NOT solve Simon Says - it only logs which nearby blocks"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("change state while you're in a dungeon, so a real puzzle run's log"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("can be manually read to figure out the real sequence data."),
                Minecraft.getInstance().font));
        y += 20;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(horizontalRadiusText(cfg), btn -> {
                    int next = cfg.getHorizontalRadius() + 2;
                    cfg.setHorizontalRadius(next > 16 ? 2 : next);
                    cfg.save();
                    btn.setMessage(horizontalRadiusText(cfg));
                }).bounds(contentX, y, 260, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(verticalRadiusText(cfg), btn -> {
                    int next = cfg.getVerticalRadius() + 1;
                    cfg.setVerticalRadius(next > 10 ? 1 : next);
                    cfg.save();
                    btn.setMessage(verticalRadiusText(cfg));
                }).bounds(contentX, y, 260, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Larger boxes catch more but log more too - start small."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component horizontalRadiusText(SimonSaysConfig cfg) {
        return Component.literal("Horizontal radius: " + cfg.getHorizontalRadius() + " blocks");
    }

    private static Component verticalRadiusText(SimonSaysConfig cfg) {
        return Component.literal("Vertical radius: " + cfg.getVerticalRadius() + " blocks");
    }

    private static Component enabledText() {
        return Component.literal("Simon Says Logger: " + (SimonSaysConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
