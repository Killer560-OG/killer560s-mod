package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Port of QUOI's {@code ClearNode} and its three {@code executor/nodes}: a teleport to perform when the (predicted)
 * player position is on {@link #pos}. {@link #execute} swaps to the item, raycasts the landing spot, queues the
 * rotated item use for the next tick and advances the predicted position - so a whole chain runs one hop per tick.
 */
public abstract class ClearNode {

    public final Vec3 pos;
    public final float yaw;
    public final float pitch;
    public final int colour;
    public final List<Vec3> points;

    protected ClearNode(Vec3 pos, float yaw, float pitch, int colour, List<Vec3> points) {
        this.pos = pos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.colour = colour;
        this.points = points;
    }

    public int priority() {
        return 0;
    }

    /** @return true once the teleport was issued (the node is consumed). */
    public abstract boolean execute(double[] playerPos);

    public boolean inside(double[] playerPos) {
        double dx = playerPos[0] - pos.x;
        double dy = playerPos[1] - pos.y;
        double dz = playerPos[2] - pos.z;
        return dx * dx + dy * dy + dz * dz <= 0.1;
    }

    public void render(LevelRenderContext ctx) {
        double eye = TeleportUtils.eyeHeight(this instanceof EtherNode);
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        WorldRenderUtils.renderFilledBox(ctx, new AABB(pos.x - 0.1, pos.y + 0.1, pos.z - 0.1, pos.x + 0.1, pos.y + eye, pos.z + 0.1),
                r, g, b, 100 / 255f);
        WorldRenderUtils.renderFilledBox(ctx, new AABB(pos.x - 0.5, pos.y, pos.z - 0.5, pos.x + 0.5, pos.y + 0.1, pos.z + 0.5),
                0f, 1f, 0f, 100 / 255f);
        WorldRenderUtils.renderLineStrip(ctx, points, r, g, b, 1f, 2f);
    }

    protected boolean cancel() {
        ClearExecutor.cancel();
        return false;
    }

    protected interface Raycast {
        TeleportUtils.RaycastResult cast(Vec3 from);
    }

    protected boolean doTeleport(double[] playerPos, String[] items, boolean sneak, double yOff, Raycast raycast) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        if (client.player.getLastSentInput().shift() != sneak) {
            return false; // wait until the server has seen the sneak state
        }
        if (!ClearExecutor.holdingAny(items)) {
            if (!ClearExecutor.swapById(items)) {
                return cancel();
            }
        }
        Vec3 from = new Vec3(playerPos[0], playerPos[1] + TeleportUtils.eyeHeight(sneak), playerPos[2]);
        TeleportUtils.RaycastResult res = raycast.cast(from);
        if (!res.succeeded() || res.pos() == null) {
            ModChat.send(ClearExecutor.CHAT, ModChat.bad("Failed"), ModChat.dim(" from "), ModChat.value(fmt(from)),
                    ModChat.dim(" yaw "), ModChat.value(String.format(java.util.Locale.US, "%.1f", yaw)),
                    ModChat.dim(" pitch "), ModChat.value(String.format(java.util.Locale.US, "%.1f", pitch)));
            return cancel();
        }
        ClearExecutor.queueInteract(yaw, pitch);
        playerPos[0] = res.pos().getX() + 0.5;
        playerPos[1] = res.pos().getY() + yOff;
        playerPos[2] = res.pos().getZ() + 0.5;
        return true;
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.US, "%.1f, %.1f, %.1f", v.x, v.y, v.z);
    }

    /** QUOI {@code ClearEtherNode}: sneaking Aspect of the Void etherwarp. */
    public static final class EtherNode extends ClearNode {
        public EtherNode(Vec3 pos, float yaw, float pitch, int colour, List<Vec3> points) {
            super(pos, yaw, pitch, colour, points);
        }

        @Override
        public boolean execute(double[] playerPos) {
            return doTeleport(playerPos, new String[]{"ASPECT_OF_THE_VOID"}, true, 1.05,
                    from -> TeleportUtils.getEtherPos(from, yaw, pitch));
        }
    }

    /** QUOI {@code ClearAotvNode}: non-sneaking 12-block instant transmission. */
    public static final class AotvNode extends ClearNode {
        public AotvNode(Vec3 pos, float yaw, float pitch, int colour, List<Vec3> points) {
            super(pos, yaw, pitch, colour, points);
        }

        @Override
        public boolean execute(double[] playerPos) {
            return doTeleport(playerPos, new String[]{"ASPECT_OF_THE_VOID"}, false, 1.0,
                    from -> TeleportUtils.getTeleportPos(from, yaw, pitch, 12.0));
        }
    }

    /** QUOI {@code ClearHypeNode}: 10-block wither-blade teleport. */
    public static final class HypeNode extends ClearNode {
        public HypeNode(Vec3 pos, float yaw, float pitch, int colour, List<Vec3> points) {
            super(pos, yaw, pitch, colour, points);
        }

        @Override
        public boolean execute(double[] playerPos) {
            return doTeleport(playerPos, new String[]{"HYPERION", "ASTREA", "SCYLLA", "VALKYRIE"}, false, 1.0,
                    from -> TeleportUtils.getTeleportPos(from, yaw, pitch, 10.0));
        }
    }

    /** QUOI {@code TeleportPathNode.toEther()}. */
    public static EtherNode toEther(EtherwarpPathfinder.Node node) {
        Vec3 from = new Vec3(node.x, node.y + TeleportUtils.eyeHeight(true), node.z);
        Vec3 look = TeleportUtils.getLook(node.yaw, node.pitch).scale(60.0);
        TeleportUtils.RaycastResult hit = TeleportUtils.traverseVoxels(from.x, from.y, from.z, from.x + look.x, from.y + look.y,
                from.z + look.z, true);
        Vec3 target = hit.pos() != null ? Vec3.atLowerCornerOf(hit.pos()) : from.add(look);
        return new EtherNode(node.vec(), node.yaw, node.pitch, 0xFFA500, List.of(from, target));
    }
}
