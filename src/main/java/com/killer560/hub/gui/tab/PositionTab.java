package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.position.PositionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Advanced Position settings - exact X/Y/Z coordinates as a movable HUD, more precise than F3. See
 *  {@link com.killer560.hub.position.PositionFeature}. Master ships OFF; every change saves immediately. */
public class PositionTab extends BaseTab {

    public PositionTab() {
        super("Advanced Position");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PositionConfig cfg = PositionConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Advanced Position");
        w.add(SettingsButtonWidget.builder(onOff("Advanced Position", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.isEnabled()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Precision");
        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, decimalPlacesLabel(cfg),
                (cfg.getDecimalPlaces() - PositionConfig.MIN_DECIMAL_PLACES)
                        / (double) (PositionConfig.MAX_DECIMAL_PLACES - PositionConfig.MIN_DECIMAL_PLACES)) {
            @Override
            protected void updateMessage() {
                setMessage(decimalPlacesLabel(cfg));
            }

            @Override
            protected void applyValue() {
                int range = PositionConfig.MAX_DECIMAL_PLACES - PositionConfig.MIN_DECIMAL_PLACES;
                cfg.setDecimalPlaces(PositionConfig.MIN_DECIMAL_PLACES + (int) Math.round(this.value * range));
                cfg.save();
            }
        });
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Lines");
        toggle(w, contentX, y[0], half, "Show Facing", cfg::isShowFacing, cfg::setShowFacing, cfg);
        toggle(w, colB, y[0], half, "Show Block", cfg::isShowBlock, cfg::setShowBlock, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "Show Velocity", cfg::isShowVelocity, cfg::setShowVelocity, cfg);
        y[0] += 24;
        return w;
    }

    private static Component decimalPlacesLabel(PositionConfig cfg) {
        return Component.literal("Decimal Places: " + cfg.getDecimalPlaces());
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                               Supplier<Boolean> get, Consumer<Boolean> set, PositionConfig cfg) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    cfg.save();
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
