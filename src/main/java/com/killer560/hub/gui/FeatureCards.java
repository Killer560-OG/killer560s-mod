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

    /** A feature card's height, and the narrowest it is ever laid out (ModScreen draws every card at these or wider). */
    public static final int CARD_H = 66;
    public static final int CARD_MIN_W = 104;

    /**
     * The text of one card as ModScreen draws it: the name lines, then the description lines. {@code truncated} is true
     * when the description did not fit the card's space and would have been cut with "..." (the testkit requires none).
     */
    public record CardText(List<String> name, List<String> desc, boolean truncated) {
    }

    /** One card's last layout and what it was laid out for. */
    private record CachedText(int w, int h, boolean hasSwitch, boolean showMenu, int fontVersion, String description,
                              net.minecraft.client.gui.Font font, CardText text) {
    }

    private static final Map<BaseTab, CachedText> TEXT = new IdentityHashMap<>();

    /**
     * The one place a card's text is laid out: {@code drawCard} draws exactly this, and the testkit measures it.
     *
     * <p>Kept per tab until one of its inputs changes - the card's size, the switch, the Show In Menu setting, the
     * description, the font object or the Fonts setting (and every {@link #clear}, which the menu's {@code init} runs on
     * a resize or reload). Word-wrapping every visible card every frame was about a fifth of the menu's allocation
     * (95-perf-screens JFR, fps-gui-1015): {@code String.split} with a regex, a candidate string per word, a width each.
     */
    public static CardText cardText(BaseTab tab, int w, int h, boolean hasSwitch) {
        boolean show = DescriptionConfig.getInstance().menuShown();
        int fv = com.killer560.hub.fonts.FontConfig.version();
        net.minecraft.client.gui.Font font = MenuStyle.font();
        String desc = show ? description(tab) : null;
        CachedText c = TEXT.get(tab);
        if (c != null && c.w == w && c.h == h && c.hasSwitch == hasSwitch && c.showMenu == show && c.fontVersion == fv
                && c.font == font && java.util.Objects.equals(c.description, desc)) {
            return c.text;
        }
        CardText t = layoutCardText(tab, w, h, hasSwitch);
        TEXT.put(tab, new CachedText(w, h, hasSwitch, show, fv, desc, font, t));
        return t;
    }

    private static CardText layoutCardText(BaseTab tab, int w, int h, boolean hasSwitch) {
        int nameW = w - 14 - (hasSwitch ? MenuStyle.SWITCH_W + 4 : 0);
        List<String> name = MenuStyle.wrap(tab.name, nameW, 2);
        int budget = Math.max(0, (h - 14 - (6 + 10 * name.size() + 2)) / 10);
        if (!DescriptionConfig.getInstance().menuShown()) {
            return new CardText(name, List.of(), false);
        }
        String full = description(tab);
        List<String> desc = MenuStyle.wrap(full, w - 14, budget);
        return new CardText(name, desc, !MenuStyle.fits(full, w - 14, budget));
    }

    /** Off-screen width the master toggle is built at (the menu's drawer content is about this wide). */
    private static final int BUILD_WIDTH = 300;

    private static final Map<BaseTab, String> DESCRIPTIONS = new IdentityHashMap<>();
    /** Last known master state: TRUE / FALSE, or absent when the tab has no master toggle. */
    private static final Map<BaseTab, Boolean> STATE = new IdentityHashMap<>();
    private static final Map<BaseTab, Boolean> HAS_MASTER = new IdentityHashMap<>();

    /** Forget the cards' laid-out text (the menu's {@code init}: a resize, or a resource reload that may change glyphs). */
    public static void clearText() {
        TEXT.clear();
    }

    /** Forget everything (the tab list was rebuilt). */
    public static void clear() {
        TEXT.clear();
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

    /** Descriptions {@link #fitted} had to shorten itself (a blurb that did not fit its card): the testkit requires none. */
    private static final java.util.List<String> AUTO_CUT = new java.util.ArrayList<>();

    /** The tabs whose description had to be cut by {@link #fitted} (should be empty: write a shorter blurb). */
    public static java.util.List<String> autoCut() {
        return java.util.Collections.unmodifiableList(AUTO_CUT);
    }

    /** The most description lines a card of this tab has at its narrowest. */
    private static int worstBudget(BaseTab tab) {
        int nameW = CARD_MIN_W - 14 - (state(tab) != null ? MenuStyle.SWITCH_W + 4 : 0);
        return Math.max(0, (CARD_H - 14 - (6 + 10 * MenuStyle.wrap(tab.name, nameW, 2).size() + 2)) / 10);
    }

    /** {@code text} unchanged when it fits the tab's narrowest card, else cut back to whole words that do (never "..."). */
    private static String fitted(BaseTab tab, String text) {
        int w = CARD_MIN_W - 14;
        int lines = worstBudget(tab);
        if (text == null || MenuStyle.fits(text, w, lines)) {
            return text;
        }
        String[] words = text.trim().split("\\s+");
        int n = words.length;
        while (n > 1 && !MenuStyle.fits(String.join(" ", java.util.Arrays.copyOf(words, n)), w, lines)) {
            n--;
        }
        AUTO_CUT.add(tab.name);
        return String.join(" ", java.util.Arrays.copyOf(words, n));
    }

    /**
     * The longer text a card shows on hover: the master toggle's full tooltip (or the tab's own), when there is more to
     * say than the card's line. Null when the card already says it all.
     */
    public static String longDescription(BaseTab tab) {
        String shortText = description(tab);
        String full = null;
        String category = MenuCategories.legacyCategory(tab);
        SettingsButtonWidget m = master(tab, null);
        if (m != null) {
            full = SettingTooltips.describeScoped(tab.name, category, m.getMessage().getString());
        }
        if (full == null || full.isBlank()) {
            full = SettingTooltips.describeScoped(null, category, tab.name);
        }
        if (full == null || full.isBlank()) {
            full = FeatureDescriptions.get(tab);
        }
        if (full == null || full.isBlank() || full.trim().equals(shortText == null ? "" : shortText.trim())) {
            return null;
        }
        return full;
    }

    private static String computeDescription(BaseTab tab) {
        String own = tab.cardDescription();
        if (own != null && !own.isBlank()) {
            return fitted(tab, own);
        }
        String blurb = CardBlurbs.get(tab);
        if (blurb != null) {
            return fitted(tab, blurb);
        }
        return fitted(tab, rawDescription(tab));
    }

    /** The description before any fitting (the testkit dumps it so blurbs can be written). */
    public static String rawDescription(BaseTab tab) {
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
