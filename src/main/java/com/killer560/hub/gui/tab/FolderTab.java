package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.MenuRowWidget;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A top-level tab that's really a small folder of other tabs, shown as a stack of collapsible
 *  accordion sections (Skyblocker-style: click a header to expand/collapse it inline) instead of each
 *  sub-tab getting its own top-level sidebar slot. Multiple sections can be open at once - collapsing
 *  one doesn't affect the others. Redesigned (2026-09-07) from the earlier "mini sub-sidebar beside the
 *  content" layout as part of the black+orange GUI overhaul, per killer560's "similar to this one" (a
 *  screenshot of Skyblocker's own config, whose categories expand feature-by-feature this same way). */
public abstract class FolderTab extends BaseTab {

    private static final int HEADER_HEIGHT = 20;
    private static final int HEADER_GAP = 4;
    private static final int INDENT = 10;
    private static final int SECTION_GAP = 10;

    private final List<BaseTab> subTabs;
    // Empty by default so everything starts collapsed, matching the reference screenshot.
    private final Set<Integer> expanded = new HashSet<>();

    protected FolderTab(String name, List<BaseTab> subTabs) {
        super(name);
        this.subTabs = subTabs;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        for (int i = 0; i < subTabs.size(); i++) {
            int index = i;
            boolean isExpanded = expanded.contains(index);
            String arrow = isExpanded ? "▼ " : "▶ ";
            widgets.add(new MenuRowWidget(contentX, y, contentWidth, HEADER_HEIGHT,
                    arrow + subTabs.get(index).name, isExpanded, true, () -> {
                        if (!expanded.add(index)) {
                            expanded.remove(index);
                        }
                        requestRebuild.run();
                    }));
            y += HEADER_HEIGHT + HEADER_GAP;

            if (isExpanded) {
                List<AbstractWidget> subWidgets = subTabs.get(index)
                        .buildWidgets(contentX + INDENT, y, contentWidth - INDENT, requestRebuild);
                widgets.addAll(subWidgets);
                int bottom = y;
                for (AbstractWidget w : subWidgets) {
                    bottom = Math.max(bottom, w.getY() + w.getHeight());
                }
                y = bottom + SECTION_GAP;
            }
        }
        return widgets;
    }

    /** Matches if this folder's own name matches, OR any of its sub-tabs' names do - so this folder
     *  stays visible in the main sidebar as long as something inside it matches the search. */
    @Override
    public boolean matchesSearch(String query) {
        if (ownNameMatches(query)) {
            return true;
        }
        for (BaseTab sub : subTabs) {
            if (sub.matchesSearch(query)) {
                return true;
            }
        }
        return false;
    }

    /** Just this folder's own name, ignoring its sub-tabs - lets a caller tell whether a match came
     *  from the folder itself or had to come from one of its children. Deliberately calls
     *  {@link #nameMatches} rather than the full {@link #matchesSearch} - the latter would also scan
     *  this folder's own widgets (just its accordion headers, i.e. its sub-tabs' names again),
     *  redundant with the explicit per-child search below. */
    public boolean ownNameMatches(String query) {
        return !query.isBlank() && nameMatches(query);
    }

    /** @return whichever currently-EXPANDED sub-tab is actively listening for a keybind capture, or
     *  null if none are. Real bug found and fixed (2026-09-07): {@code ModScreen#keyPressed} used to
     *  only check the top-level selected tab for {@link KeyCaptureTab} - harmless while every
     *  {@code KeyCaptureTab} (just {@code ExperimentsTab} at the time) was itself top-level, but broke
     *  silently the moment Experiments moved inside a new {@code HelpersTab} folder during the
     *  category reshuffle, since a folder itself was never a {@code KeyCaptureTab}. */
    public KeyCaptureTab findListeningKeyCaptureTab() {
        for (int index : expanded) {
            if (subTabs.get(index) instanceof KeyCaptureTab captureTab && captureTab.isListeningForKey()) {
                return captureTab;
            }
        }
        return null;
    }
}
