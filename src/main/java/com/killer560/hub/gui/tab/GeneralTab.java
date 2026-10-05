package com.killer560.hub.gui.tab;

import java.util.List;

/** The General category. Was a single page (Auto Join Skyblock) until 2026-10-04, when killer560 asked to
 *  "Move etherwarp overlay into general": it is now a folder whose first section, {@link GeneralMainTab}, sits
 *  inline at the top the way Home's does, with Etherwarp Overlay below it as a dropdown. Etherwarp Overlay
 *  keeps its own name, so its "etherwarp overlay/..." tooltips and every saved setting are untouched. */
public class GeneralTab extends FolderTab {

    public GeneralTab() {
        super("General", List.of(
                new GeneralMainTab(),
                // Moved here from Helpers 2026-10-04.
                new EtherwarpOverlayTab()
        ));
        pinFirstSection();
    }
}
