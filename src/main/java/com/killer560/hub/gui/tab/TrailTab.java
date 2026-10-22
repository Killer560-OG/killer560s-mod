package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.trail.TrailConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings for the cosmetic squares trail - killer560's item 8.4: "one square per tick, 1-100, orange
 *  while grounded, blue while airborne, nothing while standing still." See
 *  {@link com.killer560.hub.trail.TrailFeature} for the actual tick/render logic. Master ships OFF;
 *  every change saves immediately. Purely visual with no gameplay effect, so no cheat-only (red)
 *  headers here. Squares only and never shared (killer560, 2026-10-14: "that is for configging specifically"); the
 *  ribbon is {@link RibbonTrailTab}. */
public class TrailTab extends BaseTab {

    public TrailTab() {
        super("Trail");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        TrailConfig cfg = TrailConfig.getInstance();
        int[] y = {contentY};
        header(w, contentX, y, contentWidth, "Trail");
        w.add(SettingsButtonWidget.builder(onOff("Trail", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.isEnabledRaw()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Appearance");
        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, lengthLabel(cfg),
                (cfg.getLength() - TrailConfig.MIN_LENGTH)
                        / (double) (TrailConfig.MAX_LENGTH - TrailConfig.MIN_LENGTH)) {
            @Override
            protected void updateMessage() {
                setMessage(lengthLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setLength(TrailConfig.MIN_LENGTH
                        + (int) Math.round(this.value * (TrailConfig.MAX_LENGTH - TrailConfig.MIN_LENGTH)));
                saveOnRelease(() -> cfg.save());
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, sizeLabel(cfg),
                (cfg.getSquareSize() - TrailConfig.MIN_SQUARE_SIZE)
                        / (TrailConfig.MAX_SQUARE_SIZE - TrailConfig.MIN_SQUARE_SIZE)) {
            @Override
            protected void updateMessage() {
                setMessage(sizeLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setSquareSize((float) (TrailConfig.MIN_SQUARE_SIZE
                        + this.value * (TrailConfig.MAX_SQUARE_SIZE - TrailConfig.MIN_SQUARE_SIZE)));
                saveOnRelease(() -> cfg.save());
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, opacityLabel(cfg),
                (cfg.getOpacityPercent() - TrailConfig.MIN_OPACITY_PERCENT)
                        / (double) (TrailConfig.MAX_OPACITY_PERCENT - TrailConfig.MIN_OPACITY_PERCENT)) {
            @Override
            protected void updateMessage() {
                setMessage(opacityLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setOpacityPercent(TrailConfig.MIN_OPACITY_PERCENT + (int) Math.round(
                        this.value * (TrailConfig.MAX_OPACITY_PERCENT - TrailConfig.MIN_OPACITY_PERCENT)));
                saveOnRelease(() -> cfg.save());
            }
        });
        y[0] += 22;

        toggle(w, contentX, y[0], contentWidth, "Fade Toward Tail", cfg::isFadeOut, v -> {
            cfg.setFadeOut(v);
            cfg.save();
        });
        y[0] += 22;
        w.add(SettingsButtonWidget.cycling(teleportLabel(cfg), (btn, dir) -> {
                    cfg.setTeleportMode(SettingsButtonWidget.step(cfg.getTeleportMode(), dir));
                    cfg.save();
                    btn.setMessage(teleportLabel(cfg));
                }).bounds(contentX, y[0], contentWidth, 18).build());
        y[0] += 22;
        firstPerson(w, contentX, y, contentWidth, cfg);

        return w;
    }

    private static void firstPerson(List<AbstractWidget> w, int x, int[] y, int width, TrailConfig cfg) {
        toggle(w, x, y[0], width, "Show In First Person", cfg::isShowInFirstPerson, v -> {
            cfg.setShowInFirstPerson(v);
            cfg.save();
        });
        y[0] += 22;
    }

    private static Component teleportLabel(TrailConfig cfg) {
        return Component.literal("On Teleport: " + cfg.getTeleportMode().label());
    }

    private static Component lengthLabel(TrailConfig cfg) {
        return Component.literal("Length: " + cfg.getLength());
    }

    private static Component sizeLabel(TrailConfig cfg) {
        return Component.literal(String.format(Locale.US, "Square Size: %.2f", cfg.getSquareSize()));
    }

    private static Component opacityLabel(TrailConfig cfg) {
        return Component.literal("Opacity: " + cfg.getOpacityPercent() + "%");
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                                Supplier<Boolean> get, Consumer<Boolean> set) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
