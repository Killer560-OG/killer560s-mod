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

    /** The START node, or null. A route has at most one (adding another moves it - see {@link RouteRecorder}). */
    public RouteNode startNode() {
        for (RouteNode n : nodes) {
            if (n.type == RouteNode.Type.START) {
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
