package com.killer560.hub.pathfinding;

import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.ClearNode;
import com.killer560.hub.livemap.autoclear.EtherwarpPathfinder;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Etherwarping for the auto fairy soul modes. CHEAT BUILD ONLY.
 * <p>
 * Everything here is the Interactive Map's own machinery, reused rather than rewritten:
 * {@code livemap/autoclear/TeleportUtils} (QUOI's voxel traversal, {@code etherwarpable}, {@code getEtherwarpDirection},
 * {@code nearestEtherwarpable} - the same etherwarp raycast the Etherwarp Overlay draws with),
 * {@code livemap/autoclear/EtherwarpPathfinder} (multi-threaded A* over etherwarp raycasts + path smoothing) and
 * {@code livemap/autoclear/ClearExecutor} (item swap, forced sneak, the rotated use-item packet, one hop per tick,
 * server position sync). The only change made to the Interactive Map code is
 * {@code ClearExecutor.setExternalOwner(boolean)}, which stops the executor cancelling a queue that this feature owns
 * while the Interactive Map's own "Teleport Pathing" toggle is off; Interactive Map behaviour is unchanged.
 * <p>
 * The item is whatever etherwarp item is actually in the hotbar (anything with the {@code ethermerge} tag, or an
 * {@code ETHERWARP_CONDUIT}), and the range is Hypixel's 57 blocks plus the item's own {@code tuned_transmission}.
 */
public final class EtherwarpHopper {

    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-soulether");
        t.setDaemon(true);
        return t;
    });

    private static boolean planning;
    private static boolean failed;
    private static Vec3 expectedLanding;
    private static int generation;

    private EtherwarpHopper() {
    }

    /** Skyblock id of an etherwarp-capable hotbar item, or null. */
    public static String hotbarItem() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            CompoundTag tag = tag(stack);
            if (tag == null) {
                continue;
            }
            String id = tag.getStringOr("id", null);
            if (tag.getIntOr("ethermerge", 0) == 1 || "ETHERWARP_CONDUIT".equals(id)) {
                return id;
            }
        }
        return null;
    }

    /** Etherwarp range of the hotbar item (57 + tuned transmission), or 0 when there is none. */
    public static double range() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        for (int slot = 0; slot <= 8; slot++) {
            CompoundTag tag = tag(player.getInventory().getItem(slot));
            if (tag == null) {
                continue;
            }
            if (tag.getIntOr("ethermerge", 0) == 1 || "ETHERWARP_CONDUIT".equals(tag.getStringOr("id", null))) {
                return 57.0 + tag.getIntOr("tuned_transmission", 0);
            }
        }
        return 0;
    }

    private static CompoundTag tag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    public static boolean isBusy() {
        return planning || ClearExecutor.isBusy();
    }

    public static boolean lastFailed() {
        return failed;
    }

    public static Vec3 expectedLanding() {
        return expectedLanding;
    }

    public static void cancel() {
        generation++;
        planning = false;
        expectedLanding = null;
        ClearExecutor.cancel();
        ClearExecutor.setExternalOwner(false);
    }

    /** An etherwarpable block to land on next to {@code point} (the soul / a path point), or null. */
    public static BlockPos landingSpotNear(Vec3 point) {
        BlockPos below = BlockPos.containing(point.x, point.y - 1, point.z);
        if (TeleportUtils.etherwarpable(below)) {
            return below;
        }
        return TeleportUtils.nearestEtherwarpable(below);
    }

    /** Can the player etherwarp straight onto {@code target} from where they stand? */
    public static TeleportUtils.Rotation directionTo(BlockPos target) {
        LocalPlayer player = Minecraft.getInstance().player;
        double range = range();
        if (player == null || range <= 0 || target == null || !TeleportUtils.etherwarpable(target)) {
            return null;
        }
        Vec3 eye = new Vec3(player.getX(), player.getY() + TeleportUtils.eyeHeight(true), player.getZ());
        return TeleportUtils.getEtherwarpDirection(eye, target, range);
    }

    /** Queues one etherwarp onto {@code target}. @return false when it isn't reachable from here right now. */
    public static boolean hop(BlockPos target, Runnable onDone) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || isBusy()) {
            return false;
        }
        TeleportUtils.Rotation dir = directionTo(target);
        String item = hotbarItem();
        if (dir == null || item == null) {
            return false;
        }
        Vec3 pos = player.position();
        expectedLanding = new Vec3(target.getX() + 0.5, target.getY() + 1.05, target.getZ() + 0.5);
        failed = false;
        ClearExecutor.setExternalOwner(true);
        List<ClearNode> nodes = new ArrayList<>(1);
        nodes.add(new PathEtherNode(pos, dir.yaw(), dir.pitch(), item,
                List.of(new Vec3(pos.x, pos.y + TeleportUtils.eyeHeight(true), pos.z), Vec3.atCenterOf(target))));
        ClearExecutor.clearPath(nodes, () -> {
            ClearExecutor.setExternalOwner(false);
            if (onDone != null) {
                onDone.run();
            }
        });
        return true;
    }

    /**
     * Plans a whole etherwarp chain to {@code target} on a background thread (the Interactive Map's own A*), then runs
     * it. {@code onDone} fires once the last hop is server-confirmed; {@code onFail} when no chain exists.
     */
    public static void chain(BlockPos target, Runnable onDone, Runnable onFail) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        String item = hotbarItem();
        if (player == null || item == null || isBusy()) {
            if (onFail != null) {
                onFail.run();
            }
            return;
        }
        Vec3 from = player.position();
        double range = range();
        EtherwarpPathfinder.PathConfig cfg = pathConfig();
        int gen = ++generation;
        planning = true;
        failed = false;
        PLANNER.submit(() -> {
            List<EtherwarpPathfinder.Node> path = null;
            try {
                // no dungeon layout - this is the plain single-goal search (findDungeonPath's room chaining is
                // Catacombs-only), so the layout argument is never touched
                path = EtherwarpPathfinder.findPath(from, target, cfg, range, true, false, null);
            } catch (RuntimeException ignored) {
                // a chunk swapped out mid-search
            }
            List<EtherwarpPathfinder.Node> result = path;
            client.execute(() -> {
                planning = false;
                if (gen != generation) {
                    return;
                }
                if (result == null || result.isEmpty()) {
                    failed = true;
                    if (onFail != null) {
                        onFail.run();
                    }
                    return;
                }
                List<ClearNode> nodes = new ArrayList<>(result.size());
                for (EtherwarpPathfinder.Node node : result) {
                    Vec3 eye = new Vec3(node.x, node.y + TeleportUtils.eyeHeight(true), node.z);
                    Vec3 look = TeleportUtils.getLook(node.yaw, node.pitch).scale(range);
                    TeleportUtils.RaycastResult hit = TeleportUtils.traverseVoxels(eye.x, eye.y, eye.z,
                            eye.x + look.x, eye.y + look.y, eye.z + look.z, true);
                    Vec3 to = hit.pos() != null ? Vec3.atLowerCornerOf(hit.pos()) : eye.add(look);
                    nodes.add(new PathEtherNode(node.vec(), node.yaw, node.pitch, item, List.of(eye, to)));
                }
                expectedLanding = new Vec3(target.getX() + 0.5, target.getY() + 1.05, target.getZ() + 0.5);
                ClearExecutor.setExternalOwner(true);
                ClearExecutor.clearPath(nodes, () -> {
                    ClearExecutor.setExternalOwner(false);
                    if (onDone != null) {
                        onDone.run();
                    }
                });
            });
        });
    }

    /** Same search knobs the Interactive Map uses, with a longer timeout (open-world distances, not one room). */
    private static EtherwarpPathfinder.PathConfig pathConfig() {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        return new EtherwarpPathfinder.PathConfig(cfg.getYawStep(), cfg.getPitchStep(), cfg.getHWeight(),
                cfg.getThreads(), Math.max(1500L, cfg.getTimeoutMs() * 3L));
    }

    public static void warnNoItem() {
        ModChat.send("Auto Fairy Souls", ModChat.bad("No etherwarp item in the hotbar"),
                ModChat.dim(" - walking only."));
    }

    /** {@code ClearNode.EtherNode} with the player's actual etherwarp item instead of a hardcoded AOTV. */
    private static final class PathEtherNode extends ClearNode {
        private final String[] items;

        private PathEtherNode(Vec3 pos, float yaw, float pitch, String item, List<Vec3> points) {
            super(pos, yaw, pitch, 0xCC6600, points);
            this.items = new String[]{item};
        }

        @Override
        public boolean execute(double[] playerPos) {
            return doTeleport(playerPos, items, true, 1.05,
                    from -> TeleportUtils.getEtherPos(from, yaw, pitch, Math.max(1.0, range())));
        }
    }
}
