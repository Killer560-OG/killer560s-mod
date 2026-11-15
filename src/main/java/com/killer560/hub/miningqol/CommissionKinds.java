package com.killer560.hub.miningqol;

import com.killer560.hub.pathfinding.IslandGraph;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commission name to where it is done and which powder it pays: the one lookup the HUD, waypoints and Commission Route
 * share. Names are matched tolerantly (case, the word "Gemstone", spacing), because the exact tab spelling of the
 * Mineshaft ones is not confirmed in game.
 *
 * <p>Glacite Tunnels commissions pay Glacite Powder (hypixelskyblock.minecraft.wiki, Commissions): the seven "Collector"
 * ones (Aquamarine, Citrine, Onyx, Peridot Gemstone, Glacite, Umber, Tungsten) and the four Mineshaft ones (Mineshaft
 * Explorer, Corpse Looter, Scrap Collector, Maniac Slayer). A collector's {@link Info#ore()} is the name of the island
 * graph's ore nodes where it is done ("Umber", "Peridot Gemstone"); the graph has none for Glacite, so that one has no route.
 */
public final class CommissionKinds {

    public static final String DWARVEN = "Dwarven Mines";
    public static final String TUNNELS = "Glacite Tunnels";
    public static final String HOLLOWS = "Crystal Hollows";

    /** {@code ore} is the graph node name of the spots, or null (no spots: a Mineshaft or non-ore commission). */
    public record Info(String area, String powder, String ore) {
    }

    private static final Map<String, String> TUNNEL_ORES = Map.of(
            "aquamarine", "Aquamarine Gemstone", "citrine", "Citrine Gemstone", "onyx", "Onyx Gemstone",
            "peridot", "Peridot Gemstone", "glacite", "Glacite", "umber", "Umber", "tungsten", "Tungsten");
    private static final Set<String> HOLLOW_GEMS = Set.of("amber", "topaz", "amethyst", "ruby", "jade", "sapphire");
    private static final List<String> MINESHAFT = List.of("mineshaft explorer", "corpse looter", "scrap collector", "maniac slayer");

    /** Max blocks between two labels of the same ore (one label per cluster of graph nodes). */
    private static final double CLUSTER = 40.0;

    private static IslandGraph cachedGraph;
    private static final Map<String, List<BlockPos>> CACHE = new HashMap<>();
    private static final Map<String, int[]> NODES = new HashMap<>();

    private CommissionKinds() {
    }

    /** The commission's area and powder, or null for one this does not know. */
    public static Info of(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT).replace('’', '\'').trim();
        if (n.isEmpty() || n.length() > 48) return null;
        for (String m : MINESHAFT) {
            if (n.equals(m)) return new Info(TUNNELS, "Glacite", null);
        }
        if (n.endsWith(" collector")) {
            String w = n.substring(0, n.length() - " collector".length()).replace(" gemstone", "").trim();
            String ore = TUNNEL_ORES.get(w);
            if (ore != null) return new Info(TUNNELS, "Glacite", ore);
            if (HOLLOW_GEMS.contains(w)) return new Info(HOLLOWS, "Gemstone", null);
        }
        for (String a : MiningState.COMMISSION_AREAS) {
            if (n.startsWith(a.toLowerCase(Locale.ROOT) + " ")) return new Info(DWARVEN, "Mithril", null);
        }
        if (n.equals("mithril miner") || n.equals("titanium miner")) return new Info(DWARVEN, "Mithril", null);
        return null;
    }

    /**
     * One spot per cluster of {@code ore} nodes in the graph (a "grind_ores" node named {@code ore}), cached per graph.
     * Empty when the graph is null or has none. Main thread only.
     */
    public static List<BlockPos> spots(IslandGraph g, String ore) {
        if (g == null || ore == null) return List.of();
        if (g != cachedGraph) {
            cachedGraph = g;
            CACHE.clear();
            NODES.clear();
        }
        return CACHE.computeIfAbsent(ore, o -> {
            List<BlockPos> out = new ArrayList<>();
            for (IslandGraph.Node nd : g.nodes) {
                if (!nd.hasTag("grind_ores") || nd.cleanName == null || !nd.cleanName.equalsIgnoreCase(o)) continue;
                boolean near = false;
                for (BlockPos p : out) {
                    double dx = p.getX() - nd.x, dy = p.getY() - nd.y, dz = p.getZ() - nd.z;
                    if (dx * dx + dy * dy + dz * dz < CLUSTER * CLUSTER) {
                        near = true;
                        break;
                    }
                }
                if (!near) out.add(BlockPos.containing(nd.x, nd.y, nd.z));
            }
            return List.copyOf(out);
        });
    }

    /** Indices of the graph nodes that are {@code ore} spots (every one, not clustered). */
    public static int[] oreNodes(IslandGraph g, String ore) {
        if (g == null || ore == null) return new int[0];
        if (g != cachedGraph) {
            cachedGraph = g;
            CACHE.clear();
            NODES.clear();
        }
        return NODES.computeIfAbsent(ore, o -> {
            List<Integer> out = new ArrayList<>();
            for (IslandGraph.Node nd : g.nodes) {
                if (nd.hasTag("grind_ores") && nd.cleanName != null && nd.cleanName.equalsIgnoreCase(o)) out.add(nd.index);
            }
            return out.stream().mapToInt(Integer::intValue).toArray();
        });
    }
}
