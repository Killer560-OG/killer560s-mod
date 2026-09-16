package com.killer560.hub.gui.tab;

import com.killer560.hub.fastleap.FastLeapSection;
import com.killer560.hub.gui.SectionHeaders;
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
 * Everything related to Spirit Leaping in one tab (killer560, 2026-09-14): the custom leap menu + Leap Order editor
 * and Leap Message. 2026-09-15 redo: labels only (no explanation lines), the Leap Order button opens the class-first
 * editor ({@link LeapOrderScreen}).
 * <p>
 * 2026-09-15, second pass ("For fast leap put that in its own complete section outside of the leap menu"): Fast Leap
 * moved out to its own cheat-only {@link FastLeapTab} ("Fast/Auto Leap"), so this tab is now all legit features and
 * every header is orange ({@link SectionHeaders}). The Leap Order editor only arranges the custom leap menu, so it
 * lives in that section instead of under a header of its own.
 * <p>
 * Same pass, "make it so the actual chat message line with set next to it takes up a full line so I can see what I am
 * typing": the Leaping To message {@link EditBox} no longer shares its row with the Set button - it gets a full-width
 * line of its own, with a compact Set (and the {@code {name}} reminder) on the line below it.
 */
public class LeapMenuTab extends BaseTab {

    private static final int ROW = FastLeapSection.ROW;
    private static final int GAP = FastLeapSection.GAP;
    private static final int HEADER_H = 14;
    private static final int SECTION_GAP = 10;
    private static final int SET_W = 56;

    public LeapMenuTab() {
        super("Leap Menu");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        y = buildCustomMenuSection(widgets, contentX, y, contentWidth, requestRebuild);
        buildLeapMessageSection(widgets, contentX, y, contentWidth, requestRebuild);

        return widgets;
    }

    private int buildCustomMenuSection(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        widgets.add(sectionHeader(x, y, width, "Custom Leap Menu", false));
        y += HEADER_H;

        int half = (width - GAP) / 2;
        int rightX = x + half + GAP;
        int rightW = Math.max(1, width - half - GAP);

        SpiritLeapOverlayConfig cfg = SpiritLeapOverlayConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Custom Leap Menu", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y, half, ROW).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Leap Order"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreenAndShow(new LeapOrderScreen(client.screen));
                }).bounds(rightX, y, rightW, ROW).build());
        y += ROW + GAP;

        if (cfg.isEnabled()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Use Class Colors", cfg.isUseClassColors()), btn -> {
                        cfg.setUseClassColors(!cfg.isUseClassColors());
                        cfg.save();
                        btn.setMessage(onOff("Use Class Colors", cfg.isUseClassColors()));
                    }).bounds(x, y, half, ROW).build());

            float span = SpiritLeapOverlayConfig.MAX_SCALE - SpiritLeapOverlayConfig.MIN_SCALE;
            double scaleNormalized = (cfg.getScale() - SpiritLeapOverlayConfig.MIN_SCALE) / span;
            widgets.add(new ThemedSliderButton(rightX, y, rightW, ROW, scaleText(cfg), scaleNormalized) {
                @Override
                protected void updateMessage() {
                    setMessage(scaleText(cfg));
                }

                @Override
                protected void applyValue() {
                    // 50%..400% in 5% steps
                    float raw = SpiritLeapOverlayConfig.MIN_SCALE + (float) this.value * span;
                    cfg.setScale(Math.round(raw * 20.0f) / 20.0f);
                    cfg.save();
                }
            });
            y += ROW + GAP;
        }

        return y + SECTION_GAP;
    }

    private void buildLeapMessageSection(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        widgets.add(sectionHeader(x, y, width, "Leap Message", false));
        y += HEADER_H;

        int half = (width - GAP) / 2;
        int rightX = x + half + GAP;
        int rightW = Math.max(1, width - half - GAP);
        widgets.add(SettingsButtonWidget.builder(leapMessageEnabledText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(leapMessageEnabledText());
                }).bounds(x, y, half, ROW).build());
        widgets.add(SettingsButtonWidget.builder(cringeText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setCringeEnabled(!cfg.isCringeEnabled());
                    cfg.save();
                    btn.setMessage(cringeText());
                }).bounds(rightX, y, rightW, ROW).build());
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.builder(leapingToText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setLeapingToEnabled(!cfg.isLeapingToEnabled());
                    cfg.save();
                    btn.setMessage(leapingToText());
                }).bounds(x, y, Math.max(1, width), ROW).build());
        y += ROW + GAP;

        // Set sits on the right of the field itself (2026-09-16, killer560: "put the set button to the right of the
        // box i type in to set the leap message"); the {name} reminder keeps its own line underneath, so the field
        // is still nearly full width and you can read everything you type.
        // message text "Leaping To message" is also the SettingTooltips key - keep it
        int msgW = Math.max(1, width - SET_W - GAP);
        EditBox messageField = new EditBox(Minecraft.getInstance().font, x, y, msgW, ROW,
                Component.literal("Leaping To message"));
        messageField.setMaxLength(200);
        messageField.setValue(LeapMessageConfig.getInstance().getCustomMessage());
        messageField.setHint(Component.literal("§8{name} = player's IGN"));
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
                }).bounds(x + msgW + GAP, y, SET_W, ROW).build());
        y += ROW + GAP;

        widgets.add(FastLeapSection.label(x, y, Math.max(1, width),
                "§7{name} becomes the IGN you leapt to"));
    }

    private static StringWidget sectionHeader(int x, int y, int width, String title, boolean cheatOnly) {
        return new StringWidget(x, y, width, 12, SectionHeaders.header(title, cheatOnly), Minecraft.getInstance().font);
    }

    private static Component scaleText(SpiritLeapOverlayConfig cfg) {
        return Component.literal(String.format(Locale.US, "Scale: %.0f%%", cfg.getScale() * 100));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component leapMessageEnabledText() {
        return onOff("Leap Message Enabled", LeapMessageConfig.getInstance().isEnabled());
    }

    private static Component cringeText() {
        return onOff("Cringe Message", LeapMessageConfig.getInstance().isCringeEnabled());
    }

    private static Component leapingToText() {
        return onOff("\"Leaping To\" Message", LeapMessageConfig.getInstance().isLeapingToEnabled());
    }
}
