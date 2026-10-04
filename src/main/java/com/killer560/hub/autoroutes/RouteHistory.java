package com.killer560.hub.autoroutes;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@code /ar undo}'s history: every add, remove, breaker edit and clear, newest first, whichever room it was in.
 * <p>
 * Mirrors AP3's undo ({@code Ap3Feature.undoLastAdded}): it reaches the most recent change "whatever area it went
 * into", lives only for the session (AP3's {@code lastAdded} is a static, never written to the chains file), and
 * when there is nothing left to undo it falls back to deleting the last node of the room you stand in. AP3 only
 * remembers the one node it last added; killer560 asked for undo across adds, removes, edits and deletes, so this
 * keeps a stack of {@link #MAX} entries instead, each one the inverse of a single change.
 * <p>
 * Entries hold the live {@link Route} and {@link RouteNode} objects, not copies, so an undo puts back the very node
 * that was removed (the start flag, await, item and breaker blocks with it). An entry whose route is no longer the
 * live one for its room - re-recorded, cleared since, or replaced by {@code /ar reload} - is stale and skipped.
 */
final class RouteHistory {

    /** Plenty for a room's worth of editing; the oldest change falls off the bottom. */
    static final int MAX = 100;

    private sealed interface Entry permits Added, Removed, Edited, Cleared {
        Route route();
    }

    private record Added(Route route, RouteNode node, RouteNode previousStart) implements Entry {
    }

    private record Removed(Route route, RouteNode node, int index) implements Entry {
    }

    private record Edited(Route route, RouteNode node, List<BlockPos> before) implements Entry {
    }

    private record Cleared(Route route) implements Entry {
    }

    private static final Deque<Entry> entries = new ArrayDeque<>();

    private RouteHistory() {
    }

    /** {@code node} was added to {@code route}; {@code previousStart} is the node the {@code start} flag moved off, or null. */
    static void added(Route route, RouteNode node, RouteNode previousStart) {
        push(new Added(route, node, previousStart));
    }

    /** {@code node} was removed from position {@code index} (0-based) of {@code route}. */
    static void removed(Route route, RouteNode node, int index) {
        push(new Removed(route, node, index));
    }

    /** A breaker node's block list is about to change: {@code node.breakerBlocks} as it stands now. */
    static void edited(Route route, RouteNode node) {
        push(new Edited(route, node, new ArrayList<>(node.breakerBlocks)));
    }

    /** {@code route} was removed from the store whole ({@code /ar clear}). */
    static void cleared(Route route) {
        push(new Cleared(route));
    }

    /** {@code /ar reload} swapped every route object: nothing in here points at a live one any more. */
    static void reset() {
        entries.clear();
    }

    static boolean isEmpty() {
        return entries.isEmpty();
    }

    private static void push(Entry e) {
        if (e.route() == null) {
            return;
        }
        entries.push(e);
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    /** What an undo did, for the chat line: verb, 1-based node number (0 for a whole route), node, room. */
    record Undone(String verb, int number, RouteNode node, Route route) {
    }

    /**
     * Reverts the newest change that still applies, skipping (and dropping) stale ones.
     * @return what was undone, or null when the history held nothing that still applies
     */
    static Undone undo() {
        while (!entries.isEmpty()) {
            Entry e = entries.pop();
            Undone done = apply(e);
            if (done != null) {
                return done;
            }
        }
        return null;
    }

    private static Undone apply(Entry entry) {
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
                return new Undone("Undone", i + 1, a.node(), route);
            }
            case Removed r -> {
                if (!isLive(route) || route.indexOf(r.node()) >= 0) {
                    return null;
                }
                int i = Math.max(0, Math.min(r.index(), route.nodes().size()));
                if (r.node().start) {
                    // A route has at most one start node (RouteRecorder.addNode's rule): if another took the flag
                    // since, the restored one takes it back, because it is the older decision being reinstated.
                    for (RouteNode n : route.nodes()) {
                        n.start = false;
                    }
                }
                route.nodes().add(i, r.node());
                return new Undone("Restored", i + 1, r.node(), route);
            }
            case Edited ed -> {
                if (!isLive(route) || route.indexOf(ed.node()) < 0) {
                    return null;
                }
                ed.node().breakerBlocks.clear();
                ed.node().breakerBlocks.addAll(ed.before());
                return new Undone("Undone breaker edit on", route.indexOf(ed.node()) + 1, ed.node(), route);
            }
            case Cleared c -> {
                if (store.forRoom(route.roomName()) != null) {
                    return null; // a new route was made in that room since - never overwrite it
                }
                store.put(route);
                return new Undone("Restored", 0, null, route);
            }
        }
    }

    /** The route is still the one the room uses (saved, or the recording in progress). */
    private static boolean isLive(Route route) {
        return route == RouteStore.getInstance().forRoom(route.roomName()) || route == RouteRecorder.recordingRoute();
    }
}
