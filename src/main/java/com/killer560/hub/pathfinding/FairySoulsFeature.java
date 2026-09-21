package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fairy soul guidance: which souls are still missing on this island, in what order to collect them, and pointing the
 * navigation at the next one.
 * <p>
 * Soul locations come from the island graph itself - SkyHanni tags the soul nodes {@code fairy_soul}
 * (see {@code data/model/graph/GraphNodeTag.kt}), and the counts match NEU-REPO's own
 * {@code constants/fairy_souls.json} island by island (Hub 80, Crimson Isle 29, Spider's Den 19, ...), so no second
 * data source is needed. Found state is tracked in {@link FairySoulStore}.
 * <p>
 * The two chat lines Hypixel sends are the ones SkyHanni and Skyblocker both match:
 * "SOUL! You found a Fairy Soul!" and "You have already found that Fairy Soul!" - both mean the soul closest to the
 * player was collected (the second one means it was already collected on this profile).
 */
public final class FairySoulsFeature {

    private static final String CHAT = "Fairy Souls";
    /** SkyHanni uses 10 blocks for "which soul did I just click". */
    private static final double CLICK_MATCH_DISTANCE = 10.0;

    private static final ExecutorService ROUTE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-soulroute");
        t.setDaemon(true);
        return t;
    });

    private static final List<IslandGraph.Node> queue = new ArrayList<>();
    private static String queueIsland;
    private static boolean routing;
    private static boolean guiding;
    private static long lastAutoStartMs;
    private static boolean subscribed;

    private FairySoulsFeature() {
    }

    public static void register() {
        if (subscribed) {
            return;
        }
        subscribed = true;
        ChatObserver.subscribe(FairySoulsFeature::onChat);
    }

    // ------------------------------------------------------------------ state

    public static boolean isGuiding() {
        return guiding;
    }

    public static boolean isRouting() {
        return routing;
    }

    /** The soul currently being navigated to, or null. */
    public static IslandGraph.Node currentSoul() {
        return queue.isEmpty() ? null : queue.get(0);
    }

    public static int queuedSouls() {
        return queue.size();
    }

    public static List<IslandGraph.Node> unfound(IslandGraph graph) {
        List<IslandGraph.Node> out = new ArrayList<>();
        String profile = ProfileTracker.key();
        for (IslandGraph.Node soul : graph.withTag(IslandGraph.TAG_FAIRY_SOUL)) {
            if (!FairySoulStore.isFound(profile, graph.island, soul.x, soul.y, soul.z)) {
                out.add(soul);
            }
        }
        return out;
    }

    /** "12/80" style counts for the current island, or null when there is no graph yet. */
    public static int[] islandCounts() {
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (graph == null) {
            return null;
        }
        List<IslandGraph.Node> souls = graph.withTag(IslandGraph.TAG_FAIRY_SOUL);
        return new int[]{souls.size() - unfound(graph).size(), souls.size()};
    }

    /** What Hypixel's own menu last said for this island, or null if it was never opened. */
    public static FairySoulStore.IslandRecord menuRecord() {
        String island = IslandDetector.graphIsland();
        if (island == null) {
            return null;
        }
        FairySoulStore.IslandRecord record =
                FairySoulStore.record(ProfileTracker.key(), IslandDetector.soulMenuGroup(island));
        return record.menuTotal > 0 ? record : null;
    }

    // ------------------------------------------------------------------ commands / actions

    public static void guideNearest() {
        start(PathfindingConfig.SoulMode.NEAREST);
    }

    public static void routeIsland() {
        start(PathfindingConfig.SoulMode.ROUTE);
    }

    public static void start(PathfindingConfig.SoulMode mode) {
        Minecraft client = Minecraft.getInstance();
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (client.player == null) {
            return;
        }
        if (graph == null) {
            GraphRepository.ensureLoading(island, false);
            ModChat.send(CHAT, ModChat.bad(island == null
                    ? "No navigation graph for this island."
                    : "Still downloading the " + island + " graph - try again in a moment."));
            return;
        }
        if (graph.withTag(IslandGraph.TAG_FAIRY_SOUL).isEmpty()) {
            // killer560, 2026-09-21: "it says they have all been claimed" / "it said it couldn't find fairy
            // souls that needed to be found" - a graph with zero fairy_soul nodes (Dungeon Hub has none at
            // all; a not-yet-updated community graph can be missing them for an island that does have real
            // souls) used to fall straight into the "already logged as found" message below, which is a lie -
            // there was never anything here for the found-log to track, so say that instead of implying his
            // tracking is complete when it was never consulted at all.
            ModChat.send(CHAT, ModChat.bad(IslandDetector.islandName().isEmpty() ? "This island" : IslandDetector.islandName()),
                    ModChat.bad(" has no Fairy Souls to find "),
                    ModChat.dim("(or the SkyHanni graph doesn't have them mapped yet)."));
            return;
        }
        List<IslandGraph.Node> missing = unfound(graph);
        if (missing.isEmpty()) {
            ModChat.send(CHAT, ModChat.text("Every Fairy Soul on "), ModChat.value(IslandDetector.islandName()),
                    ModChat.text(" is already logged as found."));
            return;
        }
        queue.clear();
        queueIsland = island;
        guiding = true;
        if (mode == PathfindingConfig.SoulMode.NEAREST || missing.size() == 1) {
            Vec3 pos = client.player.position();
            IslandGraph.Node start = graph.nearest(pos.x, pos.y + 1.0, pos.z);
            GraphPathfinder.Tree tree = GraphPathfinder.dijkstra(graph, start.index, -1);
            IslandGraph.Node best = null;
            double bestCost = Double.MAX_VALUE;
            for (IslandGraph.Node soul : missing) {
                double cost = tree.dist()[soul.index];
                if (cost < bestCost) {
                    bestCost = cost;
                    best = soul;
                }
            }
            queue.add(best == null ? missing.get(0) : best);
            navigateToCurrent();
            return;
        }

        routing = true;
        ModChat.send(CHAT, ModChat.text("Calculating a route for "), ModChat.value(String.valueOf(missing.size())),
                ModChat.text(" Fairy Souls..."));
        Vec3 pos = client.player.position();
        long started = System.currentTimeMillis();
        ROUTE_POOL.submit(() -> {
            IslandGraph.Node start = graph.nearest(pos.x, pos.y + 1.0, pos.z);
            int[] order;
            try {
                order = GraphPathfinder.orderRoute(graph, start, missing, costFor(PathfindingConfig.getInstance()));
            } catch (RuntimeException e) {
                order = new int[0];
            }
            int[] finalOrder = order;
            client.execute(() -> {
                routing = false;
                if (!island.equals(IslandDetector.graphIsland()) || !guiding) {
                    return;
                }
                queue.clear();
                for (int idx : finalOrder) {
                    queue.add(missing.get(idx));
                }
                if (queue.isEmpty()) {
                    queue.addAll(missing);
                }
                ModChat.send(CHAT, ModChat.text("Route ready: "), ModChat.value(String.valueOf(queue.size())),
                        ModChat.text(" souls "), ModChat.dim("(" + (System.currentTimeMillis() - started) + "ms)"));
                navigateToCurrent();
            });
        });
    }

    public static void stopGuide(boolean announce) {
        queue.clear();
        guiding = false;
        routing = false;
        NavigationManager.stop(null, false);
        if (announce) {
            ModChat.send(CHAT, ModChat.text("Fairy Soul guidance stopped."));
        }
    }

    /** Points the navigation at the head of the queue. */
    public static void navigateToCurrent() {
        IslandGraph.Node soul = currentSoul();
        if (soul == null) {
            guiding = false;
            ModChat.send(CHAT, ModChat.good("All known Fairy Souls on this island are found!"));
            NavigationManager.stop(null, false);
            return;
        }
        int[] counts = islandCounts();
        String label = counts == null ? "Fairy Soul"
                : String.format(Locale.US, "Fairy Soul %d/%d", counts[0] + 1, counts[1]);
        NavigationManager.navigate(label, NavigationManager.centre(soul), soul.index, true, target -> {
            if (PathfindingConfig.getInstance().isChatFeedback()) {
                ModChat.send(CHAT, ModChat.text("Soul is right here - click it."));
            }
        });
    }

    /**
     * How "far apart" two souls are for the route order. Walking is measured in real graph distance; an etherwarp
     * chain is measured in walk-equivalent blocks (one ~50 block hop costs about as much time as walking 4.5 blocks)
     * plus a per-hop penalty that depends on the auto mode - so mode WALK practically never prefers warping, mode
     * ETHERWARP warps when it clearly wins, and mode FAST_ETHERWARP orders the route as if warping is free.
     * Without auto walking (display-only guidance) it is plain graph distance.
     */
    static GraphPathfinder.LegCost costFor(PathfindingConfig cfg) {
        if (!cfg.isAutoSouls()) {
            return GraphPathfinder.GRAPH_DISTANCE;
        }
        final double perHopPenalty = switch (cfg.getAutoMode()) {
            case WALK -> 400.0;
            case ETHERWARP -> 8.0;
            case FAST_ETHERWARP -> 0.0;
            // A thrown pearl has real range/precision limits (see EnderPearlHopper) so route order should
            // still mostly prefer walking, same spirit as WALK, but a well-aimed pearl beats a very long
            // detour - priced between WALK's near-never and ETHERWARP's readily-warps.
            case PEARLS -> 60.0;
        };
        return (from, to, graphDistance) -> {
            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;
            double straight = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int hops = Math.max(1, (int) Math.ceil(straight / 50.0));
            double ether = hops * (4.5 + perHopPenalty) + straight * 0.05;
            return Math.min(graphDistance, ether);
        };
    }

    /** Drops the current soul from the queue WITHOUT logging it as found (auto mode could not reach it). */
    public static void skipCurrent() {
        if (!queue.isEmpty()) {
            queue.remove(0);
        }
        if (guiding) {
            navigateToCurrent();
        }
    }

    /** Marks the soul closest to the player found and moves on. */
    public static void markClosestFound() {
        Minecraft client = Minecraft.getInstance();
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (client.player == null || graph == null) {
            return;
        }
        Vec3 pos = client.player.position();
        IslandGraph.Node best = null;
        double bestDist = Double.MAX_VALUE;
        for (IslandGraph.Node soul : graph.withTag(IslandGraph.TAG_FAIRY_SOUL)) {
            double d = soul.distSq(pos.x, pos.y, pos.z);
            if (d < bestDist) {
                bestDist = d;
                best = soul;
            }
        }
        if (best == null || bestDist > CLICK_MATCH_DISTANCE * CLICK_MATCH_DISTANCE) {
            return;
        }
        final IslandGraph.Node found = best;
        boolean changed = FairySoulStore.markFound(ProfileTracker.key(), island, found.x, found.y, found.z);
        IslandGraph.Node current = currentSoul();
        if (current != null && current.index == found.index) {
            queue.remove(0);
            if (guiding) {
                navigateToCurrent();
            }
        } else if (changed) {
            queue.removeIf(n -> n.index == found.index);
        }
        if (changed && PathfindingConfig.getInstance().isChatFeedback()) {
            int[] counts = islandCounts();
            if (counts != null) {
                ModChat.send(CHAT, ModChat.text("Logged "), ModChat.value(counts[0] + "/" + counts[1]),
                        ModChat.text(" on "), ModChat.value(IslandDetector.islandName()));
            }
        }
    }

    public static void resetIsland() {
        String island = IslandDetector.graphIsland();
        if (island == null) {
            ModChat.send(CHAT, ModChat.bad("Unknown island."));
            return;
        }
        FairySoulStore.resetIsland(ProfileTracker.key(), island);
        queue.clear();
        ModChat.send(CHAT, ModChat.text("Cleared the found-soul log for "), ModChat.value(IslandDetector.islandName()),
                ModChat.dim(" (profile " + ProfileTracker.displayName() + ")"));
    }

    public static void resetProfile() {
        FairySoulStore.resetProfile(ProfileTracker.key());
        queue.clear();
        ModChat.send(CHAT, ModChat.text("Cleared the found-soul log for profile "),
                ModChat.value(ProfileTracker.displayName()));
    }

    /** Marks every soul of the current island as found (for "I already did this island by hand"). */
    public static void markIslandFound() {
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (graph == null) {
            ModChat.send(CHAT, ModChat.bad("No graph for this island (yet)."));
            return;
        }
        String profile = ProfileTracker.key();
        int added = 0;
        for (IslandGraph.Node soul : graph.withTag(IslandGraph.TAG_FAIRY_SOUL)) {
            if (FairySoulStore.markFound(profile, island, soul.x, soul.y, soul.z)) {
                added++;
            }
        }
        queue.clear();
        NavigationManager.stop(null, false);
        ModChat.send(CHAT, ModChat.text("Marked "), ModChat.value(String.valueOf(added)),
                ModChat.text(" souls as found on "), ModChat.value(IslandDetector.islandName()));
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(Minecraft client) {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!cfg.isFairySouls()) {
            if (guiding) {
                stopGuide(false);
            }
            return;
        }
        String island = IslandDetector.graphIsland();
        if (island == null) {
            return;
        }
        GraphRepository.ensureLoading(island, false);
        if (guiding && !island.equals(queueIsland)) {
            stopGuide(false);
        }
        if (guiding && !routing && !NavigationManager.isActive() && !queue.isEmpty()) {
            navigateToCurrent();
        }
        if (!guiding && cfg.isAutoStartOnIsland() && System.currentTimeMillis() - lastAutoStartMs > 10_000L) {
            IslandGraph graph = GraphRepository.get(island);
            if (graph != null && !unfound(graph).isEmpty() && client.player != null) {
                lastAutoStartMs = System.currentTimeMillis();
                start(cfg.getSoulMode());
            }
        }
    }

    private static void onChat(Component message) {
        if (!PathfindingConfig.getInstance().isFairySouls()) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        if (plain.equals("SOUL! You found a Fairy Soul!") || plain.equals("You have already found that Fairy Soul!")) {
            Minecraft.getInstance().execute(FairySoulsFeature::markClosestFound);
        }
    }
}
