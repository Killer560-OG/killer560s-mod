package com.killer560.hub.miningqol;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.compat.McBlocks;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pickobulus Estimate (legit, both builds; killer560 2026-11-01: "a estimate for where my pickobulus will go").
 *
 * <p>While he holds a tool whose lore names the Pickobulus ability and it is not on cooldown (the tab list's "Pickaxe
 * Ability:" widget says "Pickobulus: Available", or is not shown), every block the ability would break is outlined. The
 * model is Skyblocker's PickobulusHelper (LGPL-3.0, read for behavior only, no code taken): Pickobulus is thrown from
 * 0.53625 blocks above the eye, lands on the first block in a 20-block line of sight, and breaks the blocks with an exposed
 * face (air on at least one side) within 3 blocks of where it lands (a 6x6x6 box round the landing block). Which blocks
 * break depends on the island: in the Dwarven Mines, Gold Mine and Deep Caverns only the ore and mineable blocks, in the
 * Crystal Hollows and the Glacite Mineshafts everything, and in the Glacite Tunnels the tunnel ores. It is an estimate:
 * nothing is used, clicked or sent.
 */
public final class PickobulusEstimate {

    private static final Pattern ABILITY = Pattern.compile("^Ability: Pickobulus\\b.*");

    private static final Set<Block> MINES_BREAKABLE = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.POLISHED_DIORITE, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE, McBlocks.CYAN_TERRACOTTA, McBlocks.LIGHT_BLUE_WOOL, McBlocks.GRAY_WOOL,
            Blocks.LAPIS_BLOCK, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.REDSTONE_BLOCK, Blocks.COAL_BLOCK, Blocks.QUARTZ_BLOCK, Blocks.GOLD_ORE, Blocks.IRON_ORE,
            Blocks.COAL_ORE, Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.NETHERRACK, Blocks.GLOWSTONE, Blocks.OBSIDIAN, Blocks.END_STONE);

    private static final Set<Block> TUNNELS_BREAKABLE = Set.of(
            Blocks.PACKED_ICE, Blocks.POLISHED_DIORITE, Blocks.INFESTED_STONE, McBlocks.LIGHT_GRAY_CARPET,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, McBlocks.LIGHT_BLUE_WOOL,
            McBlocks.GRAY_WOOL, McBlocks.CYAN_TERRACOTTA, McBlocks.BROWN_TERRACOTTA, Blocks.TERRACOTTA,
            Blocks.SMOOTH_RED_SANDSTONE, Blocks.INFESTED_COBBLESTONE, Blocks.CLAY);

    private static volatile List<BlockPos> breaks = List.of();
    private static volatile String status = "";
    private static int frames = 0;
    private static final BoxBatch LINES = new BoxBatch();

    private PickobulusEstimate() {
    }

    static void tick(Minecraft client) {
        if (!MiningQolConfig.getInstance().isPickobulus() || client.player == null || client.level == null
                || !MiningState.onMiningIsland()) {
            breaks = List.of();
            status = "";
            return;
        }
        for (String line : MiningState.pickaxeAbilityLines()) {
            if (line.startsWith("Pickobulus: ") && !line.equals("Pickobulus: Available")) {
                breaks = List.of();
                status = "cooldown";
                return;
            }
        }
        if (!holdsPickobulus(client.player)) {
            breaks = List.of();
            status = "not holding";
            return;
        }
        BlockPos target = target(client.player, client.level);
        if (target == null) {
            breaks = List.of();
            status = "no block";
            return;
        }
        breaks = estimate(client.level, target);
        status = "ok";
    }

    static boolean holdsPickobulus(Player player) {
        for (String line : CheatUtils.lore(player.getMainHandItem())) {
            if (ABILITY.matcher(line.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The block Pickobulus would land on: the first block within 20 blocks along the look, from 0.53625 above the eye. */
    static BlockPos target(Player player, ClientLevel level) {
        Vec3 start = player.getEyePosition().add(0, 0.53625, 0);
        Vec3 end = start.add(player.getLookAngle().scale(20));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** Every block the ability would break landing on {@code pos}, by the island's rule. Public for the testkit. */
    public static List<BlockPos> estimate(ClientLevel level, BlockPos pos) {
        BlockState[][][] b = new BlockState[8][8][8];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                for (int k = 0; k < 8; k++) {
                    b[i][j][k] = level.getBlockState(pos.offset(i - 4, j - 4, k - 4));
                }
            }
        }
        boolean all = MiningState.crystalHollows() || MiningState.mineshaft();
        boolean tunnels = !all && MiningState.glaciteTunnels();
        List<BlockPos> out = new ArrayList<>();
        for (int i = 1; i < 7; i++) {
            for (int j = 1; j < 7; j++) {
                for (int k = 1; k < 7; k++) {
                    BlockState s = b[i][j][k];
                    if (s.isAir() || s.is(Blocks.BEDROCK)) {
                        continue;
                    }
                    boolean exposed = b[i - 1][j][k].isAir() || b[i + 1][j][k].isAir() || b[i][j - 1][k].isAir()
                            || b[i][j + 1][k].isAir() || b[i][j][k - 1].isAir() || b[i][j][k + 1].isAir();
                    if (!exposed) {
                        continue;
                    }
                    boolean breakable;
                    if (all) {
                        breakable = true;
                    } else if (tunnels) {
                        breakable = TUNNELS_BREAKABLE.contains(s.getBlock()) || glass(s);
                    } else {
                        breakable = MINES_BREAKABLE.contains(s.getBlock()) || glass(s);
                    }
                    if (breakable) {
                        out.add(pos.offset(i - 4, j - 4, k - 4));
                        // Where everything breaks, a broken block exposes the ones behind it (Skyblocker does the same).
                        if (all || (tunnels && (s.is(Blocks.PACKED_ICE) || glass(s)))) {
                            b[i][j][k] = Blocks.AIR.defaultBlockState();
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean glass(BlockState s) {
        return s.getBlock() instanceof StainedGlassBlock || s.getBlock() instanceof StainedGlassPaneBlock;
    }

    static void render(LevelRenderContext context) {
        List<BlockPos> snap = breaks;
        if (snap.isEmpty() || !MiningQolConfig.getInstance().isPickobulus()) {
            return;
        }
        float[] k = WorldRenderUtils.argbToFloats(MiningQolConfig.getInstance().getPickobulusColor());
        LINES.clear();
        for (BlockPos p : snap) {
            LINES.add(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, k[0], k[1], k[2], 1f);
        }
        LINES.drawOutlines(context, RenderTypes.LINES_TRANSLUCENT, 2f);
        frames++;
    }

    // ---- testkit hooks

    public static int count() {
        return breaks.size();
    }

    public static String status() {
        return status;
    }

    public static int frames() {
        return frames;
    }
}
