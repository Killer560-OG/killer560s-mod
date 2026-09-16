package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
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
 * Everything related to Spirit Leaping in one tab (killer560, 2026-09-14): the custom leap menu + Leap Order editor,
 * Fast Leap and Leap Message. 2026-09-15 redo: labels only (no explanation lines), the Leap Order button opens the
 * class-first editor ({@link LeapOrderScreen}).
 * <p>
 * 2026-09-15 consolidation ("try to consolidate the menu and maybe make it look a bit better"): three sections on one
 * grid - every control is {@link FastLeapSection#ROW} high with {@link FastLeapSection#GAP} between controls, related
 * toggles share a row (halves/thirds on the same columns), the "Leaping To" text sits beside its toggle, labels keep
 * their SettingTooltips keys, and each header appears once
 * (legit sections orange, the cheat-only Fast Leap section red - {@link SectionHeaders}). The Leap Order editor only
 * arranges the custom leap menu, so it lives in that section instead of under a header of its own.
 */
public class LeapMenuTab extends BaseTab {

    private static final int ROW = FastLeapSection.ROW;
    private static final int GAP = FastLeapSection.GAP;
    private static final int HEADER_H = 14;
    private static final int SECTION_GAP = 10;
    private static final int SET_W = 44;

    public LeapMenuTab() {
        super("Leap Menu");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        y = buildCustomMenuSection(widgets, contentX, y, contentWidth, requestRebuild);
        y = buildFastLeapSection(widgets, contentX, y, contentWidth, requestRebuild);
        buildLeapMessageSection(widgets, contentX, y, contentWidth, requestRebuild);

        return widgets;
    }

    private int buildCustomMenuSection(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        widgets.add(sectionHeader(x, y, width, "Custom Leap Menu", false));
        y += HEADER_H;

        int half = (width - GAP) / 2;
        int rightX = x + half + GAP;
        int rightW = width - half - GAP;

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

    private int buildFastLeapSection(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        // QUOI AutoLeap port (fast leap + auto leaps) - cheat build only, so a red header. The i4 leap lives in the
        // Sharp Shooter tab. FastLeapSection draws no header of its own (it used to, which showed "Fast Leap" twice).
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return y;
        }
        widgets.add(sectionHeader(x, y, width, "Fast Leap", true));
        y += HEADER_H;
        y = FastLeapSection.build(widgets, x, y, width, requestRebuild);
        return y + SECTION_GAP;
    }

    private void buildLeapMessageSection(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild) {
        widgets.add(sectionHeader(x, y, width, "Leap Message", false));
        y += HEADER_H;

        int half = (width - GAP) / 2;
        int rightX = x + half + GAP;
        int rightW = width - half - GAP;
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

        // the "Leaping To" toggle sits next to its own text: toggle | field + Set, one ROW-high line
        widgets.add(SettingsButtonWidget.builder(leapingToText(), btn -> {
                    LeapMessageConfig cfg = LeapMessageConfig.getInstance();
                    cfg.setLeapingToEnabled(!cfg.isLeapingToEnabled());
                    cfg.save();
                    btn.setMessage(leapingToText());
                }).bounds(x, y, half, ROW).build());
        int fieldW = Math.max(1, rightW - GAP - SET_W);
        // message text "Leaping To message" is also the SettingTooltips key - keep it
        EditBox messageField = new EditBox(Minecraft.getInstance().font, rightX, y, fieldW, ROW,
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
                }).bounds(rightX + fieldW + GAP, y, rightW - fieldW - GAP, ROW).build());
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
