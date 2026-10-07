package com.killer560.hub.testing;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.FolderTab;
import com.killer560.hub.gui.tab.HomeTab;
import com.killer560.hub.gui.ModScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The testing build's menu (killer560, 2026-10-07): an "Untested" category at the top holding every feature tab that
 * is not marked tested, each one taken out of its normal category; a tab marked tested appears in its final category
 * instead. Home (which holds the HUD editor) is never touched. Only compiled into testing jars.
 */
public final class TestingMenu {

    public static final String UNTESTED = "Untested";
    public static final int MARK_ROW_HEIGHT = 18;

    /** Every feature tab of the normal menu (class simple name -> "Category > ... > Tab"), from the last
     *  {@link #arrange}, before anything was moved - what the testkit checks the Untested category against. */
    private static final Map<String, String> NORMAL_LEAVES = new java.util.LinkedHashMap<>();
    /** Leaf tab name -> the top-level category it lives in normally, for tooltip lookups made from Untested. */
    private static final Map<String, String> CATEGORY = new HashMap<>();

    private TestingMenu() {
    }

    /** {@code tabs} as a normal build would show them; returns the testing build's list. */
    public static List<BaseTab> arrange(List<BaseTab> tabs) {
        NORMAL_LEAVES.clear();
        for (BaseTab top : tabs) {
            leaves(top, "", NORMAL_LEAVES);
        }
        CATEGORY.clear();
        List<BaseTab> untested = new ArrayList<>();
        List<BaseTab> out = new ArrayList<>();
        for (BaseTab top : tabs) {
            if (top instanceof HomeTab) {
                out.add(top);
                continue;
            }
            record(top, top.name);
            if (top instanceof FolderTab folder) {
                if (prune(folder, untested)) {
                    out.add(top);
                }
            } else if (TestedFeatures.isTested(top)) {
                out.add(top);
            } else {
                untested.add(top);
            }
        }
        int at = 0;
        while (at < out.size() && out.get(at) instanceof HomeTab) {
            at++;
        }
        if (!untested.isEmpty()) {
            out.add(at, new UntestedTab(untested));
        }
        return out;
    }

    /** Takes every untested leaf out of {@code folder} (recursively) into {@code untested}; false if nothing is left. */
    private static boolean prune(FolderTab folder, List<BaseTab> untested) {
        List<BaseTab> keep = new ArrayList<>();
        for (BaseTab sub : folder.subTabs()) {
            if (sub instanceof FolderTab inner) {
                if (prune(inner, untested)) {
                    keep.add(sub);
                }
            } else if (TestedFeatures.isTested(sub)) {
                keep.add(sub);
            } else {
                untested.add(sub);
            }
        }
        folder.replaceSubTabs(keep);
        return !keep.isEmpty();
    }

    private static void record(BaseTab tab, String category) {
        CATEGORY.putIfAbsent(tab.name, category);
        if (tab instanceof FolderTab folder) {
            for (BaseTab sub : folder.subTabs()) {
                record(sub, category);
            }
        }
    }

    private static void leaves(BaseTab tab, String parent, Map<String, String> into) {
        String path = parent.isEmpty() ? tab.name : parent + " > " + tab.name;
        if (tab instanceof FolderTab folder) {
            for (BaseTab sub : folder.subTabs()) {
                leaves(sub, path, into);
            }
        } else {
            into.put(TestedFeatures.id(tab), path);
        }
    }

    public static Map<String, String> normalLeaves() {
        return java.util.Collections.unmodifiableMap(NORMAL_LEAVES);
    }

    /**
     * The top-level name a tooltip lookup should use: a row shown in Untested keeps the tooltips scoped to the category
     * it normally lives in ("dungeon/..."), so the testing menu shows exactly the text the normal one does.
     */
    public static String tooltipCategory(String selectedTop, String scope) {
        if (UNTESTED.equals(selectedTop) && scope != null) {
            String c = CATEGORY.get(scope);
            if (c != null) {
                return c;
            }
        }
        return selectedTop;
    }

    /** The small Mark tested / Mark untested row drawn above a feature tab's own rows in a testing build. */
    public static AbstractWidget markButton(BaseTab tab, int x, int y, int width) {
        boolean tested = TestedFeatures.isTested(tab);
        Component label = Component.literal(tested ? "§eMark untested" : "§aMark tested");
        return SettingsButtonWidget.builder(label, b -> {
            TestedFeatures.mark(tab, !tested);
            ModScreen.reloadTabs();
        }).bounds(x, y, Math.min(width, 110), MARK_ROW_HEIGHT - 4).build();
    }
}
