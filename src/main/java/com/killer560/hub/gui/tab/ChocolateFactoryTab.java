package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.chocolatefactory.ChocolateFactoryConfig;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.gui.components.AbstractWidget;

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
        // Split (killer560, 2026-10-19): what shows inside the factory menu, then Hoppity's Hunt outside it, then Cheat.
        section(w, contentX, y, "Inside the Chocolate Factory menu");
        toggle(w, contentX, y, "Highlight Best Upgrade", cfg::isHighlightBestUpgrade, cfg::setHighlightBestUpgrade, null);
        toggle(w, contentX, y, "Highlight Strays", cfg::isHighlightStrays, cfg::setHighlightStrays, null);
        toggle(w, contentX, y, "Stray Alert", cfg::isStrayAlert, cfg::setStrayAlert, null);
        toggle(w, contentX, y, "Time Tower Reminder", cfg::isTimeTowerReminder, cfg::setTimeTowerReminder, null);
        toggle(w, contentX, y, "Extra Stat Counter", cfg::isInfoLine, cfg::setInfoLine, null);
        // Hoppity's Hunt, simplified 2026-10-19 (killer560: "see if you can simplify the settings in that menu though and
        // combine them"): the timers HUD, its Extra Info (which took in Duplicate Rabbit Values), the Egglocator's line
        // and the tracker.
        section(w, contentX, y, "Hoppity's Hunt (outside the menu)");
        toggle(w, contentX, y, "Hoppity Egg Timers", cfg::isEggHud, cfg::setEggHud, requestRebuild);
        if (cfg.isEggHud()) {
            toggle(w, contentX, y, "Egg Extra Info", cfg::isEggExtraInfo, cfg::setEggExtraInfo, null);
        }
        toggle(w, contentX, y, "Egglocator Tracer Line", cfg::isEggLocatorLine, cfg::setEggLocatorLine, null);
        toggle(w, contentX, y, "Hoppity Tracker", cfg::isEggTracker, cfg::setEggTracker, null);
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Cheat-only settings go at the bottom of the tab under a red header (CLAUDE.md menu placement).
            y[0] += 6;
            w.add(SectionHeaders.row(contentX, y[0], BTN_W, "Cheat", true));
            y[0] += 16;
            toggle(w, contentX, y, "Egg ESP", cfg::isEggEsp, cfg::setEggEsp, null);
            toggle(w, contentX, y, "Hide Found Eggs", cfg::isEggEspHideFound, cfg::setEggEspHideFound, null);
        }
        return w;
    }

    /** A plain section label above a group of switches; takes one row of height. */
    private static void section(List<AbstractWidget> w, int x, int[] y, String title) {
        w.add(SectionHeaders.row(x, y[0], BTN_W, title, false));
        y[0] += 16;
    }

    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(onOffButton(name,
                getter::get, setter, () -> ChocolateFactoryConfig.getInstance().save(), rebuild, x, y[0], BTN_W, 20));
        y[0] += 24;
    }
}
