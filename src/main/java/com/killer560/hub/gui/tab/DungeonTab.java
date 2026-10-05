package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Folder tab grouping dungeon-related features: RNG Meter, the Terminal Solver (moved in from Helpers,
 *  2026-09-09, per killer560's re-categorization request), and Termism (2026-09-09, killer560's
 *  terminal-practice request) on every build; Full Block/hitboxes and Auto Terminals (2026-09-10, cheat
 *  build only - per killer560's "cheat variant should have hitbox's auto etable and auto terms" request)
 *  are omitted from this list entirely on the legit build, which has neither feature at all, not just a
 *  disabled-looking version of them. Leap Message moved OUT of here (2026-09-14) into the consolidated
 *  {@code LeapMenuTab}, which then moved back in here whole (2026-09-16) once killer560 confirmed it. */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new RngMeterTab(),
                new TerminalSolverTab(),
                new TermismTab(),
                // Moved out of New 2026-09-16 at killer560's request ("you can move the leap menu tab
                // into dungeons") - the consolidated Leap Menu (leap order, custom leap overlay, leap
                // message) is confirmed working, so it lives with the rest of the dungeon features now.
                new LeapMenuTab(),
                // Moved out of New 2026-09-16 - it is a boss-fight feature and now gated to F7/M7 boss.
                new PosmsgTab(),
                // Not cheat-only: setting someone's class fixes the normal leap menu and every
                // class-coloured display too, not just AP3's leap nodes (killer560, 2026-09-16).
                new ClassOverridesTab(),
                // Moved out of New 2026-09-14 - killer560 confirmed Simon Says (solver + Auto Start/Solve)
                // working after real runs ("I think ss is now done").
                new SimonSaysTab(),
                // Moved out of New 2026-09-27, per killer560's own report that night: "some stuff looks
                // weird like mask invincibility being in its own sectino inside of party finder makes no
                // sence and I dont think that is somethign i asked you to doo." He's right - git log/
                // comments show no prior request to nest it under Party Finder or Dungeon Queue; it was
                // just the next plain top-level entry after DungeonQueueTab in New's accordion list, sitting
                // right below that tab's own "Party Finder Overlay" section, which reads as nesting even
                // though the code never nested it. This tab already earmarked Mask Invincibility (below,
                // in the comment) for the move once confirmed - moving it now, unconditionally, per his
                // request tonight, regardless of confirmation status. Behaviour untouched, presentation only.
                new MaskInvincibilityTab(),
                // Cheat build: Auto Simon Says and Auto Mask are inserted after their legit sections below.
                // Puzzle Solvers dissolved into New earlier 2026-09-20, then killer560 changed his mind after
                // testing: "put every puzzle solver back under one tab called Puzzle Solvers inside the
                // dungeon tab... separate from Auto Puzzles, but all solvers in one tab". Ships on both
                // builds - only Auto Puzzles below is cheat-only.
                new PuzzleSolversTab(),
                // Its own tab, out of Puzzles (killer560, 2026-09-21: "that is its own tab that should be called livid solver").
                new LividSolverTab(),
                // One home for everything secret-related (killer560, 2026-09-20): sound, waypoints,
                // etherwarp waypoints, and the cheat-only Secret Aura/Triggerbot/Lever Aura/Full Block,
                // which the folder gates internally so none of them exist in the legit jar.
                new SecretsTab()
        ));
        // Leap Menu, Fast Leap, Posmsg, Ability Timers, Dungeon Info, Mob ESP, Mapping, Etherwarp
        // (landed just before this session, 2026-09-13) and Simon Says, Tick Timers, Split Timers,
        // I4 Sensors, Live Map, Secret Waypoints, Mod Chat, Voice To Text, Proximity
        // Voice, and (cheat build) Auto Leap Out (built this session) all live in the "New" tab only
        // until killer560 confirms each one actually works, then move back here. Fullbright was tested
        // and confirmed working 2026-09-14 and has already moved back out of New (see DisplayTab). Mask
        // Invincibility moved back here 2026-09-27 (see above) ahead of the rest of this list, for the
        // presentation reason explained there - not because it was confirmed working.
        // Per killer560's explicit "cheat variant should have hitbox's auto etable and auto terms"
        // request (2026-09-10) - Full Block (hitbox expansion) moved from always-available to cheat-only
        // here, joining Auto Terminals; the legit build has neither tab at all, not just a
        // disabled-looking one (matching SecretsConfig#isMasterEnabled's own gate underneath).
        if (cheat) {
            // Each cheat half sits directly under its legit section, with its own red header (killer560,
            // 2026-10-04: "Move all the cheat stuff from Simon Says into its own red Simon Says portion. Move
            // auto mask into its own section as well.").
            tabs.add(indexOf(tabs, SimonSaysTab.class) + 1, new AutoSimonSaysTab());
            tabs.add(indexOf(tabs, MaskInvincibilityTab.class) + 1, new AutoMaskTab());
            // First in this block, so it sits right after the normal Secrets folder.
            tabs.add(new CheatSecretsTab());
            tabs.add(new AutoTerminalTab());
            // Auto Puzzles came here when the Puzzle Solvers category was first dissolved (killer560,
            // 2026-09-20), and stayed here - deliberately separate from the Puzzle Solvers folder above -
            // once that folder came back the same day. It is the cheat half - the solvers only show you
            // the answer, this plays the puzzle for you - so the legit build has no such section at all.
            tabs.add(new AutoPuzzlesTab());
        }
        return tabs;
    }

    private static int indexOf(List<BaseTab> tabs, Class<? extends BaseTab> type) {
        for (int i = 0; i < tabs.size(); i++) {
            if (type.isInstance(tabs.get(i))) {
                return i;
            }
        }
        return tabs.size() - 1;
    }
}
