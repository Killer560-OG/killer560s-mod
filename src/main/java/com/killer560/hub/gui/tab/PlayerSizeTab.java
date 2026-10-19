package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.supporters.SupportersConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Player Size (killer560, 2026-10-19: "move the custom item resize out of its current cosmetics tab into its own unique
 *  tab still under the overarching cosmetic area"). My Size and Others' Size, and the model's Width, Height and Thickness.
 *  Moved out of {@link CosmeticsTab}; the controls and their saving are unchanged. */
public class PlayerSizeTab extends BaseTab {

    public PlayerSizeTab() {
        super("Player Size");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        buildPlayerSize(widgets, contentX, contentY, contentWidth, SupportersConfig.getInstance(), requestRebuild);
        return widgets;
    }

    private static int buildPlayerSize(List<AbstractWidget> widgets, int contentX, int y, int contentWidth,
                                       SupportersConfig cfg, Runnable requestRebuild) {
        int resetW = 60;
        int sliderW = contentWidth - resetW - 6;
        widgets.add(sizeSlider(contentX, y, sliderW, "My Size", cfg.getOwnScale(), cfg::setOwnScale, cfg));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset"), btn -> {
                    cfg.setOwnScale(1.0f);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + sliderW + 6, y, resetW, 18).build());
        y += 22;
        widgets.add(sizeSlider(contentX, y, sliderW, "Others' Size", cfg.getOthersScale(), cfg::setOthersScale, cfg));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset"), btn -> {
                    cfg.setOthersScale(1.0f);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + sliderW + 6, y, resetW, 18).build());
        y += 22;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Width", cfg.getModelWidth(), cfg::setModelWidth, cfg));
        y += 20;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Height", cfg.getModelHeight(), cfg::setModelHeight, cfg));
        y += 20;
        widgets.add(dimensionSlider(contentX, y, contentWidth, "Thickness", cfg.getModelThickness(), cfg::setModelThickness, cfg));
        y += 20;
        return y;
    }

    /** Play as Mob's on/off switch, then My Mob (a button that opens {@link MobPickerScreen}, its search box and list),
     *  then the note on sharing. Your size sliders apply to your mob too; see {@code com.killer560.hub.mobskin.MobSkinRender}. */
    private static ThemedSliderButton sizeSlider(int x, int y, int width, String label, float current,
                                          java.util.function.Consumer<Float> setter, SupportersConfig cfg) {
        float min = SupportersConfig.MIN_PLAYER_SIZE;
        float max = SupportersConfig.MAX_PLAYER_SIZE;
        double norm = (current - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, dimensionLabel(label, current),
                Math.max(0.0, Math.min(1.0, norm))) {
            @Override
            protected void updateMessage() {
                setMessage(dimensionLabel(label, snapSize(min + (float) (this.value * (max - min)))));
            }

            @Override
            protected void applyValue() {
                setter.accept(snapSize(min + (float) (this.value * (max - min))));
                cfg.save();
            }
        };
    }

    /** Hundredths, so the shared size is a tidy number and 0.05 and 1.00 can both be hit exactly. */
    private static float snapSize(float raw) {
        return Math.round(raw * 100f) / 100f;
    }

    private static ThemedSliderButton dimensionSlider(int x, int y, int width, String label, float current,
                                               java.util.function.Consumer<Float> setter, SupportersConfig cfg) {
        float min = SupportersConfig.MIN_MODEL_DIMENSION;
        float max = SupportersConfig.MAX_MODEL_DIMENSION;
        double norm = (current - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, dimensionLabel(label, current),
                Math.max(0.0, Math.min(1.0, norm))) {
            @Override
            protected void updateMessage() {
                setMessage(dimensionLabel(label, snapDimension(min + (float) (this.value * (max - min)))));
            }

            @Override
            protected void applyValue() {
                // the label shows what is saved (it used to show the unsnapped drag: "0.51x" saved as 0.50)
                float snapped = snapDimension(min + (float) (this.value * (max - min)));
                setter.accept(snapped);
                cfg.save();
            }
        };
    }

    /** Model dimensions go in 0.05 steps. */
    private static float snapDimension(float raw) {
        return Math.round(raw / 0.05f) * 0.05f;
    }

    private static Component dimensionLabel(String label, float value) {
        return Component.literal(label + ": " + String.format(Locale.ROOT, "%.2fx", value));
    }
}
