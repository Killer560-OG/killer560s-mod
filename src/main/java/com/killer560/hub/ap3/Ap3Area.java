package com.killer560.hub.ap3;

import com.killer560.hub.fastleap.Floor7Tracker.Phase;

import java.util.Locale;

/**
 * Where in the F7/M7 boss fight a chain belongs: a boss phase, plus the terminal section (S1-S5) when that phase is
 * P3. killer560 (2026-09-20): "For ap3 it should work in p1 and p2 and p3 and p4 and p5. Any part of boss phase it
 * should work in." - so a chain is recorded in one area and only ever runs there. P3 keeps its five sections because
 * the sections ARE the structure of P3; the other phases are one arena each, so the phase is the whole identity.
 * <p>
 * File keys are unchanged for P3 ({@code "S3"}, {@code "S3:MAGE"}) so every chains file written before this existed
 * still loads and still runs in P3; the other phases are keyed {@code "P1"}, {@code "P2"}, {@code "P4"}, {@code "P5"}.
 */
public record Ap3Area(Phase phase, int section) {

    public Ap3Area {
        if (phase == null || phase == Phase.UNKNOWN) {
            throw new IllegalArgumentException("area needs a boss phase");
        }
        if (phase == Phase.P3 ? (section < 1 || section > 5) : section != 0) {
            throw new IllegalArgumentException("bad section " + section + " for " + phase);
        }
    }

    /** P3 section 1-5. */
    public static Ap3Area p3(int section) {
        return new Ap3Area(Phase.P3, section);
    }

    /** A whole non-P3 phase; null for P3 (which needs a section) or UNKNOWN. */
    public static Ap3Area ofPhase(Phase phase) {
        if (phase == null || phase == Phase.UNKNOWN || phase == Phase.P3) {
            return null;
        }
        return new Ap3Area(phase, 0);
    }

    /** Validating factory: null instead of an exception for a bad combination (file input). */
    public static Ap3Area of(Phase phase, int section) {
        try {
            return new Ap3Area(phase, section);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isP3() {
        return phase == Phase.P3;
    }

    /** Stable file / chat key: {@code "S3"} for P3 sections (the pre-existing form), {@code "P1"} etc. otherwise. */
    public String key() {
        return isP3() ? "S" + section : phase.name();
    }

    /** What the player reads: the key is already the shortest honest name ({@code S3}, {@code P1}). */
    public String label() {
        return key();
    }

    /** Longer form for status lines: {@code "P3 S3"} / {@code "P1"}. */
    public String longLabel() {
        return isP3() ? "P3 S" + section : phase.name();
    }

    /**
     * The area encoded in a chain key ({@code "S3"}, {@code "s3:mage"}, {@code "P1"}, {@code "P4:TANK"}), or null when
     * it is not one. {@code "P3"} alone is not an area (P3 always needs its section).
     */
    public static Ap3Area parseKey(String key) {
        if (key == null) {
            return null;
        }
        String t = key.trim().toUpperCase(Locale.ROOT);
        int colon = t.indexOf(':');
        String s = colon < 0 ? t : t.substring(0, colon);
        if (s.length() != 2 || s.charAt(1) < '1' || s.charAt(1) > '5') {
            return null;
        }
        int n = s.charAt(1) - '0';
        if (s.charAt(0) == 'S') {
            return p3(n);
        }
        if (s.charAt(0) == 'P' && n != 3) {
            return ofPhase(Phase.valueOf("P" + n));
        }
        return null;
    }

    @Override
    public String toString() {
        return key();
    }
}
