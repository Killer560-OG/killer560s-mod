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
        // MenuCategories.cheatLast never touches Home): Mod & HUD, Click Sounds, Descriptions, Discord Rich Presence, Auto
        // Update. Menu Theme is built too but Home shows it as its own row, not a card. Testkit 519 holds the order.
        // Sound Effects (game sounds: hits, mob deaths, block breaks) moved out to Misc on 2026-10-20 (killer560: "move sound
        // effects out of the mod settings for stuff that pertains to things like mob kills and such"); see GeneralTab.
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new HomeMainTab(),
                new ClickSoundsTab(),
                new DescriptionsTab(),
                new DiscordRpcTab(),
                new AutoUpdateTab(),
                new MenuThemeTab(),
                // Auto Report Bans (to-do Doc "BAN LOGGER", 2026-11-01): after his ordered cards, in both builds; he asked for
                // a very noticeable switch, so it sits on Home rather than deep in Misc.
                new BanReportTab(),
                // Auto Crash Reports (killer560, 2026-11-04): next to Ban Reports, both builds, OFF by default.
                new CrashReportTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            tabs.add(new CorrectionAlarmTab());
            tabs.add(new StreamerModeTab());
        }
        return tabs;
    }
}
