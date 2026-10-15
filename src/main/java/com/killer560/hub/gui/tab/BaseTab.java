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

    /** Whether this whole tab only exists in the cheat jar (i.e. it's only ever added to a folder behind
     *  {@code BuildVariant.CHEAT_FEATURES_ENABLED}). Cheat-only tabs get their title drawn red wherever tab
     *  names are listed (main sidebar, {@link FolderTab} accordion headers) - 2026-09-15 rule, see
     *  {@link com.killer560.hub.gui.SectionHeaders}. Override to return true; the name string itself stays
     *  plain so search matching and tooltips are unaffected. */
    public boolean isCheatOnly() {
        return false;
    }

    /**
     * The menu card's switch (Option A menu, 2026-10-12): which of the widgets this tab just built is its master on/off,
     * or null for a tab with none (the card then has no switch). The default takes the tab's first control, in reading
     * order, when that control is an ON/OFF button; failing that, an ON/OFF button labelled with the tab's own name or
     * "Enabled". Override where that picks the wrong one.
     */
    public com.killer560.hub.gui.SettingsButtonWidget masterToggle(List<AbstractWidget> built) {
        AbstractWidget first = null;
        for (AbstractWidget w : built) {
            if (w instanceof net.minecraft.client.gui.components.StringWidget || !w.visible) {
                continue;
            }
            if (first == null || w.getY() < first.getY() || (w.getY() == first.getY() && w.getX() < first.getX())) {
                first = w;
            }
        }
        if (first instanceof com.killer560.hub.gui.SettingsButtonWidget b && b.isSwitch()) {
            return b;
        }
        for (AbstractWidget w : built) {
            if (w instanceof com.killer560.hub.gui.SettingsButtonWidget b && b.isSwitch()) {
                String l = b.parts().label();
                if (l.equalsIgnoreCase(name) || l.equalsIgnoreCase("Enabled") || l.equalsIgnoreCase(name + " Enabled")) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * The independent ON/OFF rows the card's switch stands for. One row for an ordinary tab (its {@link #masterToggle});
     * a tab that is really several features side by side lists each feature's own row here, and its switch is ON while
     * any of them is on ({@link com.killer560.hub.gui.MasterSwitch}). {@link #masterToggle} should still return the first
     * of them (the default does: the tab's first control).
     */
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        com.killer560.hub.gui.SettingsButtonWidget m = masterToggle(built);
        return m == null ? List.of() : List.of(m);
    }

    /** The row in {@code built} whose label (formatting stripped, cut at its ':') is {@code label}, if it is an ON/OFF
     *  switch. For {@link #masterToggles} overrides. */
    protected static com.killer560.hub.gui.SettingsButtonWidget switchRow(List<AbstractWidget> built, String label) {
        for (AbstractWidget w : built) {
            if (w instanceof com.killer560.hub.gui.SettingsButtonWidget b && b.isSwitch()
                    && b.parts().label().equalsIgnoreCase(label)) {
                return b;
            }
        }
        return null;
    }

    /** The labels (formatting stripped, cut at their ':') of this tab's rows that contain {@code query}, for the menu
     *  search's setting hits. Built off-screen like {@link #widgetsMatchSearch}; a tab that scans with hidden sections
     *  laid open overrides this the same way. */
    public List<String> searchHits(String query) {
        String q = query.toLowerCase(Locale.US).trim();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (q.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        for (AbstractWidget widget : buildWidgets(0, 0, 200, () -> {})) {
            String text = net.minecraft.ChatFormatting.stripFormatting(widget.getMessage().getString());
            if (text == null) {
                continue;
            }
            text = text.trim();
            if (!text.isEmpty() && (text.charAt(0) == '▶' || text.charAt(0) == '▼')) {
                text = text.substring(1).trim();
            }
            int colon = text.indexOf(':');
            String label = colon > 0 ? text.substring(0, colon).trim() : text;
            if (!label.isEmpty() && text.toLowerCase(Locale.US).contains(q)) {
                out.add(label);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** The menu card's one-line description, or null to take it from the master toggle's tooltip (the default). */
    public String cardDescription() {
        return null;
    }

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
            // Strip § codes first so a styled header (e.g. SectionHeaders' "§c§l" prefix, or a mid-string
            // colour code) never breaks a match that spans the formatting.
            String text = net.minecraft.ChatFormatting.stripFormatting(widget.getMessage().getString());
            if (text != null && text.toLowerCase(Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }
}
