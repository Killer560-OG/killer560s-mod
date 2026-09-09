package com.killer560.hub.terminals;

import java.util.regex.Pattern;

/** The Floor 7 dungeon terminal minigames this solver covers, with the exact GUI title Hypixel opens
 *  for each - confirmed by decompiling Odin's own {@code TerminalTypes} enum (its real title regexes,
 *  byte-for-byte). Melody IS a real container GUI (confirmed by killer560's own screenshot, 2026-09-09,
 *  matching this exact regex) but isn't actually solved here - {@link TerminalSolverFeature} only hides
 *  its player-inventory rows and recenters it, no highlight logic. */
public enum TerminalType {

    PANES("Panes", "^Correct all the panes!$"),
    RUBIX("Rubix", "^Change all to same color!$"),
    NUMBERS("Numbers", "^Click in order!$"),
    STARTS_WITH("Starts With", "^What starts with: '(\\w)'\\?$"),
    SELECT("Select", "^Select all the ([\\w ]+) items!$"),
    MELODY("Melody", "^Click the button on time!$");

    private final String displayName;
    private final Pattern titlePattern;

    TerminalType(String displayName, String titleRegex) {
        this.displayName = displayName;
        this.titlePattern = Pattern.compile(titleRegex);
    }

    public String displayName() {
        return displayName;
    }

    public Pattern titlePattern() {
        return titlePattern;
    }
}
