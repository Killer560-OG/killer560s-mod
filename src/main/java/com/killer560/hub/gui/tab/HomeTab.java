package com.killer560.hub.gui.tab;

import java.util.List;

/** Landing category: the mod/HUD actions plus Discord Rich Presence, each as its own collapsible section
 *  like the rest of the menu (killer560, 2026-09-16: Discord RPC moved out of the New tab once it worked). */
public class HomeTab extends FolderTab {

    public HomeTab() {
        super("Home", List.of(
                new HomeMainTab(),
                new DiscordRpcTab()
        ));
    }
}
