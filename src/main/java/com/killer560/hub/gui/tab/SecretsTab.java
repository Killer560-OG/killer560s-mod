package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Folder tab consolidating every dungeon-secrets-related setting into one place, per killer560's
 *  menu-structure request (2026-09-21): "Secret sound, messages, secret waypoints, etherwarp waypoints,
 *  Secret Aura, Secret Triggerbot and Lever Aura all in one place." Accordion sections, same pattern as
 *  {@link PuzzleSolversTab} (a {@link FolderTab} nested inside {@link DungeonTab}) - see that class and
 *  {@link FolderTab} itself for how a folder mixing legit and cheat-only sections keeps the cheat ones out
 *  of the legit jar: the cheat sub-tabs are only ever constructed inside the
 *  {@code BuildVariant.CHEAT_FEATURES_ENABLED} block below, exactly like {@link DungeonTab}'s own
 *  cheat-only children, so the legit build's tab list never contains their classes at all.
 *  <p>
 *  A class already named {@code SecretsTab} existed here before this change - the cheat-only "Full Block"
 *  hitbox-expansion tab (itself already renamed once, from "Secrets" to "Full Block", back on 2026-09-09).
 *  Reusing this class name for the new folder meant that tab needed to move: it is renamed to
 *  {@link FullBlockTab} and folded in below as this folder's last section rather than kept as a separate,
 *  same-topic "Secrets"-adjacent tab elsewhere - see {@link FullBlockTab}'s own class doc for why.
 *  <p>
 *  Secret Sound (+ whatever killer560 means by "messages" - see the staging notes' "Needs his answer"
 *  section, no such separate setting exists in the codebase to move) came out of {@code DungeonAlertsTab}
 *  into {@link SecretSoundTab}; Secret Waypoints, Etherwarp Waypoints, Secret Triggerbot and Lever Aura
 *  were already their own tabs and just move folders; Secret Aura came out of {@code CheatUtilsTab} into
 *  {@link SecretAuraTab}. None of the underlying config classes were split - every section still reads and
 *  writes the exact same JSON keys it always did. */
public class SecretsTab extends FolderTab {

    public SecretsTab() {
        super("Secrets", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new SecretSoundTab(),
                new SecretWaypointsTab(),
                new EtherwarpTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new SecretAuraTab());
            tabs.add(new SecretTriggerbotTab());
            tabs.add(new LeverAuraTab());
            tabs.add(new FullBlockTab());
        }
        return tabs;
    }
}
