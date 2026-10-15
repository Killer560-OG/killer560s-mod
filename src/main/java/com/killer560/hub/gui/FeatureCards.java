package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.MenuCategories;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a feature card shows for a tab (Option A menu, 2026-10-12): its master switch and its one-line description.
 *
 * <p>Both come from the tab's own widgets, built off-screen the way the menu search always has ({@code BaseTab
 * #widgetsMatchSearch}): the switch is the button {@link BaseTab#masterToggle} picks, and pressing it on a card runs that
 * button's own {@code onPress} - the very code a click on the row in the tab runs - so a card can never do anything the
 * tab could not. The description is the master toggle's tooltip ({@link SettingTooltipsData}) cut to its first sentence,
 * else the tooltip the tab's own name had as an accordion header, else {@link FeatureDescriptions}.
 */
public final class FeatureCards {

    private FeatureCards() {
    }

    /** Off-screen width the master toggle is built at (the menu's drawer content is about this wide). */
    private static final int BUILD_WIDTH = 300;

    private static final Map<BaseTab, String> DESCRIPTIONS = new IdentityHashMap<>();
    /** Last known master state: TRUE / FALSE, or absent when the tab has no master toggle. */
    private static final Map<BaseTab, Boolean> STATE = new IdentityHashMap<>();
    private static final Map<BaseTab, Boolean> HAS_MASTER = new IdentityHashMap<>();

    /** Forget everything (the tab list was rebuilt). */
    public static void clear() {
        DESCRIPTIONS.clear();
        STATE.clear();
        HAS_MASTER.clear();
    }

    /** The tab's master toggle, freshly built with {@code requestRebuild} as its rebuild hook, or null. */
    public static SettingsButtonWidget master(BaseTab tab, Runnable requestRebuild) {
        List<AbstractWidget> built;
        try {
            built = tab.buildWidgets(0, 0, BUILD_WIDTH, requestRebuild == null ? () -> { } : requestRebuild);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            return tab.masterToggle(built);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The tab's switch (one master row, or several independent ones: {@link MasterSwitch}), freshly built, or null. */
    public static MasterSwitch masterSwitch(BaseTab tab, Runnable requestRebuild) {
        List<AbstractWidget> built;
        try {
            built = tab.buildWidgets(0, 0, BUILD_WIDTH, requestRebuild == null ? () -> { } : requestRebuild);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            return MasterSwitch.of(tab.masterToggles(built));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Re-reads the tab's master state; returns it (null = no master). */
    public static Boolean refresh(BaseTab tab) {
        MasterSwitch m = masterSwitch(tab, null);
        HAS_MASTER.put(tab, m != null);
        if (m == null) {
            STATE.remove(tab);
            return null;
        }
        STATE.put(tab, m.isOn());
        return m.isOn();
    }

    /** The last known master state (reads it once if never read); null = the tab has no master toggle. */
    public static Boolean state(BaseTab tab) {
        if (!HAS_MASTER.containsKey(tab)) {
            return refresh(tab);
        }
        return STATE.get(tab);
    }

    /** Record a state read elsewhere (the open drawer's own master row, read every frame). */
    public static void setKnownState(BaseTab tab, boolean on) {
        HAS_MASTER.put(tab, true);
        STATE.put(tab, on);
    }

    /** Presses the tab's master toggle (the same onPress a click on it in the tab runs), then re-reads the state. */
    public static Boolean toggle(BaseTab tab, Runnable requestRebuild) {
        MasterSwitch m = masterSwitch(tab, requestRebuild);
        if (m == null) {
            return null;
        }
        m.press();
        return refresh(tab);
    }

    /** The card's description line (cached per tab instance). */
    public static String description(BaseTab tab) {
        String d = DESCRIPTIONS.get(tab);
        if (d == null) {
            d = computeDescription(tab);
            DESCRIPTIONS.put(tab, d);
        }
        return d;
    }

    private static String computeDescription(BaseTab tab) {
        String own = tab.cardDescription();
        if (own != null && !own.isBlank()) {
            return own;
        }
        String category = MenuCategories.legacyCategory(tab);
        // A tab of several independent switches: its first row's tooltip would describe only that one.
        MasterSwitch several = masterSwitch(tab, null);
        if (several != null && several.parts().size() > 1) {
            String fixedSeveral = FeatureDescriptions.get(tab);
            if (fixedSeveral != null) {
                return fixedSeveral;
            }
        }
        SettingsButtonWidget m = master(tab, null);
        if (m != null) {
            String tip = SettingTooltips.describeScoped(tab.name, category, m.getMessage().getString());
            if (tip != null && !tip.isBlank()) {
                return firstSentence(tip);
            }
        }
        String fixed = FeatureDescriptions.get(tab);
        if (fixed != null) {
            return fixed;
        }
        String header = SettingTooltips.describeScoped(null, category, tab.name);
        if (header != null && !header.isBlank()) {
            return firstSentence(header);
        }
        return "";
    }

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s+[A-Z(\"'0-9])");
    private static final Pattern CHEAT_PREFIX = Pattern.compile("^(?i)cheat build( only)?[:,.]?\\s*");
    private static final Pattern CHEAT_TAG = Pattern.compile("\\s*\\((?i)cheat build[^)]*\\)");

    /** The first sentence of a tooltip (long enough to say something: a leading "!!" warning keeps its sentence),
     *  without the "Cheat build" tags (the card's red title already says it). */
    public static String firstSentence(String text) {
        String t = CHEAT_TAG.matcher(CHEAT_PREFIX.matcher(text.trim()).replaceFirst("")).replaceAll("");
        Matcher m = SENTENCE_END.matcher(t);
        int end = t.length();
        while (m.find()) {
            int at = m.start();
            String before = t.substring(Math.max(0, at - 4), at + 1).toLowerCase(java.util.Locale.ROOT);
            if (before.endsWith("e.g.") || before.endsWith("i.e.") || before.endsWith("vs.") || before.endsWith("etc.")) {
                continue;
            }
            if (t.substring(0, at + 1).replaceAll("[^A-Za-z]", "").length() < 20) {
                continue;
            }
            end = at + 1;
            break;
        }
        String s = t.substring(0, end).trim();
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }
}
