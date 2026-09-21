package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.FastLeapConfig.LeapGroup;
import com.killer560.hub.fastleap.FastLeapSection;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

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
 * Two views, same tab (2026-09-15, killer560: "I dont like the current leaps style. Make it so on the left
 * half it is the title of leaps, on the right half is an edit button. If you press the edit button then it
 * opens all the settings for that specific auto/fastleap"):
 * <ul>
 *     <li>the LIST - the "Fast Leap" general section, then one row per leap (name + ON/OFF on the left half,
 *     an "Edit" button on the right half);</li>
 *     <li>the EDITOR - "&lt; Back", then that one leap's own red header and every setting it has.</li>
 * </ul>
 * Which one is showing is plain tab state ({@link #editingLeap}) plus {@code requestRebuild}, deliberately NOT
 * a separate {@code Screen}: the mod menu's search box, scrolling and tab chrome all belong to {@code ModScreen}
 * and keep working as normal this way. {@code ModScreen} builds its tab list once and reuses it across every
 * open/close (so the selected tab, scroll position and search text survive reopening), which means this field
 * survives too - on purpose, it's the same "reopen where I left off" behaviour; it is only force-reset when the
 * state would be nonsense (cheat features off, or the Fast Leap master switch turned off).
 * <p>
 * Cheat-only, so the tab title and every section header are red ({@link SectionHeaders}).
 * <p>
 * Regrouped 2026-09-21 at killer560's request ("I hate the way fast leaps menu is currently set up. Please
 * redesign the setting menu for it" - no further detail given, a judgement call): the actual row-building lives
 * in {@link FastLeapSection}, whose own class doc has the full before/after reasoning, but the short version is
 * headed groups instead of one flat row of toggles, settings that only matter once their switch is on now
 * staying hidden until then, and the leap list now split under a red header per boss phase instead of one flat
 * 13-row list. This class only had to change {@link #matchesSearch}, which now asks {@link FastLeapSection} for
 * its {@code includeHidden} variants so a search still finds a setting that the normal view is hiding.
 */
public class FastLeapTab extends BaseTab {

    private static final int HEADER_H = 14;
    private static final int SECTION_GAP = 10;
    private static final int ROW = FastLeapSection.ROW;
    private static final int GAP = FastLeapSection.GAP;
    private static final int BACK_W = 76;

    /** null = showing the leap list; otherwise the leap whose settings page is open. */
    private LeapGroup editingLeap = null;

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
        if (editingLeap != null && !FastLeapSection.isEnabledSetting()) {
            // master switch went off (here or from anywhere else) - a single leap's page would be dead controls
            editingLeap = null;
        }
        if (editingLeap != null) {
            return buildEditor(editingLeap, contentX, contentY, contentWidth, requestRebuild);
        }
        return buildList(contentX, contentY, contentWidth, requestRebuild);
    }

    // ---------------------------------------------------------------- list view

    private List<AbstractWidget> buildList(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(header(contentX, y, contentWidth, "Fast Leap"));
        y += HEADER_H;
        y = FastLeapSection.buildGeneral(widgets, contentX, y, contentWidth, requestRebuild);
        if (!FastLeapSection.isEnabledSetting()) {
            return widgets;
        }
        y += SECTION_GAP;

        widgets.add(header(contentX, y, contentWidth, "Leaps"));
        y += HEADER_H;
        FastLeapSection.buildLeapList(widgets, contentX, y, contentWidth, group -> {
            editingLeap = group;
            requestRebuild.run();
        });
        return widgets;
    }

    // ---------------------------------------------------------------- one leap's settings page

    private List<AbstractWidget> buildEditor(LeapGroup group, int contentX, int contentY, int contentWidth,
                                             Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
            editingLeap = null;
            requestRebuild.run();
        }).bounds(contentX, y, Math.min(BACK_W, Math.max(1, contentWidth)), ROW).build());
        y += ROW + GAP + 2;

        widgets.add(header(contentX, y, contentWidth, group.label + " Leap"));
        y += HEADER_H;
        FastLeapSection.buildLeapEditor(widgets, contentX, y, contentWidth, group);
        return widgets;
    }

    // ---------------------------------------------------------------- search

    /** Search has to see EVERY leap's settings, not just whichever view happens to be open - otherwise
     *  typing "keywords" or "gate blown" would only find them while that one leap's page was already open
     *  (and the list page would hide the rest). So this scans the list view plus every leap's editor body,
     *  instead of the default {@link BaseTab#widgetsMatchSearch}, which only ever sees the current view.
     *  Same speculative build-and-throw-away as the default: only ever run from the search field's
     *  responder, never per frame. */
    @Override
    public boolean matchesSearch(String query) {
        if (query.isBlank() || nameMatches(query)) {
            return true;
        }
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        List<AbstractWidget> scan = new ArrayList<>();
        scan.add(header(0, 0, 200, "Fast Leap"));
        FastLeapSection.buildGeneral(scan, 0, 0, 200, () -> {}, true);
        scan.add(header(0, 0, 200, "Leaps"));
        FastLeapSection.buildLeapList(scan, 0, 0, 200, group -> {});
        for (LeapGroup group : LeapGroup.values()) {
            FastLeapSection.buildLeapEditor(scan, 0, 0, 200, group, true);
        }
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
