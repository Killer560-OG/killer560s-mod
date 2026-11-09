package com.killer560.hub.ghostblock;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Ghost Block (cheat build, featH-1109, from the Odin sweep): while the key is held, the block under the crosshair becomes
 * air on this client only, tick after tick, so the next one behind it can be looked at and clicked. Outside AP3 (its own
 * ghost blocks are placements). Nothing is sent and no position or velocity is written.
 * <p>
 * The server still has the block solid, and walking into it would be a GrimAC simulation setback, so a ghost never reaches
 * the body: the real state comes back the moment the player's box (grown by {@link #SAFE} each way, more downward) touches the
 * block, and every ghost comes back when the key is let go, the screen opens or the world changes. At most {@link #MAX} at
 * once. Containers, buttons, levers, doors, trapdoors and fence gates are never ghosted (a secret or a terminal stays real).
 */
public final class GhostBlockFeature {

    private static final double SAFE = 0.6;
    private static final int MAX = 64;
    private static final Map<BlockPos, BlockState> GHOSTS = new HashMap<>();
    private static Object level;

    /** Test hook: stands in for the key (a harness key press never reaches the poll). Null in play. */
    private static volatile Boolean heldForTest;

    private GhostBlockFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("GhostBlock", GhostBlockFeature::tick));
    }

    public static void forceHeld(Boolean held) { heldForTest = held; }

    /** Blocks currently ghosted, for the testkit. */
    public static int count() { return GHOSTS.size(); }

    static void tick(Minecraft client) {
        if (client.player == null || client.level == null || !GhostBlockConfig.getInstance().isEnabled()
                || McCompat.screen(client) != null || !held(client)) {
            if (!GHOSTS.isEmpty()) restoreAll(client);
            return;
        }
        if (client.level != level) {
            GHOSTS.clear();
            level = client.level;
        }
        AABB body = client.player.getBoundingBox().inflate(SAFE).expandTowards(0, -SAFE, 0);
        for (Iterator<Map.Entry<BlockPos, BlockState>> it = GHOSTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            if (body.intersects(new AABB(e.getKey()))) {
                client.level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
                it.remove();
            }
        }
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || GHOSTS.size() >= MAX || body.intersects(new AABB(pos)) || interactable(state)) {
            return;
        }
        GHOSTS.put(pos.immutable(), state);
        client.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static boolean held(Minecraft client) {
        Boolean forced = heldForTest;
        if (forced != null) return forced;
        int code = GhostBlockConfig.getInstance().getKeyCode();
        return code != KeyUtil.NONE && client.getWindow() != null && KeyUtil.isBindDown(client.getWindow(), code);
    }

    private static boolean interactable(BlockState s) {
        return s.getBlock() instanceof EntityBlock || s.getBlock() instanceof ButtonBlock || s.getBlock() instanceof LeverBlock
                || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof TrapDoorBlock || s.getBlock() instanceof FenceGateBlock;
    }

    private static void restoreAll(Minecraft client) {
        if (client.level != null) {
            GHOSTS.forEach((p, s) -> client.level.setBlock(p, s, Block.UPDATE_CLIENTS));
        }
        GHOSTS.clear();
    }
}
