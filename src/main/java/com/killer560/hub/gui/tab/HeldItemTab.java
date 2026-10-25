package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.helditem.HeldItemConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Held Item Transform settings - see {@link HeldItemConfig}. Per-hand scale / offset / rotation sliders,
 *  global swing/equip/sway toggles, swing speed, and a reset button. */
public class HeldItemTab extends BaseTab {

    public HeldItemTab() {
        super("Held Item Transform");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        HeldItemConfig cfg = HeldItemConfig.getInstance();

        widgets.add(onOffButton("Held Item Transform", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        // ---- Animations (both hands) ----
        widgets.add(label(contentX, y, contentWidth, "§6Animations"));
        y += 14;

        widgets.add(onOffButton("No Swing Animation", cfg::isNoSwing, cfg::setNoSwing, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(onOffButton("No Equip Animation", cfg::isNoEquip, cfg::setNoEquip, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(onOffButton("No Hand Sway", cfg::isNoHandSway, cfg::setNoHandSway, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        if (!cfg.isNoSwing()) {
            widgets.add(slider(contentX, y, contentWidth, "Swing Speed", "x",
                    HeldItemConfig.MIN_SWING_SPEED, HeldItemConfig.MAX_SWING_SPEED, 0.05f, 2,
                    cfg::getSwingSpeed, cfg::setSwingSpeed, cfg));
            y += 22;
        }

        // ---- Main hand ----
        y += 4;
        widgets.add(label(contentX, y, contentWidth, cfg.isSeparateOffHand() ? "§6Main Hand" : "§6Both Hands"));
        y += 14;
        y = addHandSliders(widgets, contentX, y, contentWidth, cfg.getMainHand(), cfg);

        widgets.add(onOffButton("Separate Off Hand", cfg::isSeparateOffHand, cfg::setSeparateOffHand, cfg::save,
                requestRebuild, contentX, y, contentWidth, 18));
        y += 22;

        // ---- Off hand ----
        if (cfg.isSeparateOffHand()) {
            widgets.add(label(contentX, y, contentWidth, "§6Off Hand"));
            y += 14;
            y = addHandSliders(widgets, contentX, y, contentWidth, cfg.getOffHand(), cfg);
        }

        y += 4;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset to Defaults"), btn -> {
                    cfg.resetValues();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private static int addHandSliders(List<AbstractWidget> widgets, int x, int y, int width,
                                      HeldItemConfig.HandTransform t, HeldItemConfig cfg) {
        widgets.add(slider(x, y, width, "Scale", "x", HeldItemConfig.MIN_SCALE, HeldItemConfig.MAX_SCALE, 0.05f, 2,
                () -> t.scale, v -> t.scale = v, cfg));
        y += 22;
        widgets.add(slider(x, y, width, "X Offset", "", HeldItemConfig.MIN_OFFSET, HeldItemConfig.MAX_OFFSET, 0.01f, 2,
                () -> t.x, v -> t.x = v, cfg));
        y += 22;
        widgets.add(slider(x, y, width, "Y Offset", "", HeldItemConfig.MIN_OFFSET, HeldItemConfig.MAX_OFFSET, 0.01f, 2,
                () -> t.y, v -> t.y = v, cfg));
        y += 22;
        widgets.add(slider(x, y, width, "Z Offset", "", HeldItemConfig.MIN_OFFSET, HeldItemConfig.MAX_OFFSET, 0.01f, 2,
                () -> t.z, v -> t.z = v, cfg));
        y += 22;
        widgets.add(slider(x, y, width, "X Rotation", "°", HeldItemConfig.MIN_ROTATION, HeldItemConfig.MAX_ROTATION, 1f, 0,
                () -> t.rotX, v -> t.rotX = v, cfg));
        y += 22;
        widgets.add(slider(x, y, width, "Y Rotation", "°", HeldItemConfig.MIN_ROTATION, HeldItemConfig.MAX_ROTATION, 1f, 0,
                () -> t.rotY, v -> t.rotY = v, cfg));
        y += 22;
        widgets.add(slider(x, y, width, "Z Rotation", "°", HeldItemConfig.MIN_ROTATION, HeldItemConfig.MAX_ROTATION, 1f, 0,
                () -> t.rotZ, v -> t.rotZ = v, cfg));
        y += 24;
        return y;
    }

    /** A float slider snapped to {@code step}, saving on every change (same pattern as the other tabs). */
    private static ThemedSliderButton slider(int x, int y, int width, String name, String unit,
                                             float min, float max, float step, int decimals,
                                             Supplier<Float> getter, Consumer<Float> setter, HeldItemConfig cfg) {
        double norm = (getter.get() - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, sliderLabel(name, getter.get(), unit, decimals),
                Math.max(0.0, Math.min(1.0, norm))) {
            @Override
            protected void updateMessage() {
                setMessage(sliderLabel(name, getter.get(), unit, decimals));
            }

            @Override
            protected void applyValue() {
                float raw = min + (float) (this.value * (max - min));
                float snapped = Math.round(raw / step) * step;
                snapped = Math.max(min, Math.min(max, snapped));
                // Round away float noise (e.g. 0.30000001) so the saved JSON and label stay clean.
                float factor = (float) Math.pow(10, decimals);
                float rounded = Math.round(snapped * factor) / factor + 0f; // + 0f turns -0.0 into 0.0
                // A drag calls this on every mouse move; only a changed step needs a write to disk.
                if (rounded == getter.get()) return;
                setter.accept(rounded);
                saveOnRelease(() -> cfg.save());
            }
        };
    }

    private static Component sliderLabel(String name, float value, String unit, int decimals) {
        String num = String.format(Locale.US, "%." + decimals + "f", value);
        return Component.literal(name + ": " + num + unit);
    }

    private static StringWidget label(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal(text), Minecraft.getInstance().font);
    }
}
