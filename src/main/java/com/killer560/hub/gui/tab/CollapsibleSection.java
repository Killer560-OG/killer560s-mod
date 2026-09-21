package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A section header that opens and closes its section - the row reads {@code ▶ Title} closed and {@code ▼ Title}
 * open (the same arrows {@link FolderTab}'s accordion and Live Map's "Room Colours" row use, and the prefix
 * {@code SettingTooltips} already strips, so the tooltip key stays the plain title). Whoever calls it owns the
 * open/closed flag - keep it in the feature's config when it should survive a restart - and adds the section's
 * widgets only when {@code open} is true. Shared so no tab grows its own copy (killer560, 2026-09-21: the AP3
 * Colors and Keybinds sections).
 */
public final class CollapsibleSection {

    private static final int ROW = 18;
    private static final int GAP = 4;

    private CollapsibleSection() {
    }

    /**
     * Adds the header row at {@code (x, y)} and returns the y just below it. {@code cheatOnly} draws the title with
     * the red cheat header treatment ({@link SectionHeaders}), else the amber one. {@code onToggle} is called on a
     * click; it should flip the flag, persist it if it persists, and rebuild the tab.
     */
    public static int header(List<AbstractWidget> widgets, int x, int y, int width, String title, boolean cheatOnly,
                             boolean open, Runnable onToggle) {
        String text = (open ? "▼ " : "▶ ") + SectionHeaders.prefix(cheatOnly) + title;
        widgets.add(SettingsButtonWidget.builder(Component.literal(text), btn -> onToggle.run())
                .bounds(x, y + 4, Math.max(1, width), ROW).build());
        return y + 4 + ROW + GAP;
    }
}
