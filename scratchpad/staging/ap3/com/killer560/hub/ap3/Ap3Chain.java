package com.killer560.hub.ap3;

import com.killer560.hub.dungeonclass.DungeonClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An ordered list of {@link Ap3Node}s for one P3 section (S1-S5, {@code Floor7Tracker.Stage.number}) with an
 * optional class filter, so a different chain can run depending on the class you are playing. A section may have
 * one class-less chain plus one chain per class; {@link Ap3Store#forSection(int, DungeonClass)} picks the class
 * chain when it exists and falls back to the class-less one.
 */
public final class Ap3Chain {

    private final int section;
    private final DungeonClass classFilter;
    private final List<Ap3Node> nodes = new ArrayList<>();

    public Ap3Chain(int section, DungeonClass classFilter) {
        this.section = section;
        this.classFilter = classFilter;
    }

    /** 1-5. */
    public int section() {
        return section;
    }

    /** null = any class. */
    public DungeonClass classFilter() {
        return classFilter;
    }

    /** The live node list - callers that mutate it must {@code Ap3Store.getInstance().save()} afterwards. */
    public List<Ap3Node> nodes() {
        return nodes;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int indexOf(Ap3Node node) {
        return nodes.indexOf(node);
    }

    /** Stable file key: {@code "S3"} or {@code "S3:MAGE"}. */
    public String key() {
        return key(section, classFilter);
    }

    public static String key(int section, DungeonClass classFilter) {
        return "S" + section + (classFilter == null ? "" : ":" + classFilter.name());
    }

    /** Human label: {@code "S3"} / {@code "S3 (Mage)"}. */
    public String label() {
        return "S" + section + (classFilter == null ? "" : " (" + classFilter.displayName() + ")");
    }

    public boolean matches(int section, DungeonClass classFilter) {
        return this.section == section && this.classFilter == classFilter;
    }

    /** The section number encoded in a {@link #key()} ({@code "S3"} / {@code "s3:mage"}), or 0 when it is not one. */
    public static int parseSection(String key) {
        if (key == null) {
            return 0;
        }
        String t = key.trim().toUpperCase(Locale.ROOT);
        int colon = t.indexOf(':');
        String s = colon < 0 ? t : t.substring(0, colon);
        if (s.length() != 2 || s.charAt(0) != 'S' || s.charAt(1) < '1' || s.charAt(1) > '5') {
            return 0;
        }
        return s.charAt(1) - '0';
    }

    /** The class filter encoded in a {@link #key()}, or null for a class-less key / an unknown class name. */
    public static DungeonClass parseClassFilter(String key) {
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        return colon < 0 ? null : DungeonClass.byName(key.substring(colon + 1).trim());
    }
}
