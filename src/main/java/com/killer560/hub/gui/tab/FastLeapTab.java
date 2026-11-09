package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.FastLeapSection;
import com.killer560.hub.fastleap.FastLeapSection.Page;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fast/Auto Leap - the QUOI AutoLeap port, split out of the Leap Menu tab into a tab of its own
 * (2026-09-15, killer560: "For fast leap put that in its own complete section outside of the leap menu.
 * Title it fast/auto leap. Then reformat it to look better."). The Leap Menu tab keeps the legit stuff:
 * Custom Leap Menu, Leap Order and Leap Message. The i4 leap stays in the Sharp Shooter (i4) tab, where it
 * sits next to the rest of the i4 device settings it depends on.
 * <p>
 * Three pages since 2026-11-02 (killer560: "have two buttons. one for universal and one to set class ... right now
 * everything feels very hard to read"): the front page (switch, Targets, the two page buttons, Target Mode, Click
 * Behavior, every leap's Off/Fast/Auto row) and the Universal Targets and Set Class Targets pages, each opened by its
 * button and left with "&lt; Back". {@link FastLeapSection} builds them and holds which one shows; every page starts with
 * the Fast Leap switch, so the card's switch ({@link BaseTab#masterToggle}'s default, the first control) is right on all
 * three. Search scans all three pages with their hidden rows laid open, and {@link #revealSetting} opens the page a hit
 * is on.
 * <p>
 * Cheat-only, so the tab title and every section header are red ({@link SectionHeaders}).
 */
public class FastLeapTab extends BaseTab {

    public FastLeapTab() {
        super("Fast/Auto Leap");
    }

    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Fast Leap");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        Page page = FastLeapSection.isEnabledSetting() ? FastLeapSection.page() : Page.FRONT;
        if (page != FastLeapSection.page()) {
            FastLeapSection.openPage(page);
        }
        build(widgets, page, contentX, contentY, contentWidth, requestRebuild, false);
        return widgets;
    }

    private static void build(List<AbstractWidget> widgets, Page page, int x, int y, int w, Runnable requestRebuild,
                              boolean includeHidden) {
        switch (page) {
            case UNIVERSAL -> {
                y = FastLeapSection.buildPageHeader(widgets, x, y, w, requestRebuild, Page.UNIVERSAL);
                FastLeapSection.buildUniversalTargets(widgets, x, y, w, includeHidden);
            }
            case CLASS -> {
                y = FastLeapSection.buildPageHeader(widgets, x, y, w, requestRebuild, Page.CLASS);
                FastLeapSection.buildClassTargets(widgets, x, y, w, requestRebuild);
            }
            default -> {
                // The Fast Leap switch is the tab's first row: no header above it.
                y = FastLeapSection.buildGeneral(widgets, x, y, w, requestRebuild, includeHidden);
                if (FastLeapSection.isEnabledSetting() || includeHidden) {
                    y = FastLeapSection.buildLeapSwitches(widgets, x, y, w);
                }
            }
        }
    }

    /** Search sees every page and the rows the switch or Block Inputs hide, by building them off screen. Only ever run
     *  from the search field's responder, never per frame. */
    @Override
    public boolean matchesSearch(String query) {
        if (query.isBlank() || nameMatches(query)) {
            return true;
        }
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        String q = query.toLowerCase(Locale.US);
        for (Page p : Page.values()) {
            for (AbstractWidget widget : searchScan(p)) {
                if (textOf(widget).toLowerCase(Locale.US).contains(q)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The setting hits come from the same off-screen scan as {@link #matchesSearch}, every page in turn. */
    @Override
    public List<String> searchHits(String query) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<AbstractWidget> all = new ArrayList<>();
        for (Page p : Page.values()) {
            all.addAll(searchScan(p));
        }
        return searchHitsIn(all, query);
    }

    @Override
    public List<AbstractWidget> searchScanWidgets() {
        List<AbstractWidget> all = new ArrayList<>();
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            for (Page p : Page.values()) {
                all.addAll(searchScan(p));
            }
        }
        return all;
    }

    /** A search hit is opened: show the page holding it (front first), so the menu can scroll to the row. */
    @Override
    public void revealSetting(String label) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || label == null || label.isBlank()) {
            return;
        }
        String want = label.toLowerCase(Locale.US).trim();
        // The row whose own label is the hit first ("Storm Death" is a Universal row, not the front's "Storm Death
        // Leap"), then any row containing it.
        for (boolean exact : new boolean[] {true, false}) {
            for (Page p : Page.values()) {
                for (AbstractWidget widget : searchScan(p)) {
                    String text = textOf(widget).toLowerCase(Locale.US).trim();
                    int colon = text.indexOf(':');
                    String own = colon > 0 ? text.substring(0, colon).trim() : text;
                    if (exact ? own.equals(want) : text.contains(want)) {
                        FastLeapSection.openPage(p);
                        return;
                    }
                }
            }
        }
    }

    /** Every row of one page, including the ones the master switch, Block Inputs or Target Mode hide. */
    private static List<AbstractWidget> searchScan(Page page) {
        List<AbstractWidget> scan = new ArrayList<>();
        build(scan, page, 0, 0, 400, () -> {}, true);
        return scan;
    }

    private static String textOf(AbstractWidget widget) {
        String text = ChatFormatting.stripFormatting(widget.getMessage().getString());
        return text == null ? "" : text;
    }
}
