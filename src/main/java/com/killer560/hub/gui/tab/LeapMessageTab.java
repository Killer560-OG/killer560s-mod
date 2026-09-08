package com.killer560.hub.gui.tab;

import com.killer560.hub.leapmessage.LeapMessageConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Leap Message settings: a master toggle plus one toggle per message type, plus a typeable custom
 *  message for "Leaping To" - see {@link com.killer560.hub.leapmessage.LeapMessageFeature}. */
public class LeapMessageTab extends BaseTab {

    private EditBox messageField;

    public LeapMessageTab() {
        super("Leap Message");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(cringeText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setCringeEnabled(!cfg.isCringeEnabled());
                    cfg.save();
                    btn.setMessage(cringeText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(leapingToText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setLeapingToEnabled(!cfg.isLeapingToEnabled());
                    cfg.save();
                    btn.setMessage(leapingToText());
                }).bounds(contentX, y, 220, 20).build());
        y += 28;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("\"Leaping To\" message (use {name} for the player's IGN):"),
                Minecraft.getInstance().font));
        y += 14;
        messageField = new EditBox(Minecraft.getInstance().font, contentX, y, 300, 20,
                Component.literal("Leaping To message"));
        messageField.setMaxLength(200);
        messageField.setValue(LeapMessageConfig.getInstance().getCustomMessage());
        widgets.add(messageField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setCustomMessage(messageField.getValue());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + 306, y, 90, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Sends to Party Chat every time you Spirit Leap. Both"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("message types can be on at once - both will send."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Leap Message Enabled: "
                + (LeapMessageConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component cringeText() {
        return Component.literal("Cringe Message: "
                + (LeapMessageConfig.getInstance().isCringeEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component leapingToText() {
        return Component.literal("\"Leaping To\" Message: "
                + (LeapMessageConfig.getInstance().isLeapingToEnabled() ? "§aON" : "§cOFF"));
    }
}
