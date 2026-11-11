package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.carnival.AutoAcceptGameConfig;
import com.killer560.hub.gui.ModuleKeybinds;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * The one Carnival card (carnivalfold-1111, killer560 2026-11-11: "this shouldnt be its own tab ... all under two blocks.
 * a red cheat carnival and a regular legit one"). The Carnival Tracker, Fruit Digging, Zombie Shootout and Catch a Fish
 * helpers sit under a "Carnival" header; the cheat-only triggerbots and autos under a red "Cheat" header at the bottom,
 * compiled out of a legit jar. There is no switch over the whole: every feature keeps its own, all of them in
 * {@link #masterToggles}. Each old card's rows are built by its old tab class, scoped to that name so its tooltips and saved
 * keys stay as they were, and its toggle key keeps the old card's id (a {@link ModuleKeybinds} extra).
 */
public class CarnivalTab extends BaseTab {

    private static final String[] LEGIT = {"Carnival Tracker", "Fruit Digging Solver", "Zombie Shootout Helper",
            "Catch a Fish Helper"};
    private static final String[] CHEAT = {"Zombie Shootout Triggerbot", "Auto Zombie Shootout", "Auto Shovel Swap",
            "Auto Fruit Digging", "Fruit Digging Triggerbot", "Auto Catch a Fish", "Auto Accept Game"};

    private final List<BaseTab> legit = List.of(new CarnivalTrackerTab(), new FruitDiggingTab(), new ZombieShootoutTab(),
            new CatchAFishTab());
    private final List<BaseTab> cheat = BuildVariant.CHEAT_FEATURES_ENABLED
            ? List.of(new ZombieTriggerbotTab(), new AutoZombieShootoutTab(), new AutoShovelSwapTab(),
            new AutoFruitDiggingTab(), new FruitDiggingTriggerbotTab(), new AutoCatchAFishTab())
            : List.of();

    public CarnivalTab() {
        super("Carnival");
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        List<String> all = new ArrayList<>(List.of(LEGIT));
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            all.addAll(List.of(CHEAT));
        }
        return switchRows(built, all.toArray(new String[0]));
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        w.add(SectionHeaders.row(contentX, contentY, contentWidth, "Carnival", false));
        int y = section(w, legit, contentX, contentY + 16, contentWidth, requestRebuild);
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat", true));
            y = section(w, cheat, contentX, y + 16, contentWidth, requestRebuild);
            y = autoAccept(w, contentX, y, contentWidth, requestRebuild);
        }
        return w;
    }

    /** Each tab's rows one under the other, then its toggle-key row; returns the y below them. */
    private int section(List<AbstractWidget> w, List<BaseTab> tabs, int x, int y, int width, Runnable rebuild) {
        for (BaseTab t : tabs) {
            List<AbstractWidget> rows = t.buildWidgets(x, y, width, rebuild);
            for (AbstractWidget r : rows) {
                SettingTooltips.scope(r, t.name);
                y = Math.max(y, r.getY() + r.getHeight());
            }
            w.addAll(rows);
            y += 4;
            w.add(keyRow(t.getClass().getSimpleName(), x, y, width, t.name));
            y += 26;
        }
        return y;
    }

    private static AbstractWidget keyRow(String id, int x, int y, int width, String scope) {
        SettingsButtonWidget row = ModuleKeybinds.row(id, x, y, width);
        SettingTooltips.scope(row, scope);
        return row;
    }

    /** Auto Accept Game: a switch and its red note (cheat only). */
    private int autoAccept(List<AbstractWidget> w, int x, int y, int width, Runnable rebuild) {
        AutoAcceptGameConfig cfg = AutoAcceptGameConfig.getInstance();
        int start = w.size();
        y = IslandRows.master(w, x, y, width, "Auto Accept Game", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, rebuild);
        y = IslandRows.automationNote(w, x, y, width);
        for (int i = start; i < w.size(); i++) {
            SettingTooltips.scope(w.get(i), "Auto Accept Game");
        }
        w.add(keyRow("AutoAcceptGameTab", x, y + 4, width, "Auto Accept Game"));
        return y + 30;
    }
}
