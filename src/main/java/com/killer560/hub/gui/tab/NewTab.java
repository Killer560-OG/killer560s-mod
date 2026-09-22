package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Every feature killer560 hasn't personally confirmed working yet, all in one place - per his explicit
 *  request (2026-09-13) so testing has one list to work through instead of hunting through normal
 *  category tabs. Once a feature here is confirmed working for real, move its tab back to wherever it
 *  normally belongs (Dungeon/Chat/etc.) and remove it from this list - this tab is a temporary staging
 *  area, not a permanent home. Fullbright was tested and confirmed working 2026-09-14 and was removed
 *  from here (it only ever lived in {@code DisplayTab} anyway - no tab to move back). Leap Menu, Fast
 *  Leap, Posmsg, Ability Timers, Dungeon Info, Mob ESP, Mapping, and Etherwarp (landed just before this
 *  session) were added 2026-09-14 per killer560's "add things like posmsg and whatnot... everything I
 *  haven't tested" request - untested is untested regardless of which session built it.
 *  <p>
 *  {@code LeapMenuTab} (2026-09-14, later same day) consolidated what used to be 4 separate tabs - the
 *  old "Leap Order" tab, {@code SpiritLeapOverlayTab} ("Custom Leap Menu"), {@code FastLeapTab}, and
 *  {@code LeapMessageTab} - per killer560's own "everything related to spirit leaps should be under one
 *  setting called leap menu" request. It has since been confirmed working and moved on to the Dungeon
 *  tab (2026-09-16), exactly the journey every tab in this list is meant to make. Fast/Auto Leap is its
 *  own top-level tab (cheat build only) and is NOT part of that move. */
public class NewTab extends FolderTab {

    public NewTab() {
        super("New", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new LeapCounterTab(),
                new ArmourDyeTab(),
                new TooltipScrollTab(),
                new EnchantColorsTab(),
                new AbilityTimersTab(),
                new DungeonInfoTab(),
                new TimeHudTab(),
                new TrailTab(),
                new CommandShortcutsTab(),
                new InventoryThemeTab(),
                new BestFriendsTab(),
                new FriendsListTab(),
                new PetWheelTab(),
                new SupportersTab(),
                new TeamMelodyTab(),
                new MobEspTab(),
                new TeammatesTab(),
                new TickTimersTab(),
                new LagDisplayTab(),
                new PositionTab(),
                new AbilityCooldownTab(),
                new SplitTimersTab(),
                new RunSummaryTab(),
                new RunStatsTab(),
                new WitherDragonsTab(),
                new TerminalTimersTab(),
                new DungeonAlertsTab(),
                new TerracottaTimerTab(),
                new SpringBootsTab(),
                new ClassColorsTab(),
                new RagAxeTab(),
                new NameChangerTab(),
                new CroesusTab(),
                new HeldItemTab(),
                new ArrowAlignTab(),
                new StorageSearchTab(),
                new WaypointRoutesTab(),
                new PathfindingTab(),
                new CustomMageBeamTab(),
                new DungeonQueueTab(),
                new MaskInvincibilityTab(),
                new ModChatTab(),
                new InteropTab(),
                new I4SensorsTab(),
                new LiveMapTab(),
                new AutoCloseChestTab(),
                new BloodCampTab(),
                new ThornTab(),
                new F7SpotsTab(),
                new P3NavTab(),
                new MaxorTab(),
                new BlessingsTab(),
                new ChunkCacheTab(),
                new ScoreCalculatorTab(),
                new QuiverDisplayTab(),
                new InventoryHudTab(),
                new CustomScoreboardTab(),
                new ProfileViewerTab(),
                new PlayerStatsTab(),
                new SlotBindsTab(),
                new PartyCommandsTab(),
                new DoorKeysTab(),
                new WitherDoorsTab(),
                new TrajectoriesTab(),
                new LoadoutKeybindsTab(),
                new AbilityKeybindsTab(),
                new P4PlatformHighlightTab(),
                // Moved into New 2026-09-20 ("move all solvers into the New category for now"), then back
                // out the same day once killer560 tested them and changed his mind: all 11 puzzle/boss
                // solvers plus Solver Highlights now live in the "Puzzle Solvers" folder inside Dungeon -
                // see PuzzleSolversTab.
                new CommandKeybindsTab(),
                new RevertMasterStarsTab(),
                new InventorySearchTab(),
                new ItemBrowserTab(),
                new AuctionHouseTab(),
                new BazaarTab(),
                new ItemProtectTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Cheat-only: clicking a room etherwarps you to it and starts a secret route (killer560,
            // 2026-09-20: "that is a cheat"). The legit jar has no such tab at all.
            tabs.add(new InteractiveMapTab());
            // Cheat-only: this is the half that actually walks him to the souls (killer560, 2026-09-20:
            // "make auto fairy soul tab that is red with all of that logic"). Pathfinding keeps the legit
            // tracking and display.
            tabs.add(new AutoFairySoulsTab());
            tabs.add(new FastLeapTab());
            tabs.add(new GoldorTriggerbotTab());
            tabs.add(new AutoGfsTab());
            tabs.add(new AutoUltTab());
            tabs.add(new AutoChocolateFactoryTab());
            tabs.add(new DoorHelpersTab());
            tabs.add(new DungeonBreakerTab());
            tabs.add(new DioriteGlassTab());
            tabs.add(new TerminalAuraTab());
            tabs.add(new TerminalTriggerbotTab());
            tabs.add(new AutoRoutesTab());
            tabs.add(new Ap3Tab());
            tabs.add(new FreezeStateTab());
            // Split out of the old "Dungeon Extras" tab 2026-09-20 (killer560: "remove that tab. Make a
            // breaker aura tab itself. make auto dialoug its own category as well") - see BreakerAuraTab,
            // AutoDialogueTab and CustomMageBeamTab (the last one is legit and lives in the normal list
            // above, since Custom Mage Beam has no CHEAT_FEATURES_ENABLED gate of its own).
            tabs.add(new BreakerAuraTab());
            tabs.add(new AutoDialogueTab());
        }
        return tabs;
    }
}
