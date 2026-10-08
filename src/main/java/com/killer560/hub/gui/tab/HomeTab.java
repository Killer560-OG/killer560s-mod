package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Landing category: the mod/HUD actions plus Discord Rich Presence, each as its own collapsible section
 *  like the rest of the menu (killer560, 2026-09-16: Discord RPC moved out of the New tab once it worked).
 *  The cheat build adds the Correction Alarm as the LAST section, below every legit one (2026-10-07). */
public class HomeTab extends FolderTab {

    public HomeTab() {
        super("Home", buildTabs());
        // Mod & HUD is what you open Home FOR - it sits inline at the top instead of behind a dropdown
        // you have to click every time (killer560, 2026-09-16).
        pinAllSections();
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new HomeMainTab(),
                new DiscordRpcTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new CorrectionAlarmTab());
        }
        return tabs;
    }
}
