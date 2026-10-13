package com.killer560.hub.gui.tab;

import java.util.List;

/**
 * One sidebar category of the Option A menu (2026-10-12): Dungeons, HUD, Chat, Items, Social, Cosmetics, Misc. Its
 * sections are feature tabs, each shown as its own card, plus the two groups killer560 asked to keep together (Secrets
 * and Puzzle Solvers), which the menu draws as a titled run of cards. Built by {@link MenuCategories}.
 */
public final class CategoryTab extends FolderTab {

    /** The sidebar icon (one glyph of the Minecraft font). */
    public final String icon;

    public CategoryTab(String name, String icon, List<BaseTab> sections) {
        super(name, sections);
        this.icon = icon;
    }
}
