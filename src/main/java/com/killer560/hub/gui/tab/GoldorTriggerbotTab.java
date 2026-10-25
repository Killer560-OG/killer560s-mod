package com.killer560.hub.gui.tab;

import com.killer560.hub.goldor.GoldorTriggerbotConfig;
import com.killer560.hub.gui.RangeSliderWidget;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Goldor Triggerbot settings - cheat build only, so the whole tab only exists on the cheat jar and its header is
 *  drawn red. The rate is a two-handled CPS range ({@link RangeSliderWidget}, the same control i4 Sensors uses),
 *  with the millisecond band it works out to shown alongside it so it lines up with Arrow Align's delays. See
 *  {@code GoldorTriggerbotFeature}. */
public class GoldorTriggerbotTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;

    public GoldorTriggerbotTab() {
        super("Goldor Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        GoldorTriggerbotConfig cfg = GoldorTriggerbotConfig.getInstance();
        int y = contentY;
        int halfW = (contentWidth - GAP) / 2;
        int rightW = Math.max(1, contentWidth - halfW - GAP);

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Goldor Triggerbot", true));
        y += 16;

        widgets.add(onOffButton("Goldor Triggerbot", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(new RangeSliderWidget(contentX, y, contentWidth, ROW,
                GoldorTriggerbotConfig.MIN_CPS, GoldorTriggerbotConfig.MAX_CPS, cfg.getCpsMin(), cfg.getCpsMax()) {
            @Override
            protected Component label(int low, int high) {
                return cpsText(low, high);
            }

            @Override
            protected void onRangeChanged(int low, int high) {
                cfg.setCpsRange(low, high);
                cfg.save();
            }
        });
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.cycling(clickTypeText(cfg), (btn, dir) -> {
                    cfg.cycleClickType(dir);
                    cfg.save();
                    btn.setMessage(clickTypeText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(SettingsButtonWidget.cycling(aimDelayText(cfg), (btn, dir) -> {
                    cfg.setAimDelayMs((int) SettingsButtonWidget.step(cfg.getAimDelayMs(), 25, 0,
                            GoldorTriggerbotConfig.MAX_AIM_DELAY_MS, dir));
                    cfg.save();
                    btn.setMessage(aimDelayText(cfg));
                }).bounds(contentX + halfW + GAP, y, rightW, ROW).build());
        y += ROW + GAP;

        double minRange = GoldorTriggerbotConfig.MIN_RANGE;
        double maxRange = GoldorTriggerbotConfig.MAX_RANGE;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, rangeText(cfg),
                (cfg.getRange() - minRange) / (maxRange - minRange)) {
            @Override
            protected void updateMessage() {
                setMessage(rangeText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setRange(minRange + this.value * (maxRange - minRange));
                saveOnRelease(() -> cfg.save());
            }
        });
        return widgets;
    }

    /** More clicks per second is a shorter gap, so the high CPS end is the low millisecond end. */
    private static Component cpsText(int low, int high) {
        return Component.literal("CPS: " + low + " - " + high + " (" + msFor(high) + "-" + msFor(low) + " ms)");
    }

    private static long msFor(int cps) {
        return Math.round(1000.0 / Math.max(1, cps));
    }

    private static Component clickTypeText(GoldorTriggerbotConfig cfg) {
        return Component.literal("Click Type: §b" + cfg.getClickType().label);
    }

    private static Component aimDelayText(GoldorTriggerbotConfig cfg) {
        return Component.literal("Aim Delay: " + cfg.getAimDelayMs() + "ms");
    }

    private static Component rangeText(GoldorTriggerbotConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.1f", cfg.getRange()));
    }
}
