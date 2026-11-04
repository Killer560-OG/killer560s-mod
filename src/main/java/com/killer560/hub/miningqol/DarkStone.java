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
 * Dark Stone (legit, both builds; killer560 2026-11-04: "add a stone dark mode option as well. a texture pack that makes
 * all stuff dark mode"). Render only, no textures shipped or generated: the chunk mesher ({@code SectionCompiler.compile},
 * the same call Mithril As Stone wraps) hands each quad of a stone-type block to {@link #factor}, and the mixin multiplies
 * the quad's vertex color by it, which is a darkened copy of the vanilla texture without copying one. Ores, gemstones and
 * Titanium (polished diorite) are not in the set and draw as they always did. The world is never changed.
 *
 * <p>Applies while the setting is on, Skyblock allows the mod and the player is on a mining island. Changing the setting or
 * the level re-meshes the view, so it applies live.
 */
public final class DarkStone {

    /** Stone-type blocks of the mining islands (stone, hardstone, cobble, the three rocks + polished, deepslate, tuff, ice). */
    private static final Set<Block> STONE = Set.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.SMOOTH_STONE,
            Blocks.ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.POLISHED_GRANITE,
            Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS,
            Blocks.TUFF, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.INFESTED_STONE, Blocks.INFESTED_COBBLESTONE);

    /** Mithril: only with Darken Mithril on (default off, so it still stands out). */
    private static final Set<Block> MITHRIL = Set.of(McBlocks.GRAY_WOOL, McBlocks.LIGHT_BLUE_WOOL, Blocks.PRISMARINE,
            Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, McBlocks.CYAN_TERRACOTTA);

    private static volatile boolean on = false;
    private static volatile boolean mithril = false;
    private static volatile int level = 50;
    private static String lastKey = "";
    private static final AtomicLong darkened = new AtomicLong();
    private static int remeshes = 0;

    private DarkStone() {
    }

    /** The multiplier (0..1) for the quads of {@code state}, or 1 for "draw as is". Mesher thread. */
    public static float factor(BlockState state) {
        if (!on || state == null) {
            return 1.0f;
        }
        Block b = state.getBlock();
        if (STONE.contains(b) || (mithril && MITHRIL.contains(b))) {
            darkened.incrementAndGet();
            return 1.0f - level / 100.0f;
        }
        return 1.0f;
    }

    /** ARGB for QuadInstance.multiplyColor from a factor. */
    public static int argb(float f) {
        int c = Math.max(0, Math.min(255, Math.round(f * 255.0f)));
        return 0xFF000000 | (c << 16) | (c << 8) | c;
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        boolean en = cfg.isDarkStone() && (MiningState.onMiningIsland() || forceIsland);
        int lv = cfg.getDarkStoneLevel();
        boolean mi = cfg.isDarkStoneMithril();
        String key = en + "|" + lv + "|" + mi;
        on = en;
        level = lv;
        mithril = mi;
        if (!key.equals(lastKey)) {
            lastKey = key;
            remesh(client);
        }
    }

    private static void remesh(Minecraft client) {
        ClientLevel lvl = client.level;
        if (lvl == null || client.player == null) {
            return;
        }
        int r = client.options.getEffectiveRenderDistance() + 1;
        int cx = client.player.blockPosition().getX() >> 4;
        int cz = client.player.blockPosition().getZ() >> 4;
        lvl.setSectionRangeDirty(cx - r, lvl.getMinSectionY(), cz - r, cx + r, lvl.getMaxSectionY(), cz + r);
        remeshes++;
    }

    private static volatile boolean forceIsland = false;

    /** Testkit: treat the world as a mining island (no Skyblock in the harness). */
    public static void forceIslandForTest(boolean v) {
        forceIsland = v;
    }

    public static boolean isOn() {
        return on;
    }

    public static long darkenedCount() {
        return darkened.get();
    }

    public static int remeshCount() {
        return remeshes;
    }
}
