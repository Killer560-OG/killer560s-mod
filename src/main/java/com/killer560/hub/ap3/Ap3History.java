package com.killer560.hub.ap3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@code /ap3 undo} and {@code /ap3 redo}'s history: every add, delete, move, edit and clear, newest first, whichever
 * area it was in. Session-only, like the single "last added node" it replaces.
 * <p>
 * killer560, 2026-10-04: "for auto routes and ap3 add a /ap3 or /ar redo where it is the opposite of undo and will
 * restore things I just deleted or undid." AP3's undo used to remember exactly one node - the last one ADDED - so
 * a delete, an editor change or a clear could not be taken back at all. This is Auto Routes' {@code RouteHistory}
 * for chains: each entry knows how to apply itself once and hands back its own inverse, which goes on the other
 * stack, so undo feeds redo and redo feeds undo. A NEW change empties the redo stack.
 * <p>
 * Entries hold the live {@link Ap3Chain} and {@link Ap3Node} objects, so an undone delete puts back the very node,
 * every modifier on it intact. A chain emptied by its last delete leaves the store ({@link Ap3Feature#deleteNode}
 * removes empty chains) and is put back by the undo; a chain that has since been replaced by a NEW chain under the
 * same area and class, or that was cleared while it still had nodes, is stale and the entry is skipped.
 */
final class Ap3History {

    static final int MAX = 100;

    private sealed interface Entry permits Added, Removed, Moved, Changed, Cleared, Recleared {
        Ap3Chain chain();
    }

    /** {@code node} is in the chain; applying takes it out. */
    private record Added(Ap3Chain chain, Ap3Node node) implements Entry {
    }

    /** {@code node} is out of the chain; applying puts it back at {@code index}. */
    private record Removed(Ap3Chain chain, Ap3Node node, int index) implements Entry {
    }

    /** {@code node} was moved; applying puts it back at {@code index}. */
    private record Moved(Ap3Chain chain, Ap3Node node, int index) implements Entry {
    }

    /** Any field of {@code node}; applying puts every field back to {@code before}. */
    private record Changed(Ap3Chain chain, Ap3Node node, Ap3Node before) implements Entry {
    }

    /** The whole chain is out of the store ({@code /ap3 clear}); applying puts it back. */
    private record Cleared(Ap3Chain chain) implements Entry {
    }

    /** The chain is back after an undone clear; applying clears it again. */
    private record Recleared(Ap3Chain chain) implements Entry {
    }

    private static final Deque<Entry> undo = new ArrayDeque<>();
    private static final Deque<Entry> redo = new ArrayDeque<>();

    private Ap3History() {
    }

    static void added(Ap3Chain chain, Ap3Node node) {
        record(new Added(chain, node));
    }

    static void removed(Ap3Chain chain, Ap3Node node, int index) {
        record(new Removed(chain, node, index));
    }

    static void moved(Ap3Chain chain, Ap3Node node, int fromIndex) {
        record(new Moved(chain, node, fromIndex));
    }

    /** {@code before} is a {@link Ap3Node#snapshot()} taken before the change. */
    static void changed(Ap3Chain chain, Ap3Node node, Ap3Node before) {
        record(new Changed(chain, node, before));
    }

    static void cleared(Ap3Chain chain) {
        record(new Cleared(chain));
    }

    /** Undo found nothing and fell back to deleting the chain's last node (AP3's old rule): redo puts it back. */
    static void fallbackDeleted(Ap3Chain chain, Ap3Node node, int index) {
        push(redo, new Removed(chain, node, index));
    }

    /** {@code /ap3 reload} or a config switch swapped every chain object. */
    static void reset() {
        undo.clear();
        redo.clear();
    }

    private static void record(Entry e) {
        if (e.chain() == null) {
            return;
        }
        redo.clear();
        push(undo, e);
    }

    private static void push(Deque<Entry> stack, Entry e) {
        if (e == null || e.chain() == null) {
            return;
        }
        stack.push(e);
        while (stack.size() > MAX) {
            stack.removeLast();
        }
    }

    /** For the chat line: verb, 1-based node number (0 for a whole chain), the node, the chain. */
    record Step(String verb, int number, Ap3Node node, Ap3Chain chain) {
    }

    private record Applied(Step step, Entry inverse) {
    }

    static Step undo() {
        return step(undo, redo, false);
    }

    static Step redo() {
        return step(redo, undo, true);
    }

    private static Step step(Deque<Entry> from, Deque<Entry> to, boolean redoing) {
        while (!from.isEmpty()) {
            Applied a = apply(from.pop(), redoing);
            if (a != null) {
                push(to, a.inverse());
                return a.step();
            }
        }
        return null;
    }

    private static Applied apply(Entry entry, boolean redoing) {
        Ap3Store store = Ap3Store.getInstance();
        Ap3Chain chain = entry.chain();
        Applied out = switch (entry) {
            case Added a -> {
                int i = live(chain) ? chain.indexOf(a.node()) : -1;
                if (i < 0) {
                    yield null;
                }
                chain.nodes().remove(i);
                yield new Applied(new Step(redoing ? "Redone delete of" : "Undone", i + 1, a.node(), chain),
                        new Removed(chain, a.node(), i));
            }
            case Removed r -> {
                if (!live(chain) || chain.contains(r.node()) || chain.nodes().size() >= Ap3Store.MAX_NODES) {
                    yield null;
                }
                int i = Math.max(0, Math.min(r.index(), chain.nodes().size()));
                chain.nodes().add(i, r.node());
                yield new Applied(new Step(redoing ? "Redone" : "Restored", i + 1, r.node(), chain),
                        new Added(chain, r.node()));
            }
            case Moved m -> {
                int i = live(chain) ? chain.indexOf(m.node()) : -1;
                if (i < 0) {
                    yield null;
                }
                chain.nodes().remove(i);
                int to = Math.max(0, Math.min(m.index(), chain.nodes().size()));
                chain.nodes().add(to, m.node());
                yield new Applied(new Step(redoing ? "Redone move of" : "Undone move of", to + 1, m.node(), chain),
                        new Moved(chain, m.node(), i));
            }
            case Changed c -> {
                if (!live(chain) || !chain.contains(c.node())) {
                    yield null;
                }
                Ap3Node now = c.node().snapshot();
                c.node().copyFrom(c.before());
                yield new Applied(new Step(redoing ? "Redone edit on" : "Undone edit on", chain.numberOf(c.node()),
                        c.node(), chain), new Changed(chain, c.node(), now));
            }
            case Cleared c -> {
                if (store.exact(chain.area(), chain.classFilter()) != null || !store.restore(chain)) {
                    yield null; // a new chain was started there since - never overwrite it
                }
                yield new Applied(new Step("Restored", 0, null, chain), new Recleared(chain));
            }
            case Recleared rc -> {
                if (store.exact(chain.area(), chain.classFilter()) != chain) {
                    yield null;
                }
                store.remove(chain);
                yield new Applied(new Step("Cleared", 0, null, chain), new Cleared(chain));
            }
        };
        if (chain.isEmpty() && store.exact(chain.area(), chain.classFilter()) == chain) {
            store.remove(chain); // the store never keeps an empty chain (deleteNode's rule) - nor one live() put back
        }
        return out;
    }

    /**
     * The chain is the one the store uses for its area and class. One that is missing because its last node was
     * deleted (so it is empty) is put back; one that is missing with nodes still in it was cleared, and only the
     * Cleared entry may bring that back.
     */
    private static boolean live(Ap3Chain chain) {
        Ap3Store store = Ap3Store.getInstance();
        Ap3Chain there = store.exact(chain.area(), chain.classFilter());
        if (there == chain) {
            return true;
        }
        return there == null && chain.isEmpty() && store.restore(chain);
    }
}
