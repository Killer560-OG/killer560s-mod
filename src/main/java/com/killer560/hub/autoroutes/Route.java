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

    /** The live node list - callers that mutate it must {@code RouteStore.getInstance().save()} afterward. */
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
     * The order a run started at {@code start} fires in. A node's number means nothing about whether it is done
     * (killer560, 2026-11-27: "the number for the node does not matter"): a path-less run from the route's own start node
     * goes on past the last node to the first and on up to the start, so nodes numbered before the start are still run
     * (his Diagonal: start #12, #14 lands on #1, and #1-#11 never fired). So does a run that carries on an earlier one
     * ({@code carried}, Auto Secret's warp onto a node across a walk gap). Any other start (he stepped onto a node
     * mid-route) keeps path order from there.
     */
    public List<RouteNode> runOrder(RouteNode start, boolean carried) {
        List<RouteNode> order = nodesInPathOrder();
        int at = order.indexOf(start);
        if (at > 0 && path.isEmpty() && (start.start || carried)) {
            List<RouteNode> wrapped = new ArrayList<>(order.subList(at, order.size()));
            wrapped.addAll(order.subList(0, at));
            return wrapped;
        }
        return order;
    }

    /**
     * Every node on {@code trigger}'s tile ({@link RouteNode#sameTile}), trigger included, in the order they fire:
     * by {@link RouteNode#stackRank}, and nodes of the same rank in the order they were added (node number) - so a boom,
     * a crypt and a boom fire boom, boom, crypt (killer560, 2026-10-06: "if i make a boom a crypt then a boom, it should
     * do the first boom then the second then the crypt"). Whether a node is the {@code start} node no longer moves it
     * ahead of an earlier node of its own type. A lone node is a stack of one. Recomputed from the live list each time,
     * so a delete, an undo or a renumber is simply what the next stack sees. A path ARRIVAL (the second PATH of a pair)
     * fires nothing itself and is only real where he stands, so it goes just before the nodes that move him
     * ({@link #fireRank}): ranked as an etherwarp it fired after the tile's etherwarp had moved him, and since
     * 60920921 that stopped the route "not at path #16" (testkit 96-ar-1017-bridges, his Bridges route, #3 ETHERWARP
     * and #16 PATH on one tile).
     */
    public List<RouteNode> stackOf(RouteNode trigger) {
        List<RouteNode> stack = new ArrayList<>();
        if (trigger == null) {
            return stack;
        }
        // Only the unbroken run of same-tile nodes in PATH order around the trigger: a later return to the tile is a new stack.
        // A path arrival fires nothing and breaks no run: it is left out here and joins its own tile's stack below.
        List<RouteNode> ordered = nodesInPathOrder();
        ordered.removeIf(n -> n != trigger && isArrival(n));
        int at = ordered.indexOf(trigger);
        if (at >= 0) {
            int lo = at;
            int hi = at;
            while (lo > 0 && ordered.get(lo - 1).sameTile(trigger)) {
                lo--;
            }
            while (hi + 1 < ordered.size() && ordered.get(hi + 1).sameTile(trigger)) {
                hi++;
            }
            stack.addAll(ordered.subList(lo, hi + 1));
            for (RouteNode n : nodes) {
                if (n != trigger && isArrival(n) && n.sameTile(trigger)) {
                    stack.add(n);
                }
            }
        }
        if (!stack.contains(trigger)) {
            stack.add(trigger); // not (or no longer) in the list: it still fires itself
        }
        sortStack(stack);
        return stack;
    }

    private void sortStack(List<RouteNode> stack) {
        stack.sort(Comparator.comparingInt(this::fireRank)
                .thenComparingInt(n -> {
                    int i = nodes.indexOf(n);
                    return i < 0 ? Integer.MAX_VALUE : i;
                }));
    }

    /**
     * {@link #stackOf} plus the nodes on the trigger's tile that its consecutive run leaves out but this run would never
     * fire otherwise (killer560, 2026-11-26: "if i make a node nubmer 7 but the node that goes onto it is 4 then it just
     * wont fire number 7"): one placed in the run's {@code order} ({@link #runOrder}) after its first node ({@code from})
     * and before the trigger that has not fired ({@code done} false) - an out-of-order landing went past it - and, when
     * nothing in the stack moves him off the tile, every later one that has not fired, since he is left standing on it.
     * A stack that moves him keeps its later nodes for the route's return to the tile (arstack-1115).
     */
    public List<RouteNode> stackWithSkipped(RouteNode trigger, List<RouteNode> order, int from,
                                            java.util.function.Predicate<RouteNode> done) {
        List<RouteNode> stack = stackOf(trigger);
        int at = order.indexOf(trigger);
        if (at < 0) {
            return stack;
        }
        boolean moves = false;
        for (RouteNode n : stack) {
            moves |= movesOff(n);
        }
        int added = 0;
        for (int i = Math.max(0, from); i < order.size(); i++) {
            RouteNode n = order.get(i);
            if (i != at && (i < at || !moves) && n.sameTile(trigger) && !stack.contains(n) && !done.test(n)) {
                stack.add(n);
                added++;
            }
        }
        if (added > 0) {
            sortStack(stack);
        }
        return stack;
    }

    /** Takes him off the tile it fires on: an etherwarp, a path warp, a jump, a walk, a use that lands, a command. */
    private boolean movesOff(RouteNode n) {
        return fireRank(n) >= 2 * RouteNode.MOVER_RANK || n.type == RouteNode.Type.COMMAND
                || (n.type == RouteNode.Type.USE_ITEM && n.hasLanding);
    }

    private boolean isArrival(RouteNode node) {
        return node.type == RouteNode.Type.PATH && pathOrdinal(node) >= 0 && !isPathSource(node);
    }

    /** {@link RouteNode#stackRank} doubled, except a path arrival, which fires nothing and goes in between: after
     *  everything that acts standing still (its await then still holds only the movers), before the etherwarp, path,
     *  jump and walk that move him off its tile. */
    int fireRank(RouteNode node) {
        if (isArrival(node)) {
            return 2 * RouteNode.MOVER_RANK - 1;
        }
        return 2 * node.stackRank();
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
