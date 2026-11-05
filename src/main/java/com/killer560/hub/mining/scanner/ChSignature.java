package com.killer560.hub.mining.scanner;

import com.killer560.hub.compat.McBlocks;
import com.killer560.hub.mining.chmap.ChStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Crystal Hollows structures the Structure Scanner knows, each by a vertical column of blocks that only it has (null =
 * any block), the part of the Hollows it can be in, and the offset from the column's bottom to the spot worth marking.
 * The column facts are the ones QUOI's {@code CrystalHollowsScanner} uses (a local reference copy; the facts only, not
 * its code), as are the quarter bounds.
 */
public enum ChSignature {

    GOBLIN_QUEEN("Goblin Queen's Den", ChStructure.GOBLIN_QUEENS_DEN, Quarter.GOBLIN_HOLDOUT, 0, 5, 0, false,
            Blocks.STONE, Blocks.ACACIA_LOG, Blocks.ACACIA_LOG, Blocks.ACACIA_LOG, Blocks.CAULDRON, Blocks.FIRE),
    MINES_OF_DIVAN("Mines of Divan", ChStructure.MINES_OF_DIVAN, Quarter.MITHRIL_DEPOSITS, 0, 5, 0, false,
            Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS),
    PRECURSOR_CITY("Lost Precursor City", ChStructure.LOST_PRECURSOR_CITY, Quarter.PRECURSOR_REMNANTS, 24, 0, -17, false,
            Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS,
            Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS),
    JUNGLE_TEMPLE("Jungle Temple", ChStructure.JUNGLE_TEMPLE, Quarter.JUNGLE, -45, 47, -18, false,
            Blocks.BEDROCK, Blocks.BEDROCK, Blocks.CLAY, Blocks.CLAY),
    GOBLIN_KING("Goblin King", ChStructure.GOBLIN_KING, Quarter.GOBLIN_HOLDOUT, 1, -1, 2, false,
            McBlocks.RED_WOOL, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS),
    BAL("Bal (Khazad-dûm)", ChStructure.KHAZAD_DUM, Quarter.MAGMA_FIELDS, 0, 1, 0, false,
            Blocks.LAVA, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER,
            Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER),
    CORLEONE_DOCK("Corleone Dock", ChStructure.CORLEONE, Quarter.MITHRIL_DEPOSITS, 23, 11, 17, false,
            Blocks.POLISHED_GRANITE, Blocks.WATER, Blocks.WATER, null, null, null, null, null, null, null,
            Blocks.STONE, McBlocks.CYAN_TERRACOTTA, Blocks.STONE_BRICKS, Blocks.STONE, Blocks.FIRE),
    KEY_GUARDIAN("Key Guardian Spiral", ChStructure.KEY_GUARDIAN, Quarter.JUNGLE, 0, 0, 0, false,
            Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE, McBlocks.WHITE_CARPET, null,
            Blocks.JUNGLE_SLAB, null, Blocks.JUNGLE_STAIRS, Blocks.STONE, Blocks.STONE, Blocks.STONE),
    ODAWA("Odawa", ChStructure.ODAWA, Quarter.JUNGLE, 0, 0, 0, false,
            Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS,
            Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LOG, Blocks.HAY_BLOCK,
            McBlocks.YELLOW_TERRACOTTA),
    GOLDEN_DRAGON("Golden Dragon", ChStructure.DRAGONS_LAIR, Quarter.ANY, 0, -3, 5, false,
            Blocks.STONE, McBlocks.RED_TERRACOTTA, McBlocks.RED_TERRACOTTA, McBlocks.RED_TERRACOTTA,
            Blocks.SKELETON_SKULL, McBlocks.RED_WOOL),
    FAIRY_GROTTO("Fairy Grotto", ChStructure.FAIRY_GROTTO, Quarter.ANY, 0, 0, 0, true,
            McBlocks.MAGENTA_STAINED_GLASS);

    enum Quarter {
        JUNGLE(p -> p.getX() <= 576 && p.getZ() <= 576),
        PRECURSOR_REMNANTS(p -> p.getX() > 448 && p.getZ() > 448),
        GOBLIN_HOLDOUT(p -> p.getX() <= 576 && p.getZ() > 448),
        MITHRIL_DEPOSITS(p -> p.getX() > 448 && p.getZ() <= 576),
        MAGMA_FIELDS(p -> p.getY() < 80),
        ANY(p -> true);

        final Predicate<BlockPos> test;

        Quarter(Predicate<BlockPos> test) {
            this.test = test;
        }
    }

    public final String displayName;
    /** The map's structure this marks. */
    public final ChStructure mapStructure;
    final Quarter quarter;
    final int dx;
    final int dy;
    final int dz;
    /** Whether a lobby can have several (grottos); each is kept 4 chunks from the next. */
    final boolean multiple;
    final List<Block> column;
    /** The column's blocks that mark this structure (not terrain every lobby has): where it extends, the box of these. */
    final java.util.Set<Block> marks;

    ChSignature(String displayName, ChStructure mapStructure, Quarter quarter, int dx, int dy, int dz, boolean multiple,
                Block... column) {
        this.displayName = displayName;
        this.mapStructure = mapStructure;
        this.quarter = quarter;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.multiple = multiple;
        this.column = Arrays.asList(column);
        java.util.Set<Block> m = new java.util.HashSet<>(this.column);
        m.remove(null);
        m.removeAll(List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.LAVA, Blocks.WATER, Blocks.FIRE, Blocks.BEDROCK,
                Blocks.CLAY, Blocks.JUNGLE_LOG));
        this.marks = java.util.Collections.unmodifiableSet(m);
    }
}
