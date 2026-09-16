package com.killer560.hub.pathfinding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One island's navigation graph, in SkyHanni's island-graph JSON format.
 * <p>
 * Data: SkyHanni-REPO {@code constants/island_graphs/<ISLAND>.json} (https://github.com/hannibal002/SkyHanni-REPO,
 * MIT licensed; hand-recorded by the SkyHanni team with their in-game Graph Editor). Format, as read by SkyHanni's own
 * {@code data/model/graph/Graph.kt} (LGPL-2.1, https://github.com/hannibal002/SkyHanni):
 * <pre>
 * { "&lt;node id&gt;": { "Position": "x:y:z",           // feet block of a standing spot
 *                   "Name": "Fairy Soul",             // optional
 *                   "Tags": ["fairy_soul"],           // optional: area, small_area, npc, poi, warp, jump_pad, ...
 *                   "ExtraWeight": 20,                // optional, ALREADY folded into the edge weights below
 *                   "Neighbours": { "&lt;id&gt;": 3.16 } } } // DIRECTED edges, weight = distance + extra weights
 * </pre>
 * Edges are directed (about 8% of edges on the real files have no reverse edge - drops you can walk down but not up).
 * Pure Java (Gson only) so it can be unit-tested outside Minecraft.
 */
public final class IslandGraph {

    public static final String TAG_FAIRY_SOUL = "fairy_soul";

    public final String island;
    public final Node[] nodes;
    private final Map<Integer, Node> byId;

    public static final class Node {
        public final int index;
        public final int id;
        public final double x;
        public final double y;
        public final double z;
        /** Raw name (may contain § colour codes), or null. */
        public final String name;
        /** Name with § codes removed, or null. */
        public final String cleanName;
        public final List<String> tags;
        int[] out = new int[0];
        double[] weight = new double[0];

        Node(int index, int id, double x, double y, double z, String name, List<String> tags) {
            this.index = index;
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.cleanName = name == null ? null : name.replaceAll("§.", "").trim();
            this.tags = tags;
        }

        public boolean hasTag(String tag) {
            return tags.contains(tag);
        }

        public int[] neighbours() {
            return out;
        }

        public double[] weights() {
            return weight;
        }

        public double distSq(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        /** Stable key for a soul/position ("x:y:z", integer block coords). */
        public String posKey() {
            return (int) Math.floor(x) + ":" + (int) Math.floor(y) + ":" + (int) Math.floor(z);
        }

        @Override
        public String toString() {
            return (cleanName == null ? "#" + id : cleanName) + " (" + (int) x + ", " + (int) y + ", " + (int) z + ")";
        }
    }

    private IslandGraph(String island, Node[] nodes) {
        this.island = island;
        this.nodes = nodes;
        Map<Integer, Node> map = new HashMap<>(nodes.length * 2);
        for (Node n : nodes) {
            map.put(n.id, n);
        }
        this.byId = map;
    }

    /** @throws IllegalArgumentException when the JSON isn't a usable graph (no nodes). */
    public static IslandGraph parse(String island, String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("graph root is not an object");
        }
        JsonObject obj = root.getAsJsonObject();
        List<Node> list = new ArrayList<>(obj.size());
        List<Map<Integer, Double>> edges = new ArrayList<>(obj.size());
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            int id;
            try {
                id = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject n = e.getValue().getAsJsonObject();
            double[] pos = parsePos(n.has("Position") && n.get("Position").isJsonPrimitive() ? n.get("Position").getAsString() : null);
            if (pos == null) {
                continue;
            }
            String name = n.has("Name") && n.get("Name").isJsonPrimitive() ? n.get("Name").getAsString() : null;
            List<String> tags = new ArrayList<>(2);
            if (n.has("Tags") && n.get("Tags").isJsonArray()) {
                JsonArray arr = n.getAsJsonArray("Tags");
                for (JsonElement t : arr) {
                    if (t.isJsonPrimitive()) {
                        tags.add(t.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
            }
            Map<Integer, Double> nb = new LinkedHashMap<>();
            if (n.has("Neighbours") && n.get("Neighbours").isJsonObject()) {
                for (Map.Entry<String, JsonElement> ne : n.getAsJsonObject("Neighbours").entrySet()) {
                    try {
                        double w = ne.getValue().getAsDouble();
                        if (Double.isFinite(w) && w >= 0) {
                            nb.put(Integer.parseInt(ne.getKey()), w);
                        }
                    } catch (RuntimeException ignored) {
                        // one bad edge never drops the node
                    }
                }
            }
            list.add(new Node(list.size(), id, pos[0], pos[1], pos[2], name, tags.isEmpty() ? List.of() : List.copyOf(tags)));
            edges.add(nb);
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("graph has no nodes");
        }
        IslandGraph graph = new IslandGraph(island, list.toArray(new Node[0]));
        for (int i = 0; i < graph.nodes.length; i++) {
            Map<Integer, Double> nb = edges.get(i);
            int[] out = new int[nb.size()];
            double[] w = new double[nb.size()];
            int k = 0;
            for (Map.Entry<Integer, Double> e : nb.entrySet()) {
                Node target = graph.byId.get(e.getKey());
                if (target == null) {
                    continue; // dangling reference - SkyHanni errors here, we just skip the edge
                }
                out[k] = target.index;
                w[k] = e.getValue();
                k++;
            }
            graph.nodes[i].out = k == out.length ? out : java.util.Arrays.copyOf(out, k);
            graph.nodes[i].weight = k == w.length ? w : java.util.Arrays.copyOf(w, k);
        }
        return graph;
    }

    private static double[] parsePos(String s) {
        if (s == null) {
            return null;
        }
        String[] parts = s.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) ? new double[]{x, y, z} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Node byId(int id) {
        return byId.get(id);
    }

    /** Closest node by straight-line distance, or null for an empty graph. */
    public Node nearest(double x, double y, double z) {
        Node best = null;
        double bestD = Double.MAX_VALUE;
        for (Node n : nodes) {
            double d = n.distSq(x, y, z);
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    public List<Node> withTag(String tag) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n.hasTag(tag)) {
                out.add(n);
            }
        }
        return out;
    }

    /** Nodes whose clean name equals {@code name} (case-insensitive). */
    public List<Node> named(String name) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n.cleanName != null && n.cleanName.equalsIgnoreCase(name)) {
                out.add(n);
            }
        }
        return out;
    }

    /** Every distinct navigable name on the island (sorted), for tab completion. "no_area" is SkyHanni's reserved
     *  "belongs to no area" marker and never a destination. */
    public List<String> destinationNames() {
        TreeMap<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Node n : nodes) {
            if (n.cleanName == null || n.cleanName.isEmpty() || n.cleanName.equalsIgnoreCase("no_area")) {
                continue;
            }
            names.putIfAbsent(n.cleanName, n.cleanName);
        }
        return Collections.unmodifiableList(new ArrayList<>(names.values()));
    }
}
