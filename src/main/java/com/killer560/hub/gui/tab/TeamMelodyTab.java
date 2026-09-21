package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.melody.MelodyHudConfig;
import com.killer560.hub.melody.MelodyTrackerFeature;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Team Melody settings - see {@link MelodyTrackerFeature}'s class doc for the read-only F7/M7 Melody
 *  terminal tracker and HUD this configures. The HUD's on-screen position is dragged/resized like every
 *  other one in the HUD editor; the "Scale" slider here is the same stored value
 *  ({@link HudConfig#getScale}), just a second way to reach it without opening the editor. */
public class TeamMelodyTab extends BaseTab {

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.0f;

    public TeamMelodyTab() {
        super("Team Melody");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        MelodyHudConfig cfg = MelodyHudConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Team Melody HUD", cfg.isHudEnabledRaw()), btn -> {
                    cfg.setHudEnabled(!cfg.isHudEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isHudEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Share My Progress", cfg.isShareProgress()), btn -> {
                    cfg.setShareProgress(!cfg.isShareProgress());
                    cfg.save();
                    btn.setMessage(onOff("Share My Progress", cfg.isShareProgress()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(scaleSlider(contentX, y, contentWidth));
        y += 22;

        return widgets;
    }

    private static AbstractWidget scaleSlider(int x, int y, int width) {
        String id = MelodyTrackerFeature.HUD.id();
        return slider(x, y, width, "Scale", "x", MIN_SCALE, MAX_SCALE, 0.05f, 2,
                () -> HudConfig.getInstance().getScale(id, 1.0f),
                v -> HudConfig.getInstance().setScale(id, v));
    }

    /** Same pattern every other tab's own scale/offset sliders use (see {@code HeldItemTab#slider}), reading
     *  and writing {@link HudConfig} directly instead of a second, competing scale field - the HUD editor's
     *  own scroll-to-resize and this slider are two ways to change the exact same stored value. */
    private static ThemedSliderButton slider(int x, int y, int width, String name, String unit,
                                              float min, float max, float step, int decimals,
                                              Supplier<Float> getter, Consumer<Float> setter) {
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
                float factor = (float) Math.pow(10, decimals);
                setter.accept(Math.round(snapped * factor) / factor + 0f);
                HudConfig.getInstance().save();
            }
        };
    }

    private static Component sliderLabel(String name, float value, String unit, int decimals) {
        String num = String.format(Locale.US, "%." + decimals + "f", value);
        return Component.literal(name + ": " + num + unit);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
