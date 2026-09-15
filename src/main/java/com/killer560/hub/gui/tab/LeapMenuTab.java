package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.leapmenu.LeapOrderScreen;
import com.killer560.hub.leapmessage.LeapMessageConfig;
import com.killer560.hub.spiritleap.SpiritLeapOverlayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything related to Spirit Leaping in one tab (killer560, 2026-09-14): the custom leap menu, the Leap Order
 * editor, Fast Leap and Leap Message. 2026-09-15 redo: labels only (no explanation lines), the Leap Order button
 * opens the class-first editor ({@link LeapOrderScreen}).
 */
public class LeapMenuTab extends BaseTab {

    private EditBox messageField;

    public LeapMenuTab() {
        super("Leap Menu");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        y = buildOverlaySection(widgets, contentX, y, contentWidth, requestRebuild);
        y = buildLeapOrderSection(widgets, contentX, y, contentWidth);
        y = buildFastLeapSection(widgets, contentX, y, contentWidth, requestRebuild);
        buildLeapMessageSection(widgets, contentX, y, contentWidth, requestRebuild);

        return widgets;
    }

    private int buildOverlaySection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Runnable requestRebuild) {
        widgets.add(sectionHeader(contentX, y, contentWidth, "Custom Leap Menu"));
        y += 14;

        SpiritLeapOverlayConfig cfg = SpiritLeapOverlayConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Custom Leap Menu", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (cfg.isEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Use Class Colors", cfg.isUseClassColors()), btn -> {
                        cfg.setUseClassColors(!cfg.isUseClassColors());
                        cfg.save();
                        btn.setMessage(onOff("Use Class Colors", cfg.isUseClassColors()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 24;

            double scaleNormalized = (cfg.getScale() - 0.5) / 1.5;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal(String.format(Locale.US, "Scale: %.0f%%", cfg.getScale() * 100)), scaleNormalized) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal(String.format(Locale.US, "Scale: %.0f%%", cfg.getScale() * 100)));
                }

                @Override
                protected void applyValue() {
                    cfg.setScale((float) (0.5 + this.value * 1.5));
                    cfg.save();
                }
            });
            y += 24;
        }

        return y + 8;
    }

    private int buildLeapOrderSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth) {
        widgets.add(sectionHeader(contentX, y, contentWidth, "Leap Order"));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Leap Order"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreenAndShow(new LeapOrderScreen(client.screen));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        return y + 8;
    }

    private int buildFastLeapSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Runnable requestRebuild) {
        // QUOI AutoLeap port (fast leap + auto leaps) - cheat build only. The i4 leap lives in the Sharp Shooter tab.
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        widgets.add(sectionHeader(contentX, y, contentWidth, "Fast Leap"));
        y += 14;
        y = com.killer560.hub.fastleap.FastLeapSection.build(widgets, contentX, y, contentWidth, requestRebuild);
        return y + 8;
    }

    private void buildLeapMessageSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Runnable requestRebuild) {
        widgets.add(sectionHeader(contentX, y, contentWidth, "Leap Message"));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(leapMessageEnabledText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(leapMessageEnabledText());
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
                Component.literal("§7\"Leaping To\" message (use {name} for the player's IGN):"),
                Minecraft.getInstance().font));
        y += 14;
        messageField = new EditBox(Minecraft.getInstance().font, contentX, y, 300, 20,
                Component.literal("Leaping To message"));
        messageField.setMaxLength(200);
        messageField.setValue(LeapMessageConfig.getInstance().getCustomMessage());
        messageField.setResponder(text -> {
            LeapMessageConfig cfg = LeapMessageConfig.getInstance();
            cfg.setCustomMessage(text);
            cfg.save();
        });
        widgets.add(messageField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setCustomMessage(messageField.getValue());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + 306, y, 90, 20).build());
    }

    private StringWidget sectionHeader(int contentX, int y, int contentWidth, String title) {
        return new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§6§l" + title), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component leapMessageEnabledText() {
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
