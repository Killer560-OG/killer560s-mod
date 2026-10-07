package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Dungeon > Timers, Score & Boss (2026-10-07): the README's "Dungeon: Timers, Score & Boss" features out of the
 *  removed New category. Auto Blood Camp sits under Blood Camp, Auto Close Chest under Croesus, and the boss
 *  automation (Auto Ult, Goldor Triggerbot) beside the boss helpers, cheat build only. */
public class DungeonTimersBossTab extends FolderTab {

    public DungeonTimersBossTab() {
        super("Timers, Score & Boss", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        List<BaseTab> tabs = new ArrayList<>();
        tabs.add(new SplitTimersTab());
        tabs.add(new TickTimersTab());
        tabs.add(new TerminalTimersTab());
        tabs.add(new TerracottaTimerTab());
        tabs.add(new RunSummaryTab());
        tabs.add(new RunStatsTab());
        tabs.add(new ScoreCalculatorTab());
        tabs.add(new DungeonAlertsTab());
        tabs.add(new BlessingsTab());
        tabs.add(new BloodCampTab());
        if (cheat) {
            tabs.add(new AutoBloodCampTab());
        }
        tabs.add(new CroesusTab());
        if (cheat) {
            tabs.add(new AutoCloseChestTab());
        }
        tabs.add(new CustomMageBeamTab());
        tabs.add(new RagAxeTab());
        if (cheat) {
            tabs.add(new AutoUltTab());
        }
        tabs.add(new MobEspTab());
        tabs.add(new I4SensorsTab());
        tabs.add(new ThornTab());
        tabs.add(new MaxorTab());
        tabs.add(new F7SpotsTab());
        tabs.add(new P3NavTab());
        if (cheat) {
            tabs.add(new GoldorTriggerbotTab());
        }
        tabs.add(new P4PlatformHighlightTab());
        tabs.add(new WitherDragonsTab());
        return tabs;
    }
}
