package com.killer560.hub.gui.tab;

import java.util.List;

/** The Carnival section of Misc (killer560, 2026-11-14: "carnival should be its own section"): the legit Carnival cards as a
 *  titled run, like Secrets and the Mining groups. The cheat-only Carnival cards sit in the category's one cheat block. */
public class CarnivalGroup extends FolderTab {

    public CarnivalGroup() {
        super("Carnival", List.of(new CarnivalTrackerTab(), new FruitDiggingTab(), new ZombieShootoutTab(), new CatchAFishTab()));
    }
}
