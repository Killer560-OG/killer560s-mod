package com.killer560.hub.gui.tab;

import com.killer560.hub.chocolatefactory.ChocolateFactoryConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Chocolate Factory - the legit, SkyHanni-style helper ({@link com.killer560.hub.chocolatefactory.ChocolateFactoryHelper}).
 *  Its own tab under Helpers (killer560, 2026-10-11); the cheat Auto Chocolate Factory keeps its red tab at the bottom. */
public class ChocolateFactoryTab extends BaseTab {

    private static final int BTN_W = 220;

    public ChocolateFactoryTab() {
        super("Chocolate Factory");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ChocolateFactoryConfig cfg = ChocolateFactoryConfig.getInstance();
        int[] y = {contentY};
        toggle(w, contentX, y, "Chocolate Factory Helper", cfg::isEnabled, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabled()) {
            return w;
        }
        toggle(w, contentX, y, "Highlight Best Upgrade", cfg::isHighlightBestUpgrade, cfg::setHighlightBestUpgrade, null);
        toggle(w, contentX, y, "Highlight Strays", cfg::isHighlightStrays, cfg::setHighlightStrays, null);
        toggle(w, contentX, y, "Stray Alert", cfg::isStrayAlert, cfg::setStrayAlert, null);
        toggle(w, contentX, y, "Time Tower Reminder", cfg::isTimeTowerReminder, cfg::setTimeTowerReminder, null);
        toggle(w, contentX, y, "Upgrade Info Line", cfg::isInfoLine, cfg::setInfoLine, null);
        return w;
    }

    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            ChocolateFactoryConfig.getInstance().save();
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
