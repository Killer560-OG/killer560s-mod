package com.killer560.hub.commissioncolors;

import com.killer560.hub.compat.McBlocks;
import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.miningqol.MithrilStone;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Commission Block Colors (legit, OFF by default): while a collector commission is open (tab "Commissions:" widget, not
 * DONE), the blocks that count for it stay as they are or take a highlight color (solid ore as wool, gemstones as stained
 * glass of the dye nearest the Highlight Color), and every other ore or gemstone of the table is dimmed (solid ore drawn as
 * stone, gemstone glass and panes as gray glass). Render only, through the chunk mesher hook Mithril As Stone owns
 * ({@code MithrilStone.replace} calls {@link #replace} for a block it leaves alone); the world is never changed.
 *
 * <p>Cost: the open-commission mask is worked out only when {@link MiningState#version()} or a setting changes (one
 * section re-mesh then); a mesher decision is one HashMap lookup and an int test, nothing allocated; the replacement states
 * are built once per change. Works in the Dwarven Mines, Glacite Tunnels, Crystal Hollows and a Mineshaft. Names are matched
 * tolerantly: "<Ore> Collector" / "<Gem> Gemstone Collector" for the collectors, "Mithril" / "Titanium" inside a name for the
 * Dwarven miners (every area's mithril counts, not just the commission's own).
 */
public final class CommissionColors {

    private enum Ore {
        MITHRIL("mithril"), TITANIUM("titanium"), GLACITE("glacite"), UMBER("umber"), TUNGSTEN("tungsten"),
        RUBY("ruby"), AMBER("amber"), AMETHYST("amethyst"), JADE("jade"), SAPPHIRE("sapphire"), TOPAZ("topaz"),
        AQUAMARINE("aquamarine"), CITRINE("citrine"), ONYX("onyx"), PERIDOT("peridot");

        final String key;

        Ore(String key) {
            this.key = key;
        }
    }

    private static final int SOLID = 0, GLASS = 1, PANE = 2;
    /** Indexes into {@link #states}: dimmed at 0..2, highlighted at 3..5. */
    private static final int HL = 3;

    /** What one block means: its ore's mask bit, whether it may be dimmed, and how it is replaced. */
    private record Hit(int bit, boolean dim, int kind) {
    }

    private static final Map<Block, Hit> BLOCKS = new HashMap<>();

    static {
        solid(Ore.MITHRIL, true, McBlocks.GRAY_WOOL, McBlocks.LIGHT_BLUE_WOOL, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS,
                Blocks.DARK_PRISMARINE, McBlocks.CYAN_TERRACOTTA);
        solid(Ore.TITANIUM, true, Blocks.POLISHED_DIORITE);
        solid(Ore.GLACITE, true, Blocks.PACKED_ICE);
        solid(Ore.UMBER, true, Blocks.TERRACOTTA, McBlocks.BROWN_TERRACOTTA, Blocks.SMOOTH_RED_SANDSTONE);
        solid(Ore.TUNGSTEN, true, Blocks.INFESTED_COBBLESTONE, Blocks.CLAY);
        // Plain cobblestone is everywhere: it counts for Tungsten but is never dimmed.
        solid(Ore.TUNGSTEN, false, Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_STAIRS);
        gem(Ore.RUBY, DyeColor.RED);
        gem(Ore.AMBER, DyeColor.ORANGE);
        gem(Ore.AMETHYST, DyeColor.PURPLE);
        gem(Ore.JADE, DyeColor.LIME);
        gem(Ore.SAPPHIRE, DyeColor.LIGHT_BLUE);
        gem(Ore.TOPAZ, DyeColor.YELLOW);
        gem(Ore.AQUAMARINE, DyeColor.BLUE);
        gem(Ore.CITRINE, DyeColor.BROWN);
        gem(Ore.ONYX, DyeColor.BLACK);
        gem(Ore.PERIDOT, DyeColor.GREEN);
    }

    private static void solid(Ore o, boolean dim, Block... blocks) {
        for (Block b : blocks) BLOCKS.put(b, new Hit(1 << o.ordinal(), dim, SOLID));
    }

    private static void gem(Ore o, DyeColor c) {
        BLOCKS.put(Blocks.STAINED_GLASS.pick(c), new Hit(1 << o.ordinal(), true, GLASS));
        BLOCKS.put(Blocks.STAINED_GLASS_PANE.pick(c), new Hit(1 << o.ordinal(), true, PANE));
    }

    private static volatile boolean active = false;
    private static volatile int mask = 0;
    private static volatile boolean highlight = true;
    private static volatile boolean dim = true;
    private static volatile BlockState[] states = new BlockState[6];
    private static int lastVersion = -1, lastGeneration = -1, lastColor;
    private static boolean lastOn = false;
    private static int remeshes = 0;

    private static final AtomicLong swapped = new AtomicLong();
    private static volatile boolean recordForTest = false;
    private static final Map<Long, Block> SWAPPED_AT = new ConcurrentHashMap<>();

    private CommissionColors() {
    }

    /** Mesher thread: the state to draw instead of {@code state} at x, y, z, or null for as is. */
    public static BlockState replace(BlockState state, int x, int y, int z) {
        if (!active || state == null) return null;
        Hit h = BLOCKS.get(state.getBlock());
        if (h == null) return null;
        BlockState r = null;
        if ((mask & h.bit) != 0) {
            if (highlight) r = states[HL + h.kind];
        } else if (dim && h.dim) {
            r = states[h.kind];
        }
        if (r != null) {
            swapped.incrementAndGet();
            if (recordForTest) SWAPPED_AT.put(BlockPos.asLong(x, y, z), r.getBlock());
        }
        return r;
    }

    /** Client tick: nothing but two int compares unless the switch is on or was just turned off. */
    public static void tick(Minecraft client) {
        CommissionColorsConfig cfg = CommissionColorsConfig.getInstance();
        boolean on = cfg.isEnabled() && MiningState.commissionsKnown()
                && (MiningState.dwarvenMines() || MiningState.crystalHollows() || MiningState.mineshaft());
        int v = MiningState.version(), g = CommissionColorsConfig.generation();
        if (on == lastOn && (!on || (v == lastVersion && g == lastGeneration))) return;
        lastOn = on;
        lastVersion = v;
        lastGeneration = g;
        int m = on ? maskOf(MiningState.commissions()) : 0;
        boolean act = m != 0, hl = cfg.isHighlight(), dm = cfg.isDimOthers();
        int col = cfg.getColor();
        // A percent moving or another commission's change leaves the picture as it was: no re-mesh.
        if (m == mask && (!act || (hl == highlight && dm == dim && col == lastColor))) return;
        if (act) {
            highlight = hl;
            dim = dm;
            lastColor = col;
            states = build(col);
        }
        mask = m;
        active = act;
        MithrilStone.remesh(client);
        remeshes++;
    }

    /** The ores of every open (not DONE) commission in the list. */
    static int maskOf(List<MiningState.Commission> coms) {
        int m = 0;
        for (MiningState.Commission c : coms) {
            if (c.done()) continue;
            String n = c.name().toLowerCase(Locale.ROOT);
            if (n.contains("collector")) {
                for (Ore o : Ore.values()) {
                    if (o.ordinal() >= Ore.GLACITE.ordinal() && n.contains(o.key)) m |= 1 << o.ordinal();
                }
            } else if (n.contains("mithril")) {
                m |= 1 << Ore.MITHRIL.ordinal();
            } else if (n.contains("titanium")) {
                m |= 1 << Ore.TITANIUM.ordinal();
            }
        }
        return m;
    }

    /** Dimmed (stone, gray glass, gray pane) then highlighted (wool, glass, pane of the dye nearest the color). */
    private static BlockState[] build(int argb) {
        DyeColor best = DyeColor.WHITE;
        long bestD = Long.MAX_VALUE;
        for (DyeColor d : DyeColor.values()) {
            int c = d.getTextureDiffuseColor();
            long dr = ((argb >> 16) & 255) - ((c >> 16) & 255), dg = ((argb >> 8) & 255) - ((c >> 8) & 255),
                    db = (argb & 255) - (c & 255);
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.STAINED_GLASS.pick(DyeColor.GRAY).defaultBlockState(),
                Blocks.STAINED_GLASS_PANE.pick(DyeColor.GRAY).defaultBlockState(),
                Blocks.WOOL.pick(best).defaultBlockState(), Blocks.STAINED_GLASS.pick(best).defaultBlockState(),
                Blocks.STAINED_GLASS_PANE.pick(best).defaultBlockState()};
    }

    /** Whether the swap is on and any ore is wanted (testkit). */
    public static boolean isActive() { return active; }

    /** Bit mask of the ores the open commissions want, by {@code Ore} ordinal (testkit). */
    public static int wantedMask() { return mask; }

    /** Section re-meshes this feature asked for since start (testkit). */
    public static int remeshCount() { return remeshes; }

    /** Mesher draws swapped since start (testkit: the hook acted). */
    public static long swappedCount() { return swapped.get(); }

    /** Testkit: record the block each swapped position was drawn as (on clears the record). */
    public static void recordForTest(boolean on) {
        SWAPPED_AT.clear();
        recordForTest = on;
    }

    /** Testkit: the block the mesher drew at x, y, z in place of its own since recording started, or null. */
    public static Block drawnAt(int x, int y, int z) { return SWAPPED_AT.get(BlockPos.asLong(x, y, z)); }
}
