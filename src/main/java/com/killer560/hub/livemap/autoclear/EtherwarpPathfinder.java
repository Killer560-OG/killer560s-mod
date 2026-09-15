package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Port of QUOI's {@code EtherwarpPathfinder} + {@code AbstractTeleportPathfinder} + {@code AbstractPathfinder} +
 * {@code PathContext}/{@code EtherwarpContext} + {@code generateRaycasts}: multi-threaded A* whose edges are etherwarp
 * raycasts (fixed yaw/pitch fan, 60 blocks), then {@code smoothPath} merges hops that can be skipped. The dungeon
 * variant chains one search per room along {@link DungeonMapPathfinder}'s room path, targeting each door with a
 * 3-block radius (or any landing inside the next room within 3 blocks of door height).
 */
public final class EtherwarpPathfinder {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-etherpath");
        t.setDaemon(true);
        return t;
    });

    private static float lastPitchStep = -1f;
    private static float lastYawStep = -1f;
    private static double lastDist = -1;
    private static Raycasts cachedRaycasts;

    private EtherwarpPathfinder() {
    }

    /** QUOI {@code PathConfig}. */
    public record PathConfig(float yawStep, float pitchStep, double hWeight, int threads, long timeout) {
    }

    /** QUOI {@code TeleportPathNode}. */
    public static final class Node implements Comparable<Node> {
        public final double x;
        public final double y;
        public final double z;
        public final BlockPos pos;
        double g;
        double h;
        Node parent;
        public final float yaw;
        public final float pitch;

        Node(double x, double y, double z, BlockPos pos, double g, double h, Node parent, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public Vec3 vec() {
            return new Vec3(x, y, z);
        }

        double f() {
            return g + h;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f(), o.f());
        }
    }

    private record Raycasts(double[] dx, double[] dy, double[] dz, float[] yaws, float[] pitches, double scale) {
    }

    /** QUOI {@code EtherwarpContext}. */
    private static final class Context {
        final BlockPos goal;
        final double dist;
        final double hWeight;
        final Raycasts raycasts;
        final long endTime;
        final boolean offset;
        final double radius;
        final int nextRoom;
        final DungeonLayout layout;

        volatile boolean solved = false;
        volatile List<Node> finalPath = null;
        final AtomicInteger processed = new AtomicInteger();
        private final PriorityQueue<Node> openSet = new PriorityQueue<>();
        private final Map<Long, Node> nodeMap = new HashMap<>();
        private final Set<Long> activeSet = new HashSet<>();

        Context(BlockPos goal, double dist, PathConfig cfg, Raycasts raycasts, boolean offset, double radius, int nextRoom,
                DungeonLayout layout) {
            this.goal = goal;
            this.dist = dist;
            this.hWeight = cfg.hWeight();
            this.raycasts = raycasts;
            this.endTime = System.currentTimeMillis() + cfg.timeout();
            this.offset = offset;
            this.radius = radius;
            this.nextRoom = nextRoom;
            this.layout = layout;
        }

        synchronized boolean isDone() {
            return openSet.isEmpty() && activeSet.isEmpty();
        }

        synchronized Node getNext() {
            while (!openSet.isEmpty()) {
                Node node = openSet.poll();
                long key = node.pos.asLong();
                Node best = nodeMap.get(key);
                if (best != null && node.g > best.g) {
                    continue;
                }
                activeSet.add(key);
                return node;
            }
            return null;
        }

        synchronized void finishNode(long key) {
            activeSet.remove(key);
        }

        synchronized void addNode(Node node) {
            if (solved) {
                return;
            }
            long key = node.pos.asLong();
            Node existing = nodeMap.get(key);
            if (existing == null || node.g < existing.g) {
                nodeMap.put(key, node);
                openSet.add(node);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- public API

    /** QUOI {@code findPath}: single search, no room chaining. */
    public static List<Node> findPath(Vec3 from, BlockPos to, PathConfig cfg, double dist, boolean offset, boolean withLast,
                                      DungeonLayout layout) {
        if (!TeleportUtils.etherwarpable(to)) {
            return null;
        }
        Raycasts raycasts = getRaycasts(dist, cfg.pitchStep(), cfg.yawStep());
        Context ctx = new Context(to, dist, cfg, raycasts, offset, 0.0, -1, layout);
        BlockPos startPos = BlockPos.containing(from);
        ctx.addNode(new Node(from.x, from.y, from.z, startPos, 0.0, distance(startPos, to) / dist, null, 0f, 0f));
        List<Node> path = find(ctx, cfg.threads());
        return path == null ? null : smoothPath(path, dist, withLast);
    }

    /** QUOI {@code findDungeonPath}: room-by-room segments via {@link DungeonMapPathfinder}. */
    public static List<Node> findDungeonPath(Vec3 from, BlockPos to, PathConfig cfg, double dist, boolean offset,
                                             DungeonLayout layout) {
        if (!TeleportUtils.etherwarpable(to)) {
            return null;
        }
        BlockPos startPos = BlockPos.containing(from);
        int startRoom = layout.roomAtWorld(from.x, from.z);
        int goalRoom = layout.roomAtWorld(to.getX(), to.getZ());
        if (startRoom < 0 || goalRoom < 0 || startRoom == goalRoom) {
            return findPath(from, to, cfg, dist, offset, false, layout);
        }
        List<DungeonMapPathfinder.RoomStep> roomPath = DungeonMapPathfinder.findPath(layout, startRoom, goalRoom, false);
        if (roomPath == null) {
            return null;
        }
        List<Node> path = new ArrayList<>();
        Node lastNode = new Node(from.x, from.y, from.z, startPos, 0.0, 0.0, null, 0f, 0f);
        Node startNode = lastNode;
        for (int i = 0; i < roomPath.size(); i++) {
            DungeonMapPathfinder.RoomStep step = roomPath.get(i);
            BlockPos target = to;
            double radius = 0.0;
            int nextRoom = -1;
            if (step.door() >= 0) {
                BlockPos door = DungeonLayout.doorBlock(step.door());
                target = new BlockPos(door.getX(), 68, door.getZ());
                radius = 9.0;
                nextRoom = roomPath.get(i + 1).room();
            }
            Raycasts raycasts = getRaycasts(dist, cfg.pitchStep(), cfg.yawStep());
            Context ctx = new Context(target, dist, cfg, raycasts, offset, radius, nextRoom, layout);
            startNode.h = distance(startNode.pos, target) / dist;
            ctx.addNode(startNode);
            List<Node> segment = find(ctx, cfg.threads());
            if (segment == null) {
                return null;
            }
            for (int j = 1; j < segment.size(); j++) {
                Node node = segment.get(j);
                Node connected = new Node(node.x, node.y, node.z, node.pos, lastNode.g + node.g, node.h,
                        j == 1 ? lastNode : path.get(path.size() - 1), node.yaw, node.pitch);
                path.add(connected);
            }
            if (!path.isEmpty()) {
                lastNode = path.get(path.size() - 1);
                startNode = new Node(lastNode.x, lastNode.y, lastNode.z, lastNode.pos, 0.0, 0.0, null, lastNode.yaw, lastNode.pitch);
            }
        }
        if (path.isEmpty()) {
            return null;
        }
        path.add(0, new Node(from.x, from.y, from.z, startPos, 0.0, 0.0, null, 0f, 0f));
        if (path.size() > 1) {
            path.get(1).parent = path.get(0);
        }
        return smoothPath(path, dist, false);
    }

    // ------------------------------------------------------------------------------------------- search

    private static List<Node> find(Context ctx, int threadCount) {
        List<Future<?>> workers = new ArrayList<>();
        for (int i = 0; i < threadCount - 1; i++) {
            workers.add(POOL.submit(() -> runWorker(ctx)));
        }
        runWorker(ctx);
        for (Future<?> f : workers) {
            try {
                f.get(100, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        return ctx.finalPath;
    }

    private static void runWorker(Context ctx) {
        while (!ctx.solved) {
            if (System.currentTimeMillis() > ctx.endTime) {
                ctx.solved = true;
                return;
            }
            Node current = ctx.getNext();
            if (current == null) {
                if (ctx.isDone()) {
                    ctx.solved = true;
                } else {
                    Thread.yield();
                }
                continue;
            }
            ctx.processed.incrementAndGet();
            if (isGoal(ctx, current)) {
                synchronized (ctx) {
                    if (ctx.finalPath == null) {
                        ctx.finalPath = reconstruct(current);
                        ctx.solved = true;
                    }
                }
                return;
            }
            try {
                expand(ctx, current);
            } catch (RuntimeException ignored) {
                // a chunk swapped out mid-read - skip this node
            }
            ctx.finishNode(current.pos.asLong());
        }
    }

    private static boolean isGoal(Context ctx, Node current) {
        if (ctx.radius > 0.0) {
            if (current.pos.distSqr(ctx.goal) <= ctx.radius) {
                return true;
            }
            if (ctx.nextRoom >= 0) {
                int room = ctx.layout.roomAtWorld(current.pos.getX(), current.pos.getZ());
                return room == ctx.nextRoom && Math.abs(current.pos.getY() - ctx.goal.getY()) <= 3;
            }
            return false;
        }
        return current.pos.equals(ctx.goal);
    }

    private static List<Node> reconstruct(Node node) {
        List<Node> path = new ArrayList<>();
        Node current = node;
        while (current != null) {
            path.add(0, current);
            current = current.parent;
        }
        return path;
    }

    /** QUOI {@code AbstractTeleportPathfinder.expand} with etherwarp hit rules (sneaking eye height). */
    private static void expand(Context ctx, Node current) {
        double eyeX = current.x;
        double eyeY = current.y + TeleportUtils.eyeHeight(true);
        double eyeZ = current.z;
        double vx = ctx.goal.getX() + 0.5 - current.x;
        double vy = ctx.goal.getY() - current.y;
        double vz = ctx.goal.getZ() + 0.5 - current.z;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double inv = dist > 0 ? 1.0 / dist : 0.0;
        double dirX = vx * inv;
        double dirY = vy * inv;
        double dirZ = vz * inv;
        double pDirX = 0;
        double pDirY = 0;
        double pDirZ = 0;
        BlockPos parent = current.parent != null ? current.parent.pos : null;
        if (parent != null) {
            double px = parent.getX() - current.pos.getX();
            double py = parent.getY() - current.pos.getY();
            double pz = parent.getZ() - current.pos.getZ();
            double pDist = Math.sqrt(px * px + py * py + pz * pz);
            if (pDist > 0) {
                pDirX = px / pDist;
                pDirY = py / pDist;
                pDirZ = pz / pDist;
            }
        }
        Set<Long> hitCache = new HashSet<>();
        Raycasts r = ctx.raycasts;
        for (int i = 0; i < r.dx().length; i++) {
            if (ctx.solved) {
                return;
            }
            double dx = r.dx()[i];
            double dy = r.dy()[i];
            double dz = r.dz()[i];
            double gDot = (dx * dirX + dy * dirY + dz * dirZ) / r.scale();
            if (gDot <= 0.5) {
                if (gDot > 0.0 && i % 2 != 0) {
                    continue;
                } else if (gDot <= 0.0 && i % 4 != 0) {
                    continue;
                }
            }
            if (parent != null && (dx * pDirX + dy * pDirY + dz * pDirZ) / r.scale() > 0.65) {
                continue;
            }
            TeleportUtils.RaycastResult result = TeleportUtils.traverseVoxels(eyeX, eyeY, eyeZ, eyeX + dx, eyeY + dy, eyeZ + dz, true);
            if (!result.succeeded() || result.pos() == null || !(result.pos().equals(ctx.goal) || !blackListed(result.state()))) {
                continue;
            }
            BlockPos hit = result.pos();
            if (hitCache.add(hit.asLong())) {
                double hCost = (distance(hit, ctx.goal) / ctx.dist) * ctx.hWeight;
                ctx.addNode(new Node(hit.getX() + 0.5, hit.getY() + (ctx.offset ? 1.05 : 1.0), hit.getZ() + 0.5, hit,
                        current.g + 1.0, hCost, current, r.yaws()[i], r.pitches()[i]));
            }
        }
    }

    /** QUOI {@code smoothPath}: from each node, jump to the furthest later node an etherwarp can reach directly. */
    private static List<Node> smoothPath(List<Node> path, double dist, boolean withLast) {
        if (path.size() < 2) {
            return path;
        }
        List<Node> smoothed = new ArrayList<>();
        int i = 0;
        while (i < path.size() - 1) {
            int next = i + 1;
            Node current = path.get(i);
            Vec3 from = new Vec3(current.x, current.y + TeleportUtils.eyeHeight(true), current.z);
            float yaw = path.get(next).yaw;
            float pitch = path.get(next).pitch;
            for (int j = path.size() - 1; j >= i + 1; j--) {
                TeleportUtils.Rotation dir = TeleportUtils.getEtherwarpDirection(from, path.get(j).pos, dist);
                if (dir != null) {
                    next = j;
                    yaw = dir.yaw();
                    pitch = dir.pitch();
                    break;
                }
            }
            smoothed.add(new Node(current.x, current.y, current.z, current.pos, current.g, current.h, current.parent, yaw, pitch));
            i = next;
        }
        if (withLast) {
            smoothed.add(path.get(path.size() - 1));
        }
        return smoothed;
    }

    private static boolean blackListed(BlockState state) {
        if (state == null) {
            return true;
        }
        var block = state.getBlock();
        boolean bottomSlab = block instanceof SlabBlock && state.hasProperty(SlabBlock.TYPE)
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
        return bottomSlab || block instanceof CarpetBlock || block instanceof WallBlock || block instanceof FenceBlock
                || block instanceof FenceGateBlock || block instanceof HopperBlock || block instanceof CauldronBlock
                || block instanceof BannerBlock;
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    private static synchronized Raycasts getRaycasts(double dist, float pitchStep, float yawStep) {
        if (dist == lastDist && pitchStep == lastPitchStep && yawStep == lastYawStep && cachedRaycasts != null) {
            return cachedRaycasts;
        }
        List<double[]> dirs = new ArrayList<>();
        List<float[]> rots = new ArrayList<>();
        for (float pitch = -90f; pitch <= 90f; pitch += pitchStep) {
            float actualYawStep = yawStep / Math.max(0.01f, (float) Math.cos(Math.toRadians(pitch)));
            for (float yaw = 0f; yaw < 360f; yaw += actualYawStep) {
                Vec3 v = TeleportUtils.getLook(yaw, pitch);
                dirs.add(new double[]{v.x * dist, v.y * dist, v.z * dist});
                rots.add(new float[]{yaw, pitch});
            }
        }
        int n = dirs.size();
        double[] dx = new double[n];
        double[] dy = new double[n];
        double[] dz = new double[n];
        float[] yaws = new float[n];
        float[] pitches = new float[n];
        for (int i = 0; i < n; i++) {
            dx[i] = dirs.get(i)[0];
            dy[i] = dirs.get(i)[1];
            dz[i] = dirs.get(i)[2];
            yaws[i] = rots.get(i)[0];
            pitches[i] = rots.get(i)[1];
        }
        cachedRaycasts = new Raycasts(dx, dy, dz, yaws, pitches, dist);
        lastDist = dist;
        lastPitchStep = pitchStep;
        lastYawStep = yawStep;
        return cachedRaycasts;
    }
}
