package com.killer560.hub.gui.tab;

import java.util.List;

/** Display category: the client-side visual options plus Window Layout, each as its own collapsible
 *  section like the rest of the mod's categories (killer560, 2026-09-15: "for the display tab put window
 *  layout under a tab like the rest of the mod features"). */
public class DisplayTab extends FolderTab {

    public DisplayTab() {
        super("Display", List.of(
                new DisplayOptionsTab(),
                new WindowLayoutTab()
        ));
    }
}
