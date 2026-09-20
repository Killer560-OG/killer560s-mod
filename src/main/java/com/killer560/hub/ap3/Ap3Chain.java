package com.killer560.hub.ap3;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.Floor7Tracker.Phase;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of {@link Ap3Node}s for one boss {@link Ap3Area} (a P3 section S1-S5, or a whole phase P1 / P2 /
 * P4 / P5) with an optional class filter, so a different chain can run depending on the class you are playing. An
 * area may have one class-less chain plus one chain per class; {@link Ap3Store#forArea(Ap3Area, DungeonClass)} picks
 * the class chain when it exists and falls back to the class-less one.
 */
public final class Ap3Chain {

    private final Ap3Area area;
    private final DungeonClass classFilter;
    private final List<Ap3Node> nodes = new ArrayList<>();

    public Ap3Chain(Ap3Area area, DungeonClass classFilter) {
        if (area == null) {
            throw new IllegalArgumentException("chain needs an area");
        }
        this.area = area;
        this.classFilter = classFilter;
    }

    public Ap3Area area() {
        return area;
    }

    public Phase phase() {
        return area.phase();
    }

    /** 1-5 for a P3 chain, 0 for every other phase. */
    public int section() {
        return area.section();
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

    /**
     * The 1-BASED number of {@code node} - the ONE number every player-visible surface shows for it: the world
     * label, {@code /ap3 list}, {@code /ap3 delete <n>}, the tab rows and the edit page. killer560: "the very
     * first node is 1 the second is 2 and so on". 0 when the node is not in this chain. Everything that prints a
     * node number goes through here or {@code index + 1} on this list, never a recomputed offset - this feature
     * has already had one 0/1-based mismatch found in review.
     */
    public int numberOf(Ap3Node node) {
        int i = nodes.indexOf(node);
        return i < 0 ? 0 : i + 1;
    }

    /** Whether {@code node} is one of this chain's nodes (by identity - a copy is a different node). */
    public boolean contains(Ap3Node node) {
        return node != null && nodes.indexOf(node) >= 0;
    }

    /** Stable file key: {@code "S3"}, {@code "S3:MAGE"}, {@code "P1"}, {@code "P4:TANK"}. */
    public String key() {
        return key(area, classFilter);
    }

    public static String key(Ap3Area area, DungeonClass classFilter) {
        return area.key() + (classFilter == null ? "" : ":" + classFilter.name());
    }

    /** Human label: {@code "S3"} / {@code "S3 (Mage)"} / {@code "P1"}. */
    public String label() {
        return label(area, classFilter);
    }

    public static String label(Ap3Area area, DungeonClass classFilter) {
        return area.label() + (classFilter == null ? "" : " (" + classFilter.displayName() + ")");
    }

    public boolean matches(Ap3Area area, DungeonClass classFilter) {
        return this.area.equals(area) && this.classFilter == classFilter;
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
