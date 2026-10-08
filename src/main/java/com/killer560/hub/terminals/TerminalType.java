package com.killer560.hub.terminals;

import java.util.regex.Pattern;

/** The Floor 7 dungeon terminal minigames this solver covers, with the exact GUI title Hypixel opens
 *  for each - confirmed by decompiling Odin's own {@code TerminalTypes} enum (its real title regexes,
 *  byte-for-byte). Melody IS a real container GUI (confirmed by killer560's own screenshot, 2026-09-09,
 *  matching this exact regex) but isn't actually solved here - {@link TerminalSolverFeature} only hides
 *  its player-inventory rows and recenters it, no highlight logic.
 *
 *  <p>Re-checked 2026-10-08 after "all terminal solvers and auto terminal is broken": the six titles are unchanged in
 *  the current source of Odin (TerminalTypes.kt), NoammAddons, Skyblocker, Devonian and jcnlk's quoi (Dungeon.kt). Every
 *  matcher in the mod goes through {@link #match} / {@link #normalizeTitle}, which drops colour codes and the
 *  private-use / formatting glyphs a resource-pack GUI title carries, so a decorated title still matches. */
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

    /**
     * A container title as the terminal regexes read it: §-codes removed, then every private-use, formatting,
     * surrogate or control character (the glyphs Hypixel's resource pack draws GUI backgrounds with), then the ends
     * trimmed. Plain text is returned unchanged.
     */
    public static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean drop;
            if (c == '§') {
                drop = true;
                if (i + 1 < raw.length()) {
                    i++;
                    if (out == null) {
                        out = new StringBuilder(raw.length()).append(raw, 0, i - 1);
                    }
                    continue;
                }
            } else {
                int type = Character.getType(c);
                drop = type == Character.PRIVATE_USE || type == Character.FORMAT || type == Character.SURROGATE
                        || type == Character.CONTROL || type == Character.UNASSIGNED;
            }
            if (drop) {
                if (out == null) {
                    out = new StringBuilder(raw.length()).append(raw, 0, i);
                }
            } else if (out != null) {
                out.append(c);
            }
        }
        return (out == null ? raw : out.toString()).trim();
    }

    /** The terminal a container title belongs to, or null. {@code title} may be raw; it is normalized here. */
    public static TerminalType match(String title) {
        String plain = normalizeTitle(title);
        for (TerminalType type : values()) {
            if (type.titlePattern.matcher(plain).matches()) {
                return type;
            }
        }
        return null;
    }
}
