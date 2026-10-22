package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.zoom.ZoomConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Zoom settings: the on/off switch, the zoom key, the zoom level and whether the wheel changes it while zoomed. */
public class ZoomTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing = false;

    public ZoomTab() {
        super("Zoom");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        ZoomConfig cfg = ZoomConfig.getInstance();
        cfg.setKeyCode(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        cfg.save();
        capturing = false;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        ZoomConfig cfg = ZoomConfig.getInstance();
        cfg.setKeyCode(KeyUtil.codeForMouseButton(button));
        cfg.save();
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ZoomConfig cfg = ZoomConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Zoom", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(
                capturing ? Component.literal("Press any key...")
                        : Component.literal("Zoom Key: " + (cfg.getKeyCode() == KeyUtil.NONE ? "§7Not Set"
                        : KeyUtil.bindDisplayName(cfg.getKeyCode()))),
                btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        double range = ZoomConfig.MAX_LEVEL - ZoomConfig.MIN_LEVEL;
        double normalized = (cfg.getZoomLevel() - ZoomConfig.MIN_LEVEL) / range;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, levelText(cfg), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(levelText(ZoomConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                ZoomConfig c = ZoomConfig.getInstance();
                c.setZoomLevel((float) (ZoomConfig.MIN_LEVEL + this.value * range));
                saveOnRelease(() -> c.save());
                this.value = (c.getZoomLevel() - ZoomConfig.MIN_LEVEL) / range;
            }
        });
        y += 26;

        widgets.add(SettingsButtonWidget.builder(onOff("Scroll While Zoomed", cfg.isScrollWhileZoomed()), btn -> {
                    cfg.setScrollWhileZoomed(!cfg.isScrollWhileZoomed());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // How far one wheel notch moves the zoom, 2% to 50% (killer560, 2026-10-19: "a slider for how much the zoom changes").
        float stepRange = ZoomConfig.MAX_SCROLL_STEP - ZoomConfig.MIN_SCROLL_STEP;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, scrollStepText(cfg),
                (cfg.getScrollStep() - ZoomConfig.MIN_SCROLL_STEP) / stepRange) {
            @Override
            protected void updateMessage() {
                setMessage(scrollStepText(ZoomConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                ZoomConfig c = ZoomConfig.getInstance();
                c.setScrollStep((float) (ZoomConfig.MIN_SCROLL_STEP + this.value * stepRange));
                saveOnRelease(() -> c.save());
            }
        });
        y += 22;

        // Zoom Style is a cycling setting like the others: the arrows step it, and the value is in the menu's orange.
        widgets.add(SettingsButtonWidget.cycling(Component.literal("Zoom Style: §6" + (cfg.isSmooth() ? "Smooth" : "Instant")),
                (btn, dir) -> {
                    cfg.setSmooth(!cfg.isSmooth());
                    cfg.save();
                    btn.setMessage(Component.literal("Zoom Style: §6" + (cfg.isSmooth() ? "Smooth" : "Instant")));
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static Component levelText(ZoomConfig cfg) {
        return Component.literal(String.format(Locale.US, "Zoom Level: %.1fx", cfg.getZoomLevel()));
    }

    private static Component scrollStepText(ZoomConfig cfg) {
        return Component.literal(String.format(Locale.US, "Scroll Step: %.0f%%", cfg.getScrollStep() * 100));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
