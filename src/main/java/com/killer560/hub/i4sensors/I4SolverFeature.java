package com.killer560.hub.i4sensors;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sharp Shooter "Solver" - killer560 (2026-09-14): "any block shot is now highlighted green like a waypoint,
 * with dots in between the blocks that are the proper prediction spots that I should use if I were doing it
 * manually". Visual only, both builds.
 * <p>
 * Every wall target seen going {@code EMERALD_BLOCK -> BLUE_TERRACOTTA} this attempt (a real hit - the unlit
 * state is also blue terracotta, so only the transition counts) gets a green filled + outlined box. For the
 * rows that still have unhit targets, a small dot marks Noamm's Terminator aim spots (AutoI4.kt
 * getTargetVector): x 67.5 (between columns x68/x66) and x 65.5 (between x66/x64), at the row's aim height
 * y = 131 - 2*row, on the wall's front face z 50. A dot is only drawn where it still covers an unhit
 * target, and the middle column only gets one dot when one already covers it.
 * <p>
 * Tracks the wall itself (independent of Auto i4), resets when a hit target lights again (new attempt) or the
 * player leaves the i4 area. Renders only while {@link I4SensorsFeature#isNearDevice()} (hypixel.net/p3sim.net,
 * around i4) and, on Hypixel, in F7/M7 per {@link DungeonState#isF7OrM7()}.
 */
public final class I4SolverFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoi4");
    private static final String TAG = "[AutoI4]";
    // Bigger (2026-09-14, killer560: "make them a bit bigger").
    // Much bigger flat plates (2026-09-14, killer560: "the little squares telling me where to aim... they are almost
    // impossible to see") - 0.6 blocks wide, drawn in the user's Aim Marker Color (default neon yellow).
    private static final double DOT_HALF = 0.3;
    private static final double DOT_DEPTH_HALF = 0.03;

    // killer560 (2026-09-14): "maybe you can hide the pane right behind it to make it pop more" - the stained
    // glass around each aim spot (the glass columns x65/x67 between the target columns, on the wall plane and the
    // layer in front of it) is replaced with air CLIENT-SIDE ONLY while the Solver is active at the device, and
    // put back when it stops. Only glass is ever touched; the server re-sends real blocks on any update anyway.
    // Blackout (2026-09-14, killer560: "make it so it only shows the 9 purple blocks and everything else in that radius
    // is pure black so it is easier to see") - CLIENT-SIDE ONLY while the Solver is active at the device, restored
    // when it stops. On the wall plane (z 50) every block of the device frame and a ring around it (x 62-70,
    // y 124-132) except the 9 targets becomes black concrete; anything glass in the layer just in front (z 49) is
    // hidden so nothing tints the markers. The 9 targets are never touched, so hit tracking is unaffected.
    private static final int MASK_MIN_X = 62, MASK_MAX_X = 70, MASK_MIN_Y = 124, MASK_MAX_Y = 132;
    private static final int WALL_Z = 50;
    private static final int FRONT_Z = 49;
    // Flags 2|16: tell the renderer, but skip neighbour shape updates.
    private static final int CLIENT_ONLY_FLAGS = 18;
    private static final Map<BlockPos, BlockState> maskedOriginals = new HashMap<>();
    private static Object maskedInLevel = null;

    private static final Set<BlockPos> hits = new HashSet<>();
    private static final Map<BlockPos, BlockState> lastWall = new HashMap<>();
    private static boolean wasActive = false;
    private static String lastState = "";

    private I4SolverFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(I4SolverFeature::render);
    }

    private static String gate(Minecraft client) {
        if (!I4SensorsConfig.getInstance().isSolverEnabled()) {
            return "off";
        }
        if (client.player == null || client.level == null || !I4SensorsFeature.isNearDevice()) {
            return "not near i4";
        }
        boolean p3sim = client.getCurrentServer() != null
                && client.getCurrentServer().ip.toLowerCase(Locale.ROOT).contains("p3sim.net");
        if (!p3sim && !DungeonState.isF7OrM7()) {
            return "not in F7/M7";
        }
        return "";
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        String gate = gate(client);
        boolean active = gate.isEmpty();
        String state = active ? "ACTIVE" : "idle: " + gate;
        if (!state.equals(lastState)) {
            LOGGER.info("{} {} Solver {}", TAG, I4SensorsFeature.clock(), state);
            lastState = state;
        }
        if (client.level != maskedInLevel) {
            maskedOriginals.clear(); // a different world - nothing of ours is left to restore
            maskedInLevel = client.level;
        }
        if (active) {
            maskWall(client);
        } else if (!maskedOriginals.isEmpty()) {
            restoreWall(client);
        }
        if (!active) {
            if (wasActive) {
                hits.clear();
                lastWall.clear();
            }
            wasActive = false;
            return;
        }
        wasActive = true;
        boolean first = lastWall.isEmpty();
        for (BlockPos pos : I4SensorsFeature.DEV_BLOCKS) {
            BlockState now = client.level.getBlockState(pos);
            BlockState old = lastWall.put(pos, now);
            if (first || old == null || old == now) {
                continue;
            }
            String from = I4SensorsFeature.blockId(old);
            String to = I4SensorsFeature.blockId(now);
            if (from.equals("emerald_block") && to.equals("blue_terracotta")) {
                hits.add(pos);
                LOGGER.info("{} {} Solver: target #{} marked hit ({} of 9 highlighted).", TAG, I4SensorsFeature.clock(),
                        I4SensorsFeature.DEV_BLOCKS.indexOf(pos), hits.size());
            } else if (to.equals("emerald_block") && hits.contains(pos)) {
                LOGGER.info("{} {} Solver: hit target #{} lit again - new attempt, clearing {} highlight(s).", TAG,
                        I4SensorsFeature.clock(), I4SensorsFeature.DEV_BLOCKS.indexOf(pos), hits.size());
                hits.clear();
            }
        }
    }

    private static void maskWall(Minecraft client) {
        BlockState black = Blocks.BLACK_CONCRETE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int newlyMasked = 0;
        for (int x = MASK_MIN_X; x <= MASK_MAX_X; x++) {
            for (int y = MASK_MIN_Y; y <= MASK_MAX_Y; y++) {
                BlockPos wall = new BlockPos(x, y, WALL_Z);
                if (!I4SensorsFeature.DEV_BLOCKS.contains(wall)) {
                    newlyMasked += mask(client, wall, black);
                }
                BlockPos front = new BlockPos(x, y, FRONT_Z);
                if (I4SensorsFeature.blockId(client.level.getBlockState(front)).contains("glass")) {
                    newlyMasked += mask(client, front, air);
                }
            }
        }
        if (newlyMasked > 0) {
            LOGGER.info("{} {} Solver: blacked out {} block(s) around the targets (client-side only, {} tracked).", TAG,
                    I4SensorsFeature.clock(), newlyMasked, maskedOriginals.size());
        }
    }

    /** @return 1 if the block had to be (re)placed - first time, or the server re-sent the real block. */
    private static int mask(Minecraft client, BlockPos pos, BlockState replacement) {
        BlockState current = client.level.getBlockState(pos);
        if (current == replacement) {
            return 0;
        }
        maskedOriginals.put(pos, current);
        client.level.setBlock(pos, replacement, CLIENT_ONLY_FLAGS);
        return 1;
    }

    private static void restoreWall(Minecraft client) {
        if (client.level != null) {
            BlockState black = Blocks.BLACK_CONCRETE.defaultBlockState();
            for (Map.Entry<BlockPos, BlockState> entry : maskedOriginals.entrySet()) {
                BlockState current = client.level.getBlockState(entry.getKey());
                if (current == black || current.isAir()) {
                    client.level.setBlock(entry.getKey(), entry.getValue(), CLIENT_ONLY_FLAGS);
                }
            }
            LOGGER.info("{} {} Solver: restored {} blacked-out block(s).", TAG, I4SensorsFeature.clock(), maskedOriginals.size());
        }
        maskedOriginals.clear();
    }

    private static void render(LevelRenderContext context) {
        if (!wasActive || !gate(Minecraft.getInstance()).isEmpty()) {
            return;
        }
        // Hit targets: filled orange (killer560: "you can keep the correct highlight orange").
        for (BlockPos pos : hits) {
            AABB box = new AABB(pos).inflate(0.03);
            WorldRenderUtils.renderFilledBox(context, box, 1f, 0.5f, 0f, 0.75f);
            WorldRenderUtils.renderOutlineBox(context, box, 1f, 0.6f, 0.1f, 1f, 3f);
        }
        List<BlockPos> dev = I4SensorsFeature.DEV_BLOCKS;
        for (int row = 0; row < 3; row++) {
            boolean col0 = !hits.contains(dev.get(row * 3));
            boolean col1 = !hits.contains(dev.get(row * 3 + 1));
            boolean col2 = !hits.contains(dev.get(row * 3 + 2));
            boolean left = col0 || (col1 && !col2);   // x 67.5 covers x68 + x66
            boolean right = col2 || (col1 && !left);  // x 65.5 covers x66 + x64
            // Centered on the row's blocks (2026-09-14, killer560: "in the middle of the blocks, right now they are
            // at the very top of them") - Auto i4's own aim height (131 - 2*row) includes arrow-drop compensation;
            // a manual player just needs to see which gap to aim at, so the marker sits at the block's center.
            double y = 130.5 - 2.0 * row;
            if (left) {
                dot(context, 67.5, y);
            }
            if (right) {
                dot(context, 65.5, y);
            }
        }
    }

    private static void dot(LevelRenderContext context, double x, double y) {
        // Just in front of the wall's front face (z 50) so it isn't hidden inside the blocks.
        AABB box = new AABB(x - DOT_HALF, y - DOT_HALF, 49.9 - DOT_DEPTH_HALF, x + DOT_HALF, y + DOT_HALF, 49.9 + DOT_DEPTH_HALF);
        float[] rgba = WorldRenderUtils.argbToFloats(I4SensorsConfig.getInstance().getSolverColor());
        // No border (2026-09-14, killer560: "please remove the border around the markers").
        WorldRenderUtils.renderFilledBox(context, box, rgba[0], rgba[1], rgba[2], 1f);
    }
}
