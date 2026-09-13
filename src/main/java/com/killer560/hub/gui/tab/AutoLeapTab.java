package com.killer560.hub.gui.tab;

import com.killer560.hub.autoleap.AutoLeapConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Leap Out settings (cheat build only) - see {@link com.killer560.hub.autoleap.AutoLeapFeature}'s
 *  class doc for the real QUOI-ported triggers/coordinates this is built on, and which triggers were
 *  deliberately left out (need phase/packet tracking this mod doesn't have). */
public class AutoLeapTab extends BaseTab {

    public AutoLeapTab() {
        super("Auto Leap Out");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AutoLeapConfig cfg = AutoLeapConfig.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Leap Out", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        EditBox targetField = new EditBox(Minecraft.getInstance().font, contentX, y, 220, 18,
                Component.literal("Target name"));
        targetField.setMaxLength(24);
        targetField.setValue(cfg.getTargetName());
        targetField.setResponder(text -> {
            cfg.setTargetName(text);
            cfg.save();
        });
        widgets.add(targetField);
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Set to \"Mel\""), btn -> {
                    cfg.setTargetName("Mel");
                    cfg.save();
                    targetField.setValue("Mel");
                }).bounds(contentX, y, 220, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(onOff("I4 Device", cfg.isLeapOnI4Device()), btn -> {
                    cfg.setLeapOnI4Device(!cfg.isLeapOnI4Device());
                    cfg.save();
                    btn.setMessage(onOff("I4 Device", cfg.isLeapOnI4Device()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Storm Death", cfg.isLeapOnStormDeath()), btn -> {
                    cfg.setLeapOnStormDeath(!cfg.isLeapOnStormDeath());
                    cfg.save();
                    btn.setMessage(onOff("Storm Death", cfg.isLeapOnStormDeath()));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Middle", cfg.isLeapOnMiddle()), btn -> {
                    cfg.setLeapOnMiddle(!cfg.isLeapOnMiddle());
                    cfg.save();
                    btn.setMessage(onOff("Middle", cfg.isLeapOnMiddle()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Relic Pickup", cfg.isLeapOnRelic()), btn -> {
                    cfg.setLeapOnRelic(!cfg.isLeapOnRelic());
                    cfg.save();
                    btn.setMessage(onOff("Relic Pickup", cfg.isLeapOnRelic()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Pad Crushes", cfg.isLeapOnPads()), btn -> {
                    cfg.setLeapOnPads(!cfg.isLeapOnPads());
                    cfg.save();
                    btn.setMessage(onOff("Pad Crushes", cfg.isLeapOnPads()));
                }).bounds(col2, y, 100, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Switches to your Spirit Leap item and clicks the target's row -"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7same real mechanism as manually leaping. P1/Predev/PY-healer leaps"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7were left out - they need phase/packet tracking this mod lacks."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
