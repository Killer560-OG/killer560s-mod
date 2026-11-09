package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** The Dungeon category. Every feature is its own entry here (killer560, 2026-10-07: "The things like Map, Leap and
 *  Party, all of those should be their own unique tab, not all grouped under the one"), so the two folders the New
 *  category's removal made, "Map, Leap & Party" and "Timers, Score & Boss", are gone and their tabs sit in this list.
 *  Secrets and Puzzle Solvers stay folders: he asked for each of those as one tab (2026-09-20).
 *  <p>
 *  Legit tabs come first, in groups: the map, doors and party; clearing; timers and score; the bosses in floor order;
 *  the sim. The cheat build's tabs follow ALL of them, as one block at the bottom (killer560, 2026-10-07: "Some cheat
 *  things are right below their setting instead of being at the bottom like they should be if they are cheats"), in
 *  the same order as the legit tab each one goes with. The legit jar has none of them at all - not even a header. */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                // ---- map, doors, party ----
                new LiveMapTab(),
                new ChunkCacheTab(),
                new DoorKeysTab(),
                new WitherDoorsTab(),
                new LeapMenuTab(),
                new LeapCounterTab(),
                new MilestoneTab(),
                new DungeonItemHighlighterTab(),
                new DungeonQueueTab(),
                // Creation Cooldown (to-do Doc 2026-11-01): SkyHanni's 30 s instance creation timer.
                new CreationCooldownTab(),
                // Insta Clear Warning (to-do Doc 2026-11-01): a blood rush room cleared without its key.
                new InstaClearWarningTab(),
                // Blood Rush Helper (killer560, 2026-11-03): where to kill the blood rush room's starred mobs.
                new BloodRushHelperTab(),
                new CustomPartyFinderTab(),
                new AutoKickTab(),
                // Not cheat-only: setting someone's class fixes the normal leap menu and every class-colored display
                // too, not just AP3's leap nodes (killer560, 2026-09-16).
                new ClassColorsTab(),
                new TeammatesTab(),
                new TeamMelodyTab(),
                new InteropTab(),
                // One home for everything secret-related (killer560, 2026-09-20); the cheat half is its own red
                // Secrets folder in the cheat block below.
                new SecretsTab(),
                // Every puzzle solver in one tab, separate from Auto Puzzles (killer560, 2026-09-20).
                new PuzzleSolversTab(),
                new ArchitectDraftTab(),
                new BloodCampTab(),
                new MobEspTab(),
                new CustomMageBeamTab(),
                new RagAxeTab(),
                new MaskInvincibilityTab(),
                new MaskKeybindsTab(),
                new BreakerChargesTab(),
                // ---- timers, score, loot ----
                new SplitTimersTab(),
                new TickTimersTab(),
                new TerminalTimersTab(),
                new TerracottaTimerTab(),
                new ScoreCalculatorTab(),
                new ScoreWarningTab(),
                new RunSummaryTab(),
                new RunStatsTab(),
                new DungeonAlertsTab(),
                new LowHealthAlertTab(),
                new BlessingsTab(),
                new PuzzleHudTab(),
                new RepartyTab(),
                new WatcherHelperTab(),
                new RoomNameTab(),
                new ItemQualityTab(),
                new IcedMobsTab(),
                new AutoCloseChestTab(),
                new CarryTrackerTab(),
                new CroesusTab(),
                new RngMeterTab(),
                // ---- bosses, floor order ----
                new BossHealthTab(),
                new ThornTab(),
                // Its own tab, out of Puzzles (killer560, 2026-09-21).
                new LividSolverTab(),
                new MaxorTab(),
                new TerminalSolverTab(),
                new TermismTab(),
                new SimonSaysTab(),
                new I4SensorsTab(),
                new ArrowAlignTab(),
                new P3NavTab(),
                new P3StatusTab(),
                new F7SpotsTab(),
                new PosmsgTab(),
                new P4PlatformHighlightTab(),
                new WitherDragonsTab(),
                // ---- the sim ----
                new SimSettingsTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // The cheat block: every tab here is isCheatOnly (red title) and exists only in the cheat jar.
            tabs.add(new InteractiveMapTab());
            tabs.add(new FastLeapTab());
            tabs.add(new DoorHelpersTab());
            tabs.add(new CheatSecretsTab());
            tabs.add(new AutoRoutesTab());
            tabs.add(new AutoClearTab());
            tabs.add(new AutoPuzzlesTab());
            tabs.add(new AutoTrapTab());
            tabs.add(new DungeonBreakerTab());
            tabs.add(new BreakerAuraTab());
            tabs.add(new AutoBloodCampTab());
            tabs.add(new AutoMaskTab());
            tabs.add(new AutoDialogueTab());
            tabs.add(new AutoSimonSaysTab());
            tabs.add(new AutoTerminalTab());
            tabs.add(new TerminalAuraTab());
            tabs.add(new TerminalTriggerbotTab());
            tabs.add(new RelicHelpersTab());
            tabs.add(new DioriteGlassTab());
            tabs.add(new GoldorTriggerbotTab());
            // Out of Secret Aura's In Boss, 2026-10-15 (killer560: "It should be its own cheat").
            tabs.add(new LightsDeviceTab());
            tabs.add(new AutoUltTab());
            tabs.add(new Ap3Tab());
            tabs.add(new FreezeStateTab());
            // Out of the Croesus tab, 2026-10-12 (killer560: "just like etable and auto etable").
            tabs.add(new AutoCroesusTab());
            tabs.add(new BlinkTab());
            tabs.add(new TerminatorClickerTab());
            tabs.add(new GhostBlockTab());
            // The Etherwarp tab (Zero Ping Etherwarp + Left Click Etherwarp): cheat-only since 2026-11-02 (killer560: "all
            // zero ping etherwarp stuff should be cheat only nothing in legit"); it was legit 2026-10-26 to 2026-11-02.
            tabs.add(new ZeroPingEtherwarpTab());
            tabs.add(new AutoSuperboomTab());
        }
        return tabs;
    }
}
