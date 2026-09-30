package com.killer560.hub.compat;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Coloured blocks that moved between Minecraft versions.
 *
 * <p>GENERATED SHAPE, hand-maintained: every version's copy is produced from the same list so the two cannot
 * drift, which is the one failure mode a facade like this has. Add a constant to one and you must add it to
 * all, or a clean compile on one Minecraft version becomes a break on another.
 *
 * <p>26.2 collapsed the sixteen coloured variants of each family into a {@code ColorCollection} - so
 * {@code Blocks.RED_TERRACOTTA} became {@code Blocks.DYED_TERRACOTTA.pick(DyeColor.RED)}, and the class went from 1147 static fields to 881.
 * The registry ids are untouched (a {@code ColorCollection}'s ids are still {@code <colour>_<base>}), which
 * matters because room captures store block states as strings and are shared between versions over the relay.
 */
public final class McBlocks {

    private McBlocks() {
    }

    // carpet
    public static final Block WHITE_CARPET = Blocks.CARPET.pick(DyeColor.WHITE);
    public static final Block ORANGE_CARPET = Blocks.CARPET.pick(DyeColor.ORANGE);
    public static final Block MAGENTA_CARPET = Blocks.CARPET.pick(DyeColor.MAGENTA);
    public static final Block LIGHT_BLUE_CARPET = Blocks.CARPET.pick(DyeColor.LIGHT_BLUE);
    public static final Block YELLOW_CARPET = Blocks.CARPET.pick(DyeColor.YELLOW);
    public static final Block LIME_CARPET = Blocks.CARPET.pick(DyeColor.LIME);
    public static final Block PINK_CARPET = Blocks.CARPET.pick(DyeColor.PINK);
    public static final Block GRAY_CARPET = Blocks.CARPET.pick(DyeColor.GRAY);
    public static final Block LIGHT_GRAY_CARPET = Blocks.CARPET.pick(DyeColor.LIGHT_GRAY);
    public static final Block CYAN_CARPET = Blocks.CARPET.pick(DyeColor.CYAN);
    public static final Block PURPLE_CARPET = Blocks.CARPET.pick(DyeColor.PURPLE);
    public static final Block BLUE_CARPET = Blocks.CARPET.pick(DyeColor.BLUE);
    public static final Block BROWN_CARPET = Blocks.CARPET.pick(DyeColor.BROWN);
    public static final Block GREEN_CARPET = Blocks.CARPET.pick(DyeColor.GREEN);
    public static final Block RED_CARPET = Blocks.CARPET.pick(DyeColor.RED);
    public static final Block BLACK_CARPET = Blocks.CARPET.pick(DyeColor.BLACK);

    // concrete
    public static final Block WHITE_CONCRETE = Blocks.CONCRETE.pick(DyeColor.WHITE);
    public static final Block ORANGE_CONCRETE = Blocks.CONCRETE.pick(DyeColor.ORANGE);
    public static final Block MAGENTA_CONCRETE = Blocks.CONCRETE.pick(DyeColor.MAGENTA);
    public static final Block LIGHT_BLUE_CONCRETE = Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE);
    public static final Block YELLOW_CONCRETE = Blocks.CONCRETE.pick(DyeColor.YELLOW);
    public static final Block LIME_CONCRETE = Blocks.CONCRETE.pick(DyeColor.LIME);
    public static final Block PINK_CONCRETE = Blocks.CONCRETE.pick(DyeColor.PINK);
    public static final Block GRAY_CONCRETE = Blocks.CONCRETE.pick(DyeColor.GRAY);
    public static final Block LIGHT_GRAY_CONCRETE = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY);
    public static final Block CYAN_CONCRETE = Blocks.CONCRETE.pick(DyeColor.CYAN);
    public static final Block PURPLE_CONCRETE = Blocks.CONCRETE.pick(DyeColor.PURPLE);
    public static final Block BLUE_CONCRETE = Blocks.CONCRETE.pick(DyeColor.BLUE);
    public static final Block BROWN_CONCRETE = Blocks.CONCRETE.pick(DyeColor.BROWN);
    public static final Block GREEN_CONCRETE = Blocks.CONCRETE.pick(DyeColor.GREEN);
    public static final Block RED_CONCRETE = Blocks.CONCRETE.pick(DyeColor.RED);
    public static final Block BLACK_CONCRETE = Blocks.CONCRETE.pick(DyeColor.BLACK);

    // stained glass
    public static final Block WHITE_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.WHITE);
    public static final Block ORANGE_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.ORANGE);
    public static final Block MAGENTA_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.MAGENTA);
    public static final Block LIGHT_BLUE_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE);
    public static final Block YELLOW_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.YELLOW);
    public static final Block LIME_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.LIME);
    public static final Block PINK_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.PINK);
    public static final Block GRAY_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.GRAY);
    public static final Block LIGHT_GRAY_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_GRAY);
    public static final Block CYAN_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.CYAN);
    public static final Block PURPLE_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.PURPLE);
    public static final Block BLUE_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.BLUE);
    public static final Block BROWN_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.BROWN);
    public static final Block GREEN_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.GREEN);
    public static final Block RED_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.RED);
    public static final Block BLACK_STAINED_GLASS = Blocks.STAINED_GLASS.pick(DyeColor.BLACK);

    // stained glass pane
    public static final Block WHITE_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.WHITE);
    public static final Block ORANGE_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.ORANGE);
    public static final Block MAGENTA_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA);
    public static final Block LIGHT_BLUE_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_BLUE);
    public static final Block YELLOW_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.YELLOW);
    public static final Block LIME_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.LIME);
    public static final Block PINK_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.PINK);
    public static final Block GRAY_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.GRAY);
    public static final Block LIGHT_GRAY_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_GRAY);
    public static final Block CYAN_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.CYAN);
    public static final Block PURPLE_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.PURPLE);
    public static final Block BLUE_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.BLUE);
    public static final Block BROWN_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.BROWN);
    public static final Block GREEN_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.GREEN);
    public static final Block RED_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.RED);
    public static final Block BLACK_STAINED_GLASS_PANE = Blocks.STAINED_GLASS_PANE.pick(DyeColor.BLACK);

    // terracotta
    public static final Block WHITE_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE);
    public static final Block ORANGE_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE);
    public static final Block MAGENTA_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.MAGENTA);
    public static final Block LIGHT_BLUE_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_BLUE);
    public static final Block YELLOW_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.YELLOW);
    public static final Block LIME_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.LIME);
    public static final Block PINK_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.PINK);
    public static final Block GRAY_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.GRAY);
    public static final Block LIGHT_GRAY_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_GRAY);
    public static final Block CYAN_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.CYAN);
    public static final Block PURPLE_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.PURPLE);
    public static final Block BLUE_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE);
    public static final Block BROWN_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.BROWN);
    public static final Block GREEN_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.GREEN);
    public static final Block RED_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.RED);
    public static final Block BLACK_TERRACOTTA = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLACK);

    // wool
    public static final Block WHITE_WOOL = Blocks.WOOL.pick(DyeColor.WHITE);
    public static final Block ORANGE_WOOL = Blocks.WOOL.pick(DyeColor.ORANGE);
    public static final Block MAGENTA_WOOL = Blocks.WOOL.pick(DyeColor.MAGENTA);
    public static final Block LIGHT_BLUE_WOOL = Blocks.WOOL.pick(DyeColor.LIGHT_BLUE);
    public static final Block YELLOW_WOOL = Blocks.WOOL.pick(DyeColor.YELLOW);
    public static final Block LIME_WOOL = Blocks.WOOL.pick(DyeColor.LIME);
    public static final Block PINK_WOOL = Blocks.WOOL.pick(DyeColor.PINK);
    public static final Block GRAY_WOOL = Blocks.WOOL.pick(DyeColor.GRAY);
    public static final Block LIGHT_GRAY_WOOL = Blocks.WOOL.pick(DyeColor.LIGHT_GRAY);
    public static final Block CYAN_WOOL = Blocks.WOOL.pick(DyeColor.CYAN);
    public static final Block PURPLE_WOOL = Blocks.WOOL.pick(DyeColor.PURPLE);
    public static final Block BLUE_WOOL = Blocks.WOOL.pick(DyeColor.BLUE);
    public static final Block BROWN_WOOL = Blocks.WOOL.pick(DyeColor.BROWN);
    public static final Block GREEN_WOOL = Blocks.WOOL.pick(DyeColor.GREEN);
    public static final Block RED_WOOL = Blocks.WOOL.pick(DyeColor.RED);
    public static final Block BLACK_WOOL = Blocks.WOOL.pick(DyeColor.BLACK);
}
