package com.killer560.hub.gui.profit;

import com.killer560.hub.croesus.CroesusProfitLog;
import com.killer560.hub.croesus.CroesusTrackerScreen;
import com.killer560.hub.experiments.ExperimentsProfitTracker;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every profit tracker the mod has, in one place: what {@code /profit} lists, what it completes, what the hub
 * draws a card for, and which screen each one opens. A new tracker is one new constant here (plus its screen).
 *
 * <p>The first word of {@link #words} is the name the hub card shows as its command; the rest are aliases
 * ({@code /profit dungeon} and {@code /profit croesus} open the same screen). Matching ignores case.
 */
public enum ProfitTracker {

    CROESUS("Dungeon Profit", "Croesus dungeon chests", List.of("croesus", "dungeon"),
            parent -> new CroesusTrackerScreen(parent, CroesusTrackerScreen.View.OVERVIEW),
            ProfitTracker::croesusSummary),
    EXPERIMENTS("Experimentation Table", "Superpairs, Chronomatron, Ultrasequencer", List.of("etable", "experiments"),
            ExperimentsProfitScreen::new, ProfitTracker::experimentsSummary),
    /**
     * Cheat build only (killer560, 2026-10-16: "only have it show on cheat version"). Every string and the screen and log
     * references sit behind {@code CHEAT_FEATURES_ENABLED ? ... : ...}, which javac folds, so a legit jar's copy of this
     * constant has an empty name, no words, and no reference to the Bazaar Flipper at all; {@link #available()} skips it.
     */
    BAZAAR_FLIP(com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? "Bazaar Flipper" : "",
            com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? "Bazaar instant buys sold to the NPC" : "",
            com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? List.of("bazaarflip", "bzflip") : List.of(""),
            com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? BazaarFlipProfitScreen::new : parent -> parent,
            com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? ProfitTracker::bazaarFlipSummary
                    : () -> new Summary("", ProfitPanels.DIM));
    // MINING ("Mining Profit") and NUCLEUS ("Nucleus Runs") are shelved until after 2.0 with the rest of the mining
    // features - shelved/mining/README.md has both constants and their summary methods to put back.

    /** One line under a card's name: the text, and the colour it is drawn in. */
    public record Summary(String text, int color) {
    }

    public final String label;
    public final String blurb;
    public final List<String> words;
    private final Function<Screen, Screen> opener;
    private final Supplier<Summary> summary;

    ProfitTracker(String label, String blurb, List<String> words, Function<Screen, Screen> opener,
                  Supplier<Summary> summary) {
        this.label = label;
        this.blurb = blurb;
        this.words = words;
        this.opener = opener;
        this.summary = summary;
    }

    /** The name shown on the card and in "/profit &lt;name&gt;". */
    public String command() {
        return words.get(0);
    }

    /** This tracker's screen; {@code parent} is where Back / Escape returns to (the hub, or null for the game). */
    public Screen open(Screen parent) {
        return opener.apply(parent);
    }

    public Summary summary() {
        try {
            return summary.get();
        } catch (RuntimeException e) {
            return new Summary("unavailable", ProfitPanels.DIM);
        }
    }

    /** The trackers this build shows: every one, minus the Bazaar Flipper's in a legit jar. */
    public static List<ProfitTracker> available() {
        List<ProfitTracker> out = new ArrayList<>();
        for (ProfitTracker t : values()) {
            if (t != BAZAAR_FLIP || com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                out.add(t);
            }
        }
        return out;
    }

    /** The tracker a typed word names, or null. */
    public static ProfitTracker byWord(String word) {
        if (word == null) {
            return null;
        }
        String w = word.toLowerCase(Locale.ROOT);
        for (ProfitTracker t : available()) {
            if (t.words.contains(w)) {
                return t;
            }
        }
        return null;
    }

    /** Every accepted word, in display order: what tab completion offers and what an unknown name is told. */
    public static List<String> allWords() {
        List<String> out = new ArrayList<>();
        for (ProfitTracker t : available()) {
            out.addAll(t.words);
        }
        return out;
    }

    private static Summary bazaarFlipSummary() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return new Summary("", ProfitPanels.DIM);
        }
        com.killer560.hub.bazaarflip.BazaarFlipProfitLog.Totals t = com.killer560.hub.bazaarflip.BazaarFlipProfitLog
                .totals(com.killer560.hub.bazaarflip.BazaarFlipProfitLog.scope());
        if (t.cycles() == 0) {
            return new Summary("No flips finished yet", ProfitPanels.DIM);
        }
        long net = Math.round(t.net());
        return new Summary(ProfitPanels.signedCoins(net) + " net, " + t.cycles() + " cycles",
                net >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD);
    }

    private static Summary croesusSummary() {
        long profit = 0;
        int chests = 0;
        for (Map.Entry<String, CroesusProfitLog.Totals> e : CroesusProfitLog.allTime().entrySet()) {
            if (CroesusProfitLog.ALL.equals(e.getKey())) {
                continue; // a roll-up of the rest
            }
            profit += e.getValue().profit;
            chests += e.getValue().chests;
        }
        if (chests == 0) {
            return new Summary("Nothing claimed yet", ProfitPanels.DIM);
        }
        return new Summary(ProfitPanels.signedCoins(profit) + " profit, " + chests + " chests",
                profit >= 0 ? ProfitPanels.GOOD : ProfitPanels.BAD);
    }

    private static Summary experimentsSummary() {
        long sessions = ExperimentsProfitTracker.getTotalSessions();
        if (sessions == 0) {
            return new Summary("No experiments logged yet", ProfitPanels.DIM);
        }
        return new Summary(ExperimentsProfitTracker.formatShort(ExperimentsProfitTracker.getTotalValueCoins())
                + " coins, " + sessions + " experiments", ProfitPanels.GOOD);
    }
}
