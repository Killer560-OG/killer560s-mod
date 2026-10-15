package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;

import java.util.HashMap;
import java.util.Map;

/**
 * The one-line description on each feature card (killer560, 2026-10-15: "the descriptions are very short and do not have
 * ... to show the description is too long to fit"). Keyed by the tab class's simple name. Each must word-wrap into the card
 * at its narrowest ({@link FeatureCards#CARD_MIN_W}) without a cut: about 40 characters when the card's name takes one
 * line, 28 when it takes two. The long text is the hover tooltip ({@link FeatureCards#longDescription}). The testkit's
 * 1060 measures every card.
 */
final class CardBlurbs {

    private CardBlurbs() {
    }

    private static final Map<String, String> BY_TAB = new HashMap<>();

    static {
        CardBlurbsData.register(BY_TAB);
    }

    static String get(BaseTab tab) {
        return BY_TAB.get(tab.getClass().getSimpleName());
    }

    /** Every tab id with a blurb (read by the testkit). */
    static Map<String, String> all() {
        return java.util.Collections.unmodifiableMap(BY_TAB);
    }
}
