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
 *  {@code LeapMenuTab} (2026-09-14, later same day) is now a consolidated tab combining what used to be
 *  4 separate ones - the old "Leap Order" tab, {@code SpiritLeapOverlayTab} ("Custom Leap Menu"),
 *  {@code FastLeapTab}, and {@code LeapMessageTab} (moved in from the Dungeon tab) - per killer560's own
 *  "everything related to spirit leaps should be under one setting called leap menu" request. Stays here
 *  in New for now per his own instruction; the plan is for the whole consolidated tab to move to Dungeon
 *  once confirmed working, same as everything else in this list eventually does. */
public class NewTab extends FolderTab {

    public NewTab() {
        super("New", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new LeapMenuTab(),
                new PosmsgTab(),
                new AbilityTimersTab(),
                new DungeonInfoTab(),
                new MobEspTab(),
                new MappingTab(),
                new EtherwarpTab(),
                new TickTimersTab(),
                new SplitTimersTab(),
                new TerminalTimersTab(),
                new ItemRarityTab(),
                new ShortsTab(),
                new DungeonAlertsTab(),
                new NameChangerTab(),
                new PackDisablerTab(),
                new CroesusTab(),
                new HeldItemTab(),
                new StorageSearchTab(),
                new WaypointRoutesTab(),
                new DungeonExtrasTab(),
                new DungeonQueueTab(),
                new MotionBlurTab(),
                new MaskInvincibilityTab(),
                new ModChatTab(),
                new VoiceToTextTab(),
                new I4SensorsTab(),
                new LiveMapTab(),
                new SecretWaypointsTab(),
                new ProximityVoiceTab(),
                new AutoCloseChestTab(),
                new BloodCampTab(),
                new BoulderSolverTab(),
                new QuizSolverTab(),
                new IceFillSolverTab(),
                new WeirdosSolverTab(),
                new WaterSolverTab(),
                new BeamsSolverTab(),
                new BlazeSolverTab(),
                new LividSolverTab(),
                new QuiverDisplayTab(),
                new InventoryHudTab(),
                new PlayerStatsTab(),
                new EtherwarpOverlayTab(),
                new SlotBindsTab(),
                new ChatCommandsTab(),
                new DoorKeysTab(),
                new TrajectoriesTab(),
                new LoadoutKeybindsTab(),
                new AbilityKeybindsTab(),
                new P4PlatformHighlightTab(),
                new BetterPartyFinderTab(),
                new CommandKeybindsTab(),
                new RevertMasterStarsTab(),
                new InventorySearchTab(),
                new ItemBrowserTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new AutoPuzzlesTab());
            tabs.add(new CheatUtilsTab());
            tabs.add(new DungeonBreakerTab());
            tabs.add(new DioriteGlassTab());
        }
        return tabs;
    }
}
