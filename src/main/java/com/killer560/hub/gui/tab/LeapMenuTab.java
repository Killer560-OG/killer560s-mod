package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.leapmenu.LeapMenuScreen;
import com.killer560.hub.leapmessage.LeapMessageConfig;
import com.killer560.hub.posmsg.PosmsgConfig;
import com.killer560.hub.posmsg.PosmsgEntry;
import com.killer560.hub.posmsg.PosmsgFeature;
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
 * Everything related to Spirit Leaping, consolidated into one tab per killer560's own request
 * (2026-09-14): "Everything related to spirit leaps should be under one setting called leap menu. This
 * includes the leap messages, the fast leap the leap order all of it." Combines what used to be 4
 * separate tabs, each kept exactly as it worked before (same configs, same features, same widgets) -
 * only the GUI grouping changed:
 * <ul>
 *   <li><b>Custom Leap Menu overlay</b> - was {@code SpiritLeapOverlayTab}; see
 *   {@link com.killer560.hub.spiritleap.SpiritLeapOverlayFeature}.</li>
 *   <li><b>Leap Order</b> - a pointer to the real full-screen Leap Order menu (unchanged; that screen
 *   still owns sorting/display mode/GUI scale/Class-Customize editors since they need the whole
 *   screen's width, not a cramped mod-menu tab).</li>
 *   <li><b>Fast Leap</b> - was {@code FastLeapTab}; one-click Posmsg-backed quick-travel buttons.</li>
 *   <li><b>Leap Message</b> - was {@code LeapMessageTab} (previously lived under the Dungeon tab, not
 *   New - moved here so every leap-related setting is genuinely in one place); see
 *   {@link com.killer560.hub.leapmessage.LeapMessageFeature}.</li>
 * </ul>
 * Stays in the New tab for now per killer560's own explicit instruction ("for now obviously keep it in
 * the new tab") - the plan is for this whole consolidated tab to move into the Dungeon tab once
 * confirmed working, same as every other New-tab feature eventually does.
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
        y = buildFastLeapSection(widgets, contentX, y, contentWidth);
        buildLeapMessageSection(widgets, contentX, y, contentWidth, requestRebuild);

        return widgets;
    }

    private int buildOverlaySection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Runnable requestRebuild) {
        widgets.add(sectionHeader(contentX, y, contentWidth, "Custom Leap Menu Overlay"));
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
            y += 26;

            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Draws 4 big clickable boxes over the real Spirit Leap GUI, one"),
                    Minecraft.getInstance().font));
            y += 12;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7per real teammate - click anywhere in a quarter of the screen"),
                    Minecraft.getInstance().font));
            y += 12;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7to leap to that quadrant's player instead of the tiny real slot."),
                    Minecraft.getInstance().font));
            y += 12;
        }

        return y + 12;
    }

    private int buildLeapOrderSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth) {
        widgets.add(sectionHeader(contentX, y, contentWidth, "Leap Order"));
        y += 14;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Sort/display the party, assign classes, and set a custom GUI scale."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Also opens directly with /killer560 leaporder."), Minecraft.getInstance().font));
        y += 18;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Leap Order Menu"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreenAndShow(new LeapMenuScreen(client.screen));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        return y + 12;
    }

    private int buildFastLeapSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth) {
        widgets.add(sectionHeader(contentX, y, contentWidth, "Fast Leap"));
        y += 14;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7One click sends that waypoint to Party Chat for everyone's Posmsg HUD."),
                Minecraft.getInstance().font));
        y += 18;

        List<PosmsgEntry> ready = new ArrayList<>();
        List<PosmsgEntry> notReady = new ArrayList<>();
        for (PosmsgEntry e : PosmsgConfig.getInstance().entries()) {
            (e.configured ? ready : notReady).add(e);
        }

        for (PosmsgEntry e : ready) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("Leap: " + e.name), btn ->
                        PosmsgFeature.send(e)
                    ).bounds(contentX, y, 220, 20).build());
            y += 24;
        }

        if (!notReady.isEmpty()) {
            y += 6;
            StringBuilder names = new StringBuilder();
            for (PosmsgEntry e : notReady) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(e.name);
            }
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Not set up yet (see Posmsg tab): " + names), Minecraft.getInstance().font));
            y += 12;
        }

        return y + 12;
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
        widgets.add(messageField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setCustomMessage(messageField.getValue());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + 306, y, 90, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Sends to Party Chat every time you Spirit Leap. Both"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7message types can be on at once - both will send."),
                Minecraft.getInstance().font));
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
