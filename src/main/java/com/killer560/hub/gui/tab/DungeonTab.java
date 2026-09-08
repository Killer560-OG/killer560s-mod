package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping dungeon-related features: RNG Meter and Leap Message. */
public class DungeonTab extends FolderTab {

    public DungeonTab() {
        super("Dungeon", List.of(
                new RngMeterTab(),
                new LeapMessageTab()
        ));
    }
}
