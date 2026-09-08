package com.killer560.hub.gui.tab;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.List;
import java.util.Locale;

public abstract class BaseTab {
    public final String name;

    protected BaseTab(String name) {
        this.name = name;
    }

    /**
     * Called each time this tab becomes active (or needs rebuilding) so it can build its widget
     * list. {@code requestRebuild} lets a widget's callback ask for the whole tab to be rebuilt
     * (e.g. switching between sub-pages), since widgets can't rebuild the screen themselves.
     */
    public abstract List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth,
                                                        Runnable requestRebuild);

    /** Whether this tab matches the given search text (blank query always matches). Checks this tab's
     *  own name AND (per killer560's report, 2026-09-08: search found top-level tabs by name and accordion
     *  sub-tabs by name, but not an individual SETTING buried inside a sub-tab) the actual labels of
     *  the widgets it builds - see {@link #widgetsMatchSearch}. {@link FolderTab} overrides this
     *  entirely to compose its own name with its sub-tabs' {@code matchesSearch} instead (so a folder
     *  doesn't disappear from the sidebar just because the match is one of its children), but each leaf
     *  sub-tab still gets this default behavior. */
    public boolean matchesSearch(String query) {
        return query.isBlank() || nameMatches(query) || widgetsMatchSearch(query);
    }

    /** Just this tab's own name, ignoring its widgets - used by {@link FolderTab#ownNameMatches} to
     *  tell whether a match came from the folder's own title specifically, as opposed to going through
     *  the full {@link #matchesSearch} (which would also scan the folder's own widgets - just its
     *  accordion headers, redundant with the explicit per-child search {@code FolderTab} already does). */
    protected boolean nameMatches(String query) {
        return name.toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US));
    }

    /** @return whether any widget this tab builds (buttons, sliders, edit boxes) has a label
     *  containing the query - built speculatively with dummy bounds/no-op rebuild callback purely to
     *  read each widget's {@code getMessage()} text, then discarded, never rendered. Only ever called
     *  from a rebuild triggered by the search field's own responder (see {@code ModScreen}), never
     *  every render frame, so building and throwing away a widget list here is cheap in practice
     *  despite not being free. Lets search reach an individual setting's own text without every tab
     *  needing to hand-maintain a separate keyword list. */
    protected boolean widgetsMatchSearch(String query) {
        String q = query.toLowerCase(Locale.US);
        for (AbstractWidget widget : buildWidgets(0, 0, 200, () -> {})) {
            if (widget.getMessage().getString().toLowerCase(Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }
}
