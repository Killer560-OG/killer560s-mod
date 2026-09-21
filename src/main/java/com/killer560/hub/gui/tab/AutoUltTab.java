package com.killer560.hub.gui.tab;

import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Auto Ult (F7/M7 boss, Healer/Tank) - see {@code com.killer560.hub.cheatutils.CheatUtils}. Cheat build
 *  only; split out of the old "Cheat Utils" tab 2026-09-20 per killer560: "Make auto ult its own category
 *  as well" - shares {@link CheatUtilsConfig} with {@link CheatUtilsTab}, {@link AutoGfsTab} and
 *  {@link AutoChocolateFactoryTab}; the config file itself was not split. */
public class AutoUltTab extends BaseTab {

    private static final int BTN_W = 220;

    public AutoUltTab() {
        super("Auto Ult");
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
        int[] y = {contentY};

        toggle(w, contentX, y, "Auto Ult", cfg::isAutoUltEnabled, cfg::setAutoUltEnabled, requestRebuild);
        if (!cfg.isAutoUltEnabled()) {
            return w;
        }

        toggle(w, contentX, y, "On Maxor Enraged", cfg::isUltMaxorEnraged, cfg::setUltMaxorEnraged, null);
        toggle(w, contentX, y, "On Goldor Factory Destroyed", cfg::isUltGoldorFactory, cfg::setUltGoldorFactory, null);
        w.add(SettingsButtonWidget.builder(classText(cfg), btn -> {
            cfg.cycleUltClassOverride();
            cfg.save();
            btn.setMessage(classText(cfg));
        }).bounds(contentX, y[0], BTN_W, 20).build());
        y[0] += 28;

        return w;
    }

    private static Component classText(CheatUtilsConfig cfg) {
        String c = cfg.getUltClassOverride();
        return Component.literal("Class: §b" + ("AUTO".equalsIgnoreCase(c) ? "Auto (tab list)" : c.charAt(0) + c.substring(1).toLowerCase(java.util.Locale.ROOT)));
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
}
