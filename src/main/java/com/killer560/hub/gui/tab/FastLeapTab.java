package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.FastLeapSection;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;

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
 * Every leap's settings sit in the list itself since 2026-10-13 (killer560: "remove the edit buttons and instead have
 * a toggle then a button to select what class/name it should leap to") - see {@link FastLeapSection#buildLeapList}.
 * <p>
 * Cheat-only, so the tab title and every section header are red ({@link SectionHeaders}).
 * <p>
 * Regrouped 2026-09-21 at killer560's request ("I hate the way fast leaps menu is currently set up. Please
 * redesign the setting menu for it" - no further detail given, a judgment call): the actual row-building lives
 * in {@link FastLeapSection}, whose own class doc has the full before/after reasoning, but the short version is
 * headed groups instead of one flat row of toggles, settings that only matter once their switch is on now
 * staying hidden until then, and the leap list now split under a red header per boss phase instead of one flat
 * 13-row list. This class only had to change {@link #matchesSearch}, which now asks {@link FastLeapSection} for
 * its {@code includeHidden} variants so a search still finds a setting that the normal view is hiding.
 */
public class FastLeapTab extends BaseTab {

    private static final int HEADER_H = 14;
    private static final int SECTION_GAP = 10;

    public FastLeapTab() {
        super("Fast/Auto Leap");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return new ArrayList<>();
        }
        return buildList(contentX, contentY, contentWidth, requestRebuild);
    }

    // ---------------------------------------------------------------- list view

    private List<AbstractWidget> buildList(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        // The Fast Leap switch is the tab's first row: no header above it.
        y = FastLeapSection.buildGeneral(widgets, contentX, y, contentWidth, requestRebuild);
        if (!FastLeapSection.isEnabledSetting()) {
            return widgets;
        }
        y += SECTION_GAP;

        widgets.add(header(contentX, y, contentWidth, "Leaps"));
        y += HEADER_H;
        FastLeapSection.buildLeapList(widgets, contentX, y, contentWidth, requestRebuild);
        return widgets;
    }

    // ---------------------------------------------------------------- search

    /** Search sees the settings the master switch or Block Inputs hide, by building them off screen. Only ever run
     *  from the search field's responder, never per frame. */
    @Override
    public boolean matchesSearch(String query) {
        if (query.isBlank() || nameMatches(query)) {
            return true;
        }
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        List<AbstractWidget> scan = new ArrayList<>();
        FastLeapSection.buildGeneral(scan, 0, 0, 200, () -> {}, true);
        scan.add(header(0, 0, 200, "Leaps"));
        FastLeapSection.buildLeapList(scan, 0, 0, 400, () -> {});
        String q = query.toLowerCase(Locale.US);
        for (AbstractWidget widget : scan) {
            String text = ChatFormatting.stripFormatting(widget.getMessage().getString());
            if (text != null && text.toLowerCase(Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private static StringWidget header(int x, int y, int width, String title) {
        return new StringWidget(x, y, width, 12, SectionHeaders.header(title, true), Minecraft.getInstance().font);
    }
}
