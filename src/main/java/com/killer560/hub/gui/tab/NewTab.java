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
 *  haven't tested" request - untested is untested regardless of which session built it. */
public class NewTab extends FolderTab {

    public NewTab() {
        super("New", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new LeapMenuTab(),
                new FastLeapTab(),
                new PosmsgTab(),
                new AbilityTimersTab(),
                new DungeonInfoTab(),
                new MobEspTab(),
                new MappingTab(),
                new EtherwarpTab(),
                new SimonSaysTab(),
                new TickTimersTab(),
                new SplitTimersTab(),
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
                new PlayerStatsTab(),
                new SpiritLeapOverlayTab(),
                new EtherwarpOverlayTab(),
                new SlotBindsTab(),
                new ChatCommandsTab(),
                new DoorKeysTab(),
                new TrajectoriesTab(),
                new LoadoutKeybindsTab(),
                new AbilityKeybindsTab(),
                new P4PlatformHighlightTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new AutoLeapTab());
            tabs.add(new DungeonBreakerTab());
            tabs.add(new DioriteGlassTab());
        }
        return tabs;
    }
}
