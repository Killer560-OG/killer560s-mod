package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Folder tab grouping dungeon-related features: RNG Meter, the Terminal Solver (moved in from Helpers,
 *  2026-09-09, per killer560's re-categorization request), and Termism (2026-09-09, killer560's
 *  terminal-practice request) on every build; Full Block/hitboxes and Auto Terminals (2026-09-10, cheat
 *  build only - per killer560's "cheat variant should have hitbox's auto etable and auto terms" request)
 *  are omitted from this list entirely on the legit build, which has neither feature at all, not just a
 *  disabled-looking version of them. Leap Message moved OUT of here (2026-09-14) into the New tab's
 *  consolidated {@code LeapMenuTab} - see that class's own doc for why. */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new RngMeterTab(),
                new TerminalSolverTab(),
                new TermismTab(),
                // Moved out of New 2026-09-14 - killer560 confirmed Simon Says (solver + Auto Start/Solve)
                // working after real runs ("I think ss is now done").
                new SimonSaysTab()
        ));
        // Leap Menu, Fast Leap, Posmsg, Ability Timers, Dungeon Info, Mob ESP, Mapping, Etherwarp
        // (landed just before this session, 2026-09-13) and Simon Says, Tick Timers, Split Timers, Mask
        // Invincibility, I4 Sensors, Live Map, Secret Waypoints, Mod Chat, Voice To Text, Proximity
        // Voice, and (cheat build) Auto Leap Out (built this session) all live in the "New" tab only
        // until killer560 confirms each one actually works, then move back here. Fullbright was tested
        // and confirmed working 2026-09-14 and has already moved back out of New (see DisplayTab).
        // Per killer560's explicit "cheat variant should have hitbox's auto etable and auto terms"
        // request (2026-09-10) - Full Block (hitbox expansion) moved from always-available to cheat-only
        // here, joining Auto Terminals; the legit build has neither tab at all, not just a
        // disabled-looking one (matching SecretsConfig#isMasterEnabled's own gate underneath).
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new SecretsTab());
            tabs.add(new AutoTerminalTab());
        }
        return tabs;
    }
}
