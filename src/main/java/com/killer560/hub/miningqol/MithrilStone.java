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
 * <p>When it is on: the setting is on, the player is on the Dwarven Mines island and the tab list shows his Commissions.
 * Then each mithril block is decided by the area THE BLOCK is in (killer560, 2026-11-03: "not based off of my location
 * but the blocks location"), from {@link MinesAreaMap}: it stays mithril when an open commission wants mithril or
 * titanium there ("Upper Mines Mithril" for a block in the Upper Mines) or anywhere ("Mithril Miner"), and is drawn as
 * stone otherwise. A block whose area the map does not know takes the player's area (action bar, else sidebar), as the
 * whole view did before. Each time the decision changes, the sections around him are re-meshed.
 */
public final class MithrilStone {

    /** Dwarven Mines mithril: gray and light blue wool, the three prismarines, cyan terracotta (as Skyblocker's
     *  PickobulusHelper and CarpetHighlighter read them; the hypixelskyblock wiki's Mithril Ore page shows the same). */
    private static final Set<Block> MITHRIL = Set.of(McBlocks.GRAY_WOOL, McBlocks.LIGHT_BLUE_WOOL, Blocks.PRISMARINE,
            Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, McBlocks.CYAN_TERRACOTTA);

    /** The swap is on at all (setting, island, commissions known, no anywhere commission). */
    private static volatile boolean enabled = false;
    /** What a block of unknown area does: the player's area decides (the old whole-view answer). */
    private static volatile boolean active = false;
    private static volatile MiningState.Wanted wanted = new MiningState.Wanted(false, Set.of());
    private static String lastKey = "";
    private static volatile BlockState stone;
    private static volatile boolean dirtyRequested = false;
    private static final AtomicLong swapped = new AtomicLong();
    private static volatile boolean recordForTest = false;
    private static final java.util.Set<Long> SWAPPED_AT = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int remeshes = 0;

    private MithrilStone() {
    }

    /**
     * Called from the chunk mesher (any thread): the state to draw instead of {@code state} at {@code x, y, z}, or null
     * to draw it as is.
     */
    public static BlockState replace(BlockState state, int x, int y, int z) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED
                && com.killer560.hub.dungeonextras.BreakerAuraFeature.drawnAsAir(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState red = MiningHighlights.carpetReplace(state, x, y, z);
        if (red != null) return red;
        if (!enabled || state == null || !MITHRIL.contains(state.getBlock()) || !stoneAt(x, y, z)) return null;
        swapped.incrementAndGet();
        if (recordForTest) {
            SWAPPED_AT.add(BlockPosKey.of(x, y, z));
        }
        BlockState s = stone;
        if (s == null) {
            s = Blocks.STONE.defaultBlockState();
            stone = s;
        }
        return s;
    }

    public static boolean isMithril(BlockState state) { return state != null && MITHRIL.contains(state.getBlock()); }

    /** Config saved or loaded: re-decide on the next tick. */
    static void settingsChanged() { dirtyRequested = true; }

    /** Whether a mithril block at x, y, z is drawn as stone now (its own area, else the player's). Any thread. */
    public static boolean stoneAt(int x, int y, int z) {
        if (!enabled) return false;
        String a = MinesAreaMap.areaAt(x, y, z);
        // A block the map can place is judged by its own area. One it cannot place (no map yet) takes the player's area; with
        // a map loaded an unplaceable block is not wanted: standing in an area never makes the blocks around it that area's.
        if (a == null) return MinesAreaMap.loaded() ? true : active;
        return !wanted.at(a, null);
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        boolean on = cfg.isMithrilStone() && MiningState.dwarvenMines() && MiningState.commissionsKnown();
        MiningState.Wanted w = MiningState.wanted("Mithril", true);
        boolean en = on && !w.anywhere();
        boolean fallback = en && !MiningState.openCommissionHere("Mithril", true);
        String key = en + "|" + fallback + "|" + w.areas() + "|" + MinesAreaMap.version();
        wanted = w;
        enabled = en;
        active = fallback;
        if (!key.equals(lastKey) || dirtyRequested) {
            boolean changed = !key.equals(lastKey);
            lastKey = key;
            dirtyRequested = false;
            if (changed) {
                remesh(client);
            }
        }
    }

    /** Marks every section within render distance for a rebuild (on the client thread). */
    private static void remesh(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) return;
        int r = client.options.getEffectiveRenderDistance() + 1;
        int cx = client.player.blockPosition().getX() >> 4;
        int cz = client.player.blockPosition().getZ() >> 4;
        level.setSectionRangeDirty(cx - r, level.getMinSectionY(), cz - r, cx + r, level.getMaxSectionY(), cz + r);
        remeshes++;
    }

    /** Whether a block of unknown area would be stone now (the player's area decides; the old whole-view answer). */
    public static boolean isActive() { return enabled && active; }

    /** Whether any mithril may be drawn as stone (setting, island, commissions, no anywhere commission). */
    public static boolean isEnabled() { return enabled; }

    /** The area the map gives the block at x, y, z, or null (testkit). */
    public static String areaOf(int x, int y, int z) { return MinesAreaMap.areaAt(x, y, z); }

    /** How many block draws were swapped to stone since start (testkit: the mixin acted). */
    public static long swappedCount() { return swapped.get(); }

    /** Testkit: record each position the mesher swaps (off by default; on clears the record). */
    public static void recordSwapsForTest(boolean on) {
        SWAPPED_AT.clear();
        recordForTest = on;
    }

    /** Testkit: whether the mesher drew the block at x, y, z as stone since recording started. */
    public static boolean swappedAt(int x, int y, int z) { return SWAPPED_AT.contains(BlockPosKey.of(x, y, z)); }

    /** Testkit: re-mesh the view now. */
    public static void remeshForTest() { remesh(Minecraft.getInstance()); }

    private static final class BlockPosKey {
        static long of(int x, int y, int z) { return net.minecraft.core.BlockPos.asLong(x, y, z); }
    }
}
