package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Everything new or changed this session, all in one place - per killer560's explicit request
 *  (2026-09-13) so he knows exactly what still needs real testing without hunting through the normal
 *  category tabs. Once a feature here is confirmed working for real, move its tab back to wherever it
 *  normally belongs (Dungeon/Chat/etc.) and remove it from this list - this tab is a temporary staging
 *  area, not a permanent home. */
public class NewTab extends FolderTab {

    public NewTab() {
        super("New", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new FullbrightTab(),
                new DungeonInfoTab(),
                new SimonSaysTab(),
                new TickTimersTab(),
                new SplitTimersTab(),
                new MaskInvincibilityTab(),
                new ModChatTab(),
                new VoiceToTextTab(),
                new I4SensorsTab(),
                new LiveMapTab(),
                new SecretWaypointsTab(),
                new ProximityVoiceTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new AutoLeapTab());
        }
        return tabs;
    }
}
