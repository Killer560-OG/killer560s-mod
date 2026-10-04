package com.killer560.hub.autoroutes;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@code /ar undo} and {@code /ar redo}'s history: every add, remove, breaker edit, node-editor change and clear,
 * newest first, whichever room it was in.
 * <p>
 * Mirrors AP3's undo ({@code Ap3Feature.undo}): it reaches the most recent change "whatever area it went into",
 * lives only for the session (never written to the routes file), and when there is nothing left to undo it falls
 * back to deleting the last node of the room you stand in. killer560 asked for undo across adds, removes, edits and
 * deletes, so this keeps a stack of {@link #MAX} entries, each one the inverse of a single change.
 * <p>
 * Redo (killer560, 2026-10-04: "a /ar redo where it is the opposite of undo and will restore things I just deleted
 * or undid"): applying an entry hands back its own inverse, which goes on the other stack - so undo feeds redo and
 * redo feeds undo, and every entry kind only has to know how to apply itself once. A NEW change (anything recorded
 * through {@link #added}, {@link #removed}, {@link #edited}, {@link #changed} or {@link #cleared}) empties the redo
 * stack, as every editor's redo does: redoing past a fresh edit would replay a change onto a state it never saw.
 * <p>
 * Entries hold the live {@link Route} and {@link RouteNode} objects, not copies, so an undo puts back the very node
 * that was removed (the start flag, await, item and breaker blocks with it). An entry whose route is no longer the
 * live one for its room - re-recorded, cleared since, or replaced by {@code /ar reload} - is stale and skipped.
 */
final class RouteHistory {

    /** Plenty for a room's worth of editing; the oldest change falls off the bottom. */
    static final int MAX = 100;

    private sealed interface Entry permits Added, Removed, Edited, Changed, Cleared, Recleared {
        Route route();
    }

    /** {@code node} is in the route; applying it takes the node out (and gives {@code previousStart} its start
     *  flag back when the node had taken it). */
    private record Added(Route route, RouteNode node, RouteNode previousStart) implements Entry {
    }

    /** {@code node} is out of the route; applying it puts the node back at {@code index}. */
    private record Removed(Route route, RouteNode node, int index) implements Entry {
    }

    /** A breaker node's block list; applying it sets the list back to {@code before}. */
    private record Edited(Route route, RouteNode node, List<BlockPos> before) implements Entry {
    }

    /** Any field of a node, from the node editor; applying it puts every field back to {@code before}, and the start
     *  flag back on {@code previousStart} when the edit had moved it off that node. */
    private record Changed(Route route, RouteNode node, RouteNode before, RouteNode previousStart) implements Entry {
    }

    /** {@code route} is out of the store ({@code /ar clear}); applying it puts the whole route back. */
    private record Cleared(Route route) implements Entry {
    }

    /** {@code route} is back in the store after an undone clear; applying it clears it again. */
    private record Recleared(Route route) implements Entry {
    }

    private static final Deque<Entry> undo = new ArrayDeque<>();
    private static final Deque<Entry> redo = new ArrayDeque<>();

    private RouteHistory() {
    }

    /** {@code node} was added to {@code route}; {@code previousStart} is the node the {@code start} flag moved off, or null. */
    static void added(Route route, RouteNode node, RouteNode previousStart) {
        record(new Added(route, node, previousStart));
    }

    /** {@code node} was removed from position {@code index} (0-based) of {@code route}. */
    static void removed(Route route, RouteNode node, int index) {
        record(new Removed(route, node, index));
    }

    /** A breaker node's block list is about to change: {@code node.breakerBlocks} as it stands now. */
    static void edited(Route route, RouteNode node) {
        record(new Edited(route, node, new ArrayList<>(node.breakerBlocks)));
    }

    /** The node editor changed {@code node}; {@code before} is a copy of it from before the change, and
     *  {@code previousStart} the node the change took the start flag off, or null. */
    static void changed(Route route, RouteNode node, RouteNode before, RouteNode previousStart) {
        record(new Changed(route, node, before, previousStart));
    }

    /** {@code route} was removed from the store whole ({@code /ar clear}). */
    static void cleared(Route route) {
        record(new Cleared(route));
    }

    /** Undo found nothing and deleted the room's last node instead ({@link AutoRoutesFeature#undo}'s AP3 fallback):
     *  that delete is an undo step, so redo is what puts the node back - and a new change still wipes it. */
    static void fallbackDeleted(Route route, RouteNode node, int index) {
        push(redo, new Removed(route, node, index));
    }

    /** {@code /ar reload} swapped every route object: nothing in here points at a live one any more. */
    static void reset() {
        undo.clear();
        redo.clear();
    }

    private static void record(Entry e) {
        if (e.route() == null) {
            return;
        }
        redo.clear();
        push(undo, e);
    }

    private static void push(Deque<Entry> stack, Entry e) {
        if (e == null || e.route() == null) {
            return;
        }
        stack.push(e);
        while (stack.size() > MAX) {
            stack.removeLast();
        }
    }

    /** What an undo or redo did, for the chat line: verb, 1-based node number (0 for a whole route), node, room. */
    record Undone(String verb, int number, RouteNode node, Route route) {
    }

    private record Applied(Undone done, Entry inverse) {
    }

    /**
     * Reverts the newest change that still applies, skipping (and dropping) stale ones; its inverse goes on the redo
     * stack. @return what was undone, or null when the history held nothing that still applies
     */
    static Undone undo() {
        return step(undo, redo, false);
    }

    /** Re-applies the newest undone change that still applies; its inverse goes back on the undo stack. */
    static Undone redo() {
        return step(redo, undo, true);
    }

    static boolean canRedo() {
        return !redo.isEmpty();
    }

    private static Undone step(Deque<Entry> from, Deque<Entry> to, boolean redoing) {
        while (!from.isEmpty()) {
            Applied a = apply(from.pop(), redoing);
            if (a != null) {
                push(to, a.inverse());
                return a.done();
            }
        }
        return null;
    }

    private static Applied apply(Entry entry, boolean redoing) {
        RouteStore store = RouteStore.getInstance();
        Route route = entry.route();
        switch (entry) {
            case Added a -> {
                if (!isLive(route)) {
                    return null;
                }
                int i = route.indexOf(a.node());
                if (i < 0) {
                    return null; // already deleted by number since
                }
                route.nodes().remove(i);
                if (a.node().start && a.previousStart() != null && route.indexOf(a.previousStart()) >= 0) {
                    a.previousStart().start = true; // the start flag goes back where it was
                }
                return new Applied(new Undone(redoing ? "Redone delete of" : "Undone", i + 1, a.node(), route),
                        new Removed(route, a.node(), i));
            }
            case Removed r -> {
                if (!isLive(route) || route.indexOf(r.node()) >= 0) {
                    return null;
                }
                int i = Math.max(0, Math.min(r.index(), route.nodes().size()));
                RouteNode displaced = null;
                if (r.node().start) {
                    // A route has at most one start node (RouteRecorder.addNode's rule): if another took the flag
                    // since, the restored one takes it back, because it is the older decision being reinstated.
                    for (RouteNode n : route.nodes()) {
                        if (n.start) {
                            n.start = false;
                            displaced = n;
                        }
                    }
                }
                route.nodes().add(i, r.node());
                return new Applied(new Undone(redoing ? "Redone" : "Restored", i + 1, r.node(), route),
                        new Added(route, r.node(), displaced));
            }
            case Edited ed -> {
                if (!isLive(route) || route.indexOf(ed.node()) < 0) {
                    return null;
                }
                List<BlockPos> now = new ArrayList<>(ed.node().breakerBlocks);
                ed.node().breakerBlocks.clear();
                ed.node().breakerBlocks.addAll(ed.before());
                return new Applied(new Undone(redoing ? "Redone breaker edit on" : "Undone breaker edit on",
                        route.indexOf(ed.node()) + 1, ed.node(), route), new Edited(route, ed.node(), now));
            }
            case Changed c -> {
                if (!isLive(route) || route.indexOf(c.node()) < 0) {
                    return null;
                }
                RouteNode now = c.node().copy();
                c.node().copyFrom(c.before());
                RouteNode displaced = null;
                if (c.node().start) {
                    for (RouteNode n : route.nodes()) {
                        if (n != c.node() && n.start) {
                            n.start = false;
                            displaced = n;
                        }
                    }
                } else if (c.previousStart() != null && route.indexOf(c.previousStart()) >= 0) {
                    for (RouteNode n : route.nodes()) {
                        n.start = false;
                    }
                    c.previousStart().start = true;
                }
                return new Applied(new Undone(redoing ? "Redone edit on" : "Undone edit on",
                        route.indexOf(c.node()) + 1, c.node(), route), new Changed(route, c.node(), now, displaced));
            }
            case Cleared c -> {
                if (store.forRoom(route.roomName()) != null) {
                    return null; // a new route was made in that room since - never overwrite it
                }
                store.put(route);
                return new Applied(new Undone("Restored", 0, null, route), new Recleared(route));
            }
            case Recleared rc -> {
                if (store.forRoom(route.roomName()) != route) {
                    return null;
                }
                store.remove(route.roomName());
                return new Applied(new Undone("Cleared", 0, null, route), new Cleared(route));
            }
        }
    }

    /** The route is still the one the room uses (saved, or the recording in progress). */
    private static boolean isLive(Route route) {
        return route == RouteStore.getInstance().forRoom(route.roomName()) || route == RouteRecorder.recordingRoute();
    }
}
