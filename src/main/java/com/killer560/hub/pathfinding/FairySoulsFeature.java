package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fairy soul guidance: which souls are still missing on this island, in what order to collect them, and pointing the
 * navigation at the next one.
 * <p>
 * Soul locations come from the island graph itself - the reference tags the soul nodes {@code fairy_soul}
 * (see {@code data/model/graph/GraphNodeTag.kt}), and the counts match the public
 * {@code constants/fairy_souls.json} island by island (Hub 80, Crimson Isle 29, Spider's Den 19, ...), so no second
 * data source is needed. Found state is tracked in {@link FairySoulStore}.
 * <p>
 * The two chat lines Hypixel sends are the ones other implementations both match:
 * "SOUL! You found a Fairy Soul!" and "You have already found that Fairy Soul!" - both mean the soul closest to the
 * player was collected (the second one means it was already collected on this profile).
 */
public final class FairySoulsFeature {

    private static final String CHAT = "Fairy Souls";
    /** The reference uses 10 blocks for "which soul did I just click". */
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
    private static String autoStartedIsland;
    /** Real head centres of souls whose stand has been seen (node block position -> centre); the graph node is only a
     *  walkable spot up to 3.6 blocks from the stand (doc-fairy-1101). Cleared when the island changes. */
    private static final Map<Long, Vec3> STANDS = new HashMap<>();
    private static String standsIsland;

    /** The seen stand's head centre for a soul, or null while its stand has not been in range. */
    private static long key(double x, double y, double z) {
        return BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public static Vec3 standCentre(double x, double y, double z) {
        return STANDS.get(key(x, y, z));
    }

    /** Head centre of an armor stand, from vanilla's own model: the head part pivots 1.501 - 1/16 above the feet
     *  (0.5 x on a small stand) and the skull block drawn on it is 0.5 x 1.1875 tall, centred 0.297 above the pivot. */
    public static Vec3 headCentre(ArmorStand s) {
        return s.position().add(0, (s.isSmall() ? 0.5 : 1.0) * 1.7354, 0);
    }

    private static void refineStands(Minecraft client, String island, IslandGraph graph) {
        if (!island.equals(standsIsland)) {
            STANDS.clear();
            standsIsland = island;
        }
        if (client.level == null || client.player == null || client.level.getGameTime() % 10 != 0) return;
        Vec3 me = client.player.position();
        for (IslandGraph.Node soul : unfound(graph)) {
            if (soul.distSq(me.x, me.y, me.z) > 64 * 64) continue;
            Vec3 c = NavigationManager.centre(soul);
            ArmorStand best = null;
            double bestD = 16;
            for (ArmorStand s : client.level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(c, c).inflate(5))) {
                if (s.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) continue;
                double d = s.position().distanceToSqr(c);
                if (d < bestD) { bestD = d; best = s; }
            }
            if (best != null) STANDS.put(key(soul.x, soul.y, soul.z), headCentre(best));
        }
    }
    private static boolean subscribed;

    private FairySoulsFeature() {
    }

    public static void register() {
        if (subscribed) return;
        subscribed = true;
        ChatObserver.subscribe(FairySoulsFeature::onChat);
    }


    /** Auto Fairy Souls is running (cheat build): it uses this guide and the navigation whatever the Fairy Souls switch. */
    public static boolean autoRunning() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && AutoSoulRunner.isActive();
    }

    /**
     * The guide, the found-soul log and Hypixel's soul lines are wanted: the Fairy Souls switch is on, or Auto Fairy
     * Souls is running (killer560, 2026-10-17: "you shouldn't have to have the fairy soul setting on for auto fairy
     * souls to work").
     */
    public static boolean soulsWanted() { return PathfindingConfig.getInstance().isFairySouls() || autoRunning(); }

    /** A progress line: chat for the legit guide, the log while Auto Fairy Souls runs (automation says only
     *  started/stopped, corrections and real failures, killer560 2026-10-16). */
    public static void say(String feature, Component... parts) {
        if (autoRunning()) {
            ModChat.log(feature, parts);
        } else {
            ModChat.send(feature, parts);
        }
    }

    public static boolean isGuiding() { return guiding; }

    public static boolean isRouting() { return routing; }

    /** The soul currently being navigated to, or null. */
    public static IslandGraph.Node currentSoul() { return queue.isEmpty() ? null : queue.get(0); }

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
        IslandGraph graph = GraphRepository.get(island);
        if (graph == null) return null;
        List<IslandGraph.Node> souls = graph.withTag(IslandGraph.TAG_FAIRY_SOUL);
        return new int[]{souls.size() - unfound(graph).size(), souls.size()};
    }

    /** What Hypixel's own menu last said for this island, or null if it was never opened. */
    public static FairySoulStore.IslandRecord menuRecord() {
        String island = IslandDetector.graphIsland();
        if (island == null) return null;
        FairySoulStore.IslandRecord record =
                FairySoulStore.record(ProfileTracker.key(), IslandDetector.soulMenuGroup(island));
        return record.menuTotal > 0 ? record : null;
    }


    public static void guideNearest() { start(PathfindingConfig.SoulMode.NEAREST); }

    public static void routeIsland() { start(PathfindingConfig.SoulMode.ROUTE); }

    public static void start(PathfindingConfig.SoulMode mode) {
        Minecraft client = Minecraft.getInstance();
        String island = IslandDetector.graphIsland();
        IslandGraph graph = GraphRepository.get(island);
        if (client.player == null) return;
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
                    ModChat.dim("(or the island graph doesn't have them mapped yet)."));
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
            queue.add(GraphPathfinder.closestByCost(graph, pos.x, pos.y + 1.0, pos.z, missing));
            navigateToCurrent();
            return;
        }

        routing = true;
        say(CHAT, ModChat.text("Calculating a route for "), ModChat.value(String.valueOf(missing.size())),
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
                if (!island.equals(IslandDetector.graphIsland()) || !guiding) return;
                queue.clear();
                for (int idx : finalOrder) {
                    queue.add(missing.get(idx));
                }
                if (queue.isEmpty()) {
                    queue.addAll(missing);
                }
                say(CHAT, ModChat.text("Route ready: "), ModChat.value(String.valueOf(queue.size())),
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
            say(CHAT, ModChat.good("All known Fairy Souls on this island are found!"));
            NavigationManager.stop(null, false);
            return;
        }
        int[] counts = islandCounts();
        String label = counts == null ? "Fairy Soul"
                : String.format(Locale.US, "Fairy Soul %d/%d", counts[0] + 1, counts[1]);
        NavigationManager.navigate(label, NavigationManager.centre(soul), soul.index, true, target -> {
            if (PathfindingConfig.getInstance().isChatFeedback()) {
                say(CHAT, ModChat.text("Soul is right here - click it."));
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
        if (!cfg.isAutoSouls()) return GraphPathfinder.GRAPH_DISTANCE;
        if (!cfg.getAutoMode().etherwarps()) {
            return GraphPathfinder.GRAPH_DISTANCE; // Walk Only never warps (pearls are only an escape)
        }
        final double perHopPenalty = cfg.getAutoMode() == PathfindingConfig.AutoMode.ETHERWARP ? 8.0 : 0.0;
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
        IslandGraph graph = GraphRepository.get(island);
        if (client.player == null || graph == null) return;
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
        if (best == null || bestDist > CLICK_MATCH_DISTANCE * CLICK_MATCH_DISTANCE) return;
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
                say(CHAT, ModChat.text("Logged "), ModChat.value(counts[0] + "/" + counts[1]),
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
        IslandGraph graph = GraphRepository.get(island);
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


    public static void tick(Minecraft client) {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!soulsWanted()) {
            if (guiding) {
                stopGuide(false);
            }
            return;
        }
        String island = IslandDetector.graphIsland();
        if (island == null) {
            autoStartedIsland = null; // leaving the graph islands: the next arrival starts the guide again
            if (guiding) stopGuide(false);
            return;
        }
        GraphRepository.ensureLoading(island, false);
        IslandGraph known = GraphRepository.get(island);
        if (known != null) refineStands(client, island, known);
        if (guiding && !island.equals(queueIsland)) {
            stopGuide(false);
        }
        if (guiding && !routing && !NavigationManager.isActive() && !queue.isEmpty()) {
            navigateToCurrent();
        }
        // the finder is simply on while the switch is: once per arrival on an island that still has unfound souls
        // (a Stop sticks until the next island)
        if (!guiding && cfg.isFairySouls() && !island.equals(autoStartedIsland) && client.player != null) {
            IslandGraph graph = GraphRepository.get(island);
            if (graph != null && !unfound(graph).isEmpty()) {
                autoStartedIsland = island;
                start(cfg.getSoulMode());
            }
        }
    }

    private static void onChat(Component message) {
        if (!soulsWanted()) return;
        String plain = ChatObserver.strip(message).trim();
        if (plain.equals("SOUL! You found a Fairy Soul!") || plain.equals("You have already found that Fairy Soul!")) {
            Minecraft.getInstance().execute(FairySoulsFeature::markClosestFound);
        }
    }
}
