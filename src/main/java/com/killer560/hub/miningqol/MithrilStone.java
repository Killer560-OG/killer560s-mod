package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mithril As Stone (legit, both builds; killer560 2026-11-01: "an option to turn mithril to stone if it is in an area i dont
 * need to mine in for a commission. If i have a commission in that area for mithril or for titanium it should show ... If i
 * ever get the universal mining area stuff then have all of it show").
 *
 * <p>Render only. The chunk mesher ({@code SectionCompiler.compile}) asks {@link #replace} for each block it draws; while
 * the swap is on, a Dwarven Mines mithril block is drawn with plain stone's model. The world itself is never changed: the
 * client still holds the mithril block, so breaking, collision and every other feature see the real block, and nothing
 * reaches the server. (SkyHanni's Commissions Blocks Color swaps block models the same way, through BlockStateModelSet.)
 *
 * <p>When it is on: the setting is on, the player is on the Dwarven Mines island, the tab list shows his Commissions, the
 * no open commission wants mithril or titanium where he is ({@link
 * MiningState#openCommissionHere}: "Mithril Miner"/"Titanium Miner" anywhere, or "Lava Springs Mithril" etc. while the
 * sidebar says Lava Springs). The area is the sidebar's, so the whole view follows the area he stands in. Each time the
 * answer changes, the sections around him are re-meshed.
 */
public final class MithrilStone {

    /** Dwarven Mines mithril: gray and light blue wool, the three prismarines, cyan terracotta (as Skyblocker's
     *  PickobulusHelper and CarpetHighlighter read them; the hypixelskyblock wiki's Mithril Ore page shows the same). */
    private static final Set<Block> MITHRIL = Set.of(McBlocks.GRAY_WOOL, McBlocks.LIGHT_BLUE_WOOL, Blocks.PRISMARINE,
            Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, McBlocks.CYAN_TERRACOTTA);

    private static volatile boolean active = false;
    private static volatile BlockState stone;
    private static volatile boolean dirtyRequested = false;
    private static final AtomicLong swapped = new AtomicLong();
    private static int remeshes = 0;

    private MithrilStone() {
    }

    /** Called from the chunk mesher (any thread): the state to draw instead of {@code state}, or null to draw it as is. */
    public static BlockState replace(BlockState state) {
        if (!active || state == null || !MITHRIL.contains(state.getBlock())) {
            return null;
        }
        swapped.incrementAndGet();
        BlockState s = stone;
        if (s == null) {
            s = Blocks.STONE.defaultBlockState();
            stone = s;
        }
        return s;
    }

    public static boolean isMithril(BlockState state) {
        return state != null && MITHRIL.contains(state.getBlock());
    }

    /** Config saved or loaded: re-decide on the next tick. */
    static void settingsChanged() {
        dirtyRequested = true;
    }

    static void tick(Minecraft client) {
        boolean want = compute();
        if (want != active || dirtyRequested) {
            boolean changed = want != active;
            active = want;
            dirtyRequested = false;
            if (changed) {
                remesh(client);
            }
        }
    }

    /** Whether mithril should be drawn as stone right now. */
    static boolean compute() {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        return cfg.isMithrilStone() && MiningState.dwarvenMines() && MiningState.commissionsKnown()
                && !MiningState.openCommissionHere("Mithril", true);
    }

    /** Marks every section within render distance for a rebuild (on the client thread). */
    private static void remesh(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        int r = client.options.getEffectiveRenderDistance() + 1;
        int cx = client.player.blockPosition().getX() >> 4;
        int cz = client.player.blockPosition().getZ() >> 4;
        level.setSectionRangeDirty(cx - r, level.getMinSectionY(), cz - r, cx + r, level.getMaxSectionY(), cz + r);
        remeshes++;
    }

    public static boolean isActive() {
        return active;
    }

    /** How many block draws were swapped to stone since start (testkit: the mixin acted). */
    public static long swappedCount() {
        return swapped.get();
    }

    public static int remeshCount() {
        return remeshes;
    }
}
