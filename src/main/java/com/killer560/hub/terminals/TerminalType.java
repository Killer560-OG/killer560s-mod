package com.killer560.hub.terminals;

import java.util.regex.Pattern;

/** The Floor 7 dungeon terminal minigames this solver covers, with the exact GUI title Hypixel opens
 *  for each - confirmed by decompiling Odin's own {@code TerminalTypes} enum (its real title regexes,
 *  byte-for-byte). Melody (the in-world sound-order rhythm game) is a fundamentally different UI
 *  paradigm - not a simple container to highlight slots in - and isn't covered here yet. */
public enum TerminalType {

    PANES("Panes", "^Correct all the panes!$"),
    RUBIX("Rubix", "^Change all to same color!$"),
    NUMBERS("Numbers", "^Click in order!$"),
    STARTS_WITH("Starts With", "^What starts with: '(\\w)'\\?$"),
    SELECT("Select", "^Select all the ([\\w ]+) items!$");

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
