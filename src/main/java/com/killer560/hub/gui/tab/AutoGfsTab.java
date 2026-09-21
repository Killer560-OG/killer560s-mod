package com.killer560.hub.gui.tab;

import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Auto GFS (/gfs from sacks; optional Dungeons &amp; Kuudra Only restriction) - see
 *  {@code com.killer560.hub.cheatutils.CheatUtils}. Cheat build
 *  only; split out of the old "Cheat Utils" tab 2026-09-20 per killer560: "Move auto gfs its own tab as
 *  well" - shares {@link CheatUtilsConfig} with {@code CheatUtilsTab} (deleted 2026-09-21), {@link AutoUltTab} and
 *  {@link AutoChocolateFactoryTab}; the config file itself was not split. */
public class AutoGfsTab extends BaseTab {

    private static final int BTN_W = 220;

    public AutoGfsTab() {
        super("Auto GFS");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        int half = (contentWidth - 8) / 2;
        int[] y = {contentY};

        toggle(w, contentX, y, "Auto GFS", cfg::isAutoGfsEnabled, cfg::setAutoGfsEnabled, requestRebuild);
        if (!cfg.isAutoGfsEnabled()) {
            return w;
        }

        toggle(w, contentX, y, "Ender Pearls", cfg::isGfsPearls, cfg::setGfsPearls, null);
        toggle(w, contentX, y, "Spirit Leaps", cfg::isGfsLeaps, cfg::setGfsLeaps, null);
        toggle(w, contentX, y, "Superboom TNT", cfg::isGfsSuperbooms, cfg::setGfsSuperbooms, null);
        toggle(w, contentX, y, "Inflatable Jerry", cfg::isGfsJerries, cfg::setGfsJerries, null);
        toggle(w, contentX, y, "Skip If None In Inventory", cfg::isGfsSkipIfNone, cfg::setGfsSkipIfNone, null);
        toggle(w, contentX, y, "Dungeons & Kuudra Only", cfg::isGfsDungeonKuudraOnly, cfg::setGfsDungeonKuudraOnly, null);
        slider(w, contentX, y[0], half, () -> "Refill Below: " + cfg.getGfsThresholdPercent() + "%",
                norm(cfg.getGfsThresholdPercent(), CheatUtilsConfig.MIN_GFS_THRESHOLD_PERCENT, CheatUtilsConfig.MAX_GFS_THRESHOLD_PERCENT),
                v -> cfg.setGfsThresholdPercent(denorm(v, CheatUtilsConfig.MIN_GFS_THRESHOLD_PERCENT, CheatUtilsConfig.MAX_GFS_THRESHOLD_PERCENT)));
        slider(w, contentX + half + 8, y[0], half, () -> "Check Every: " + cfg.getGfsIntervalSec() + "s",
                norm(cfg.getGfsIntervalSec(), CheatUtilsConfig.MIN_GFS_INTERVAL_SEC, CheatUtilsConfig.MAX_GFS_INTERVAL_SEC),
                v -> cfg.setGfsIntervalSec(denorm(v, CheatUtilsConfig.MIN_GFS_INTERVAL_SEC, CheatUtilsConfig.MAX_GFS_INTERVAL_SEC)));
        y[0] += 28;

        return w;
    }

    private static double norm(int value, int min, int max) {
        return (value - min) / (double) (max - min);
    }

    private static int denorm(double v, int min, int max) {
        return (int) Math.round(min + v * (max - min));
    }

    /** ON/OFF toggle button; a non-null {@code rebuild} rebuilds the tab (used by master toggles). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            CheatUtilsConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               java.util.function.DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, width, 20, Component.literal(text.get()), Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
                CheatUtilsConfig.getInstance().save();
            }
        });
    }
}
