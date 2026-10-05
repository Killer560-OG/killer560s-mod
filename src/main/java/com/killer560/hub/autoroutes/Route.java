package com.killer560.hub.autoroutes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One room's Auto Route: its ordered nodes plus the dense recorded {@link RoutePath}. One route per room name
 * (routes are keyed by the Live Map's room identity, so the same room in any run/rotation uses this one).
 */
public final class Route {

    private final String roomName;
    private final List<RouteNode> nodes = new ArrayList<>();
    private RoutePath path = new RoutePath();

    public Route(String roomName) {
        this.roomName = roomName;
    }

    public String roomName() {
        return roomName;
    }

    /** The live node list - callers that mutate it must {@code RouteStore.getInstance().save()} afterwards. */
    public List<RouteNode> nodes() {
        return nodes;
    }

    public RoutePath path() {
        return path;
    }

    public void setPath(RoutePath path) {
        this.path = path == null ? new RoutePath() : path;
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && path.isEmpty();
    }

    /** The node flagged {@code start}, or null. A route has at most one (adding the flag to another node moves
     *  it - see {@link RouteRecorder#addNode}). */
    public RouteNode startNode() {
        for (RouteNode n : nodes) {
            if (n.start) {
                return n;
            }
        }
        return null;
    }

    public int indexOf(RouteNode node) {
        return nodes.indexOf(node);
    }

    /** Nodes in playback order: by {@link RouteNode#pathIndex}, ties keeping insertion order (stable sort). */
    public List<RouteNode> nodesInPathOrder() {
        List<RouteNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingInt(n -> n.pathIndex));
        return sorted;
    }

    /**
     * Every node on {@code trigger}'s tile ({@link RouteNode#sameTile}), trigger included, in the order they fire:
     * by {@link RouteNode#stackRank}, then a {@code start} node before the rest of its type, then by node number.
     * A lone node is a stack of one. Recomputed from the live list each time, so a delete, an undo or a renumber
     * is simply what the next stack sees.
     */
    public List<RouteNode> stackOf(RouteNode trigger) {
        List<RouteNode> stack = new ArrayList<>();
        if (trigger == null) {
            return stack;
        }
        for (RouteNode n : nodes) {
            if (n == trigger || n.sameTile(trigger)) {
                stack.add(n);
            }
        }
        if (!stack.contains(trigger)) {
            stack.add(trigger); // not (or no longer) in the list: it still fires itself
        }
        stack.sort(Comparator.comparingInt(RouteNode::stackRank)
                .thenComparing(n -> !n.start)
                .thenComparingInt(n -> {
                    int i = nodes.indexOf(n);
                    return i < 0 ? Integer.MAX_VALUE : i;
                }));
        return stack;
    }

    /**
     * Path nodes pair up by number: the 1st path node of the room with the 2nd, the 3rd with the 4th. For the FIRST
     * of a pair this is the second (where its saved warps go); null for the second of a pair, or for a first with no
     * later path node yet.
     */
    public RouteNode pathDestination(RouteNode node) {
        int k = pathOrdinal(node);
        if (k < 0 || k % 2 != 0) {
            return null;
        }
        int seen = 0;
        for (RouteNode n : nodes) {
            if (n.type == RouteNode.Type.PATH && seen++ == k + 1) {
                return n;
            }
        }
        return null;
    }

    /** True when {@code node} is the first of a pair (whether or not its partner exists yet). */
    public boolean isPathSource(RouteNode node) {
        int k = pathOrdinal(node);
        return k >= 0 && k % 2 == 0;
    }

    /** The node a path destination is reached from, or null. */
    public RouteNode pathSource(RouteNode node) {
        int k = pathOrdinal(node);
        if (k < 1 || k % 2 != 1) {
            return null;
        }
        int seen = 0;
        for (RouteNode n : nodes) {
            if (n.type == RouteNode.Type.PATH && seen++ == k - 1) {
                return n;
            }
        }
        return null;
    }

    /** 0-based position of {@code node} among this route's PATH nodes in number order, or -1. */
    private int pathOrdinal(RouteNode node) {
        if (node == null || node.type != RouteNode.Type.PATH) {
            return -1;
        }
        int k = 0;
        for (RouteNode n : nodes) {
            if (n == node) {
                return k;
            }
            if (n.type == RouteNode.Type.PATH) {
                k++;
            }
        }
        return -1;
    }

    /** Re-anchors nodes that point past the end of a (re)recorded path so playback can still reach them. */
    public void clampNodeAnchors() {
        int max = Math.max(0, path.size() - 1);
        for (RouteNode n : nodes) {
            if (n.pathIndex < 0) {
                n.pathIndex = 0;
            } else if (n.pathIndex > max) {
                n.pathIndex = max;
            }
        }
    }
}
