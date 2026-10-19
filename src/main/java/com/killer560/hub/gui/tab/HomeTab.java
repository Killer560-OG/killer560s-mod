package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** Landing category: the mod/HUD actions plus Discord Rich Presence, each as its own collapsible section
 *  like the rest of the menu (killer560, 2026-09-16: Discord RPC moved out of the New tab once it worked).
 *  Cards go in a fixed order (see buildTabs). The cheat build adds the Correction Alarm as the LAST section, below every legit one (2026-10-07). */
public class HomeTab extends FolderTab {

    public HomeTab() {
        super("Home", buildTabs());
        // Mod & HUD is what you open Home FOR - it sits inline at the top instead of behind a dropdown
        // you have to click every time (killer560, 2026-09-16).
        pinAllSections();
    }

    private static List<BaseTab> buildTabs() {
        // killer560, 2026-10-15: Home's cards are in THIS order, not alphabetical (the alphabetical rule of
        // MenuCategories.cheatLast never touches Home): Mod & HUD, Click Sounds, Sound Effects (2026-10-18), Descriptions,
        // Discord Rich Presence, Auto Update. Menu Theme is built too but Home shows it as its own row, not a card. Testkit 519 holds the order.
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new HomeMainTab(),
                new ClickSoundsTab(),
                new SoundEffectsTab(),
                new DescriptionsTab(),
                new DiscordRpcTab(),
                new AutoUpdateTab(),
                new MenuThemeTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new CorrectionAlarmTab());
            tabs.add(new StreamerModeTab());
        }
        return tabs;
    }
}
