package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping the mod's on-screen decorative HUD elements: GIF Player and DVD - split out
 *  from their own top-level slots (2026-09-07) per killer560's re-categorization request. Renamed from
 *  "Random Hud Elements" to just "Hud Elements" the same day. */
public class HudElementsTab extends FolderTab {

    public HudElementsTab() {
        super("Hud Elements", List.of(
                new GifPlayerTab(),
                new DvdTab()
        ));
    }
}
