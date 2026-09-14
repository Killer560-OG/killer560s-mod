package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.trajectories.TrajectoriesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Trajectories settings - see {@link com.killer560.hub.trajectories.TrajectoriesFeature}'s class doc
 *  for the real QUOI-ported projectile simulation this is built on. */
public class TrajectoriesTab extends BaseTab {

    public TrajectoriesTab() {
        super("Trajectories");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TrajectoriesConfig cfg = TrajectoriesConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Trajectories", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Bows", cfg.isShowBows()), btn -> {
                    cfg.setShowBows(!cfg.isShowBows());
                    cfg.save();
                    btn.setMessage(onOff("Bows", cfg.isShowBows()));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Ender Pearls", cfg.isShowPearls()), btn -> {
                    cfg.setShowPearls(!cfg.isShowPearls());
                    cfg.save();
                    btn.setMessage(onOff("Ender Pearls", cfg.isShowPearls()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 26;

        double rangeNormalized = (cfg.getRange() - 5) / 115.0;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                Component.literal("Simulated Ticks: " + cfg.getRange()), rangeNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Simulated Ticks: " + cfg.getRange()));
            }

            @Override
            protected void applyValue() {
                cfg.setRange((int) Math.round(5 + this.value * 115));
                cfg.save();
            }
        });
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Predicts where a real bow shot or Ender Pearl throw would land,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7using the real vanilla drag/gravity for each. Never fires anything."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
