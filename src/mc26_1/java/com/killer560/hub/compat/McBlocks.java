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
    public static final Block WHITE_CARPET = Blocks.WHITE_CARPET;
    public static final Block ORANGE_CARPET = Blocks.ORANGE_CARPET;
    public static final Block MAGENTA_CARPET = Blocks.MAGENTA_CARPET;
    public static final Block LIGHT_BLUE_CARPET = Blocks.LIGHT_BLUE_CARPET;
    public static final Block YELLOW_CARPET = Blocks.YELLOW_CARPET;
    public static final Block LIME_CARPET = Blocks.LIME_CARPET;
    public static final Block PINK_CARPET = Blocks.PINK_CARPET;
    public static final Block GRAY_CARPET = Blocks.GRAY_CARPET;
    public static final Block LIGHT_GRAY_CARPET = Blocks.LIGHT_GRAY_CARPET;
    public static final Block CYAN_CARPET = Blocks.CYAN_CARPET;
    public static final Block PURPLE_CARPET = Blocks.PURPLE_CARPET;
    public static final Block BLUE_CARPET = Blocks.BLUE_CARPET;
    public static final Block BROWN_CARPET = Blocks.BROWN_CARPET;
    public static final Block GREEN_CARPET = Blocks.GREEN_CARPET;
    public static final Block RED_CARPET = Blocks.RED_CARPET;
    public static final Block BLACK_CARPET = Blocks.BLACK_CARPET;

    // concrete
    public static final Block WHITE_CONCRETE = Blocks.WHITE_CONCRETE;
    public static final Block ORANGE_CONCRETE = Blocks.ORANGE_CONCRETE;
    public static final Block MAGENTA_CONCRETE = Blocks.MAGENTA_CONCRETE;
    public static final Block LIGHT_BLUE_CONCRETE = Blocks.LIGHT_BLUE_CONCRETE;
    public static final Block YELLOW_CONCRETE = Blocks.YELLOW_CONCRETE;
    public static final Block LIME_CONCRETE = Blocks.LIME_CONCRETE;
    public static final Block PINK_CONCRETE = Blocks.PINK_CONCRETE;
    public static final Block GRAY_CONCRETE = Blocks.GRAY_CONCRETE;
    public static final Block LIGHT_GRAY_CONCRETE = Blocks.LIGHT_GRAY_CONCRETE;
    public static final Block CYAN_CONCRETE = Blocks.CYAN_CONCRETE;
    public static final Block PURPLE_CONCRETE = Blocks.PURPLE_CONCRETE;
    public static final Block BLUE_CONCRETE = Blocks.BLUE_CONCRETE;
    public static final Block BROWN_CONCRETE = Blocks.BROWN_CONCRETE;
    public static final Block GREEN_CONCRETE = Blocks.GREEN_CONCRETE;
    public static final Block RED_CONCRETE = Blocks.RED_CONCRETE;
    public static final Block BLACK_CONCRETE = Blocks.BLACK_CONCRETE;

    // stained glass
    public static final Block WHITE_STAINED_GLASS = Blocks.WHITE_STAINED_GLASS;
    public static final Block ORANGE_STAINED_GLASS = Blocks.ORANGE_STAINED_GLASS;
    public static final Block MAGENTA_STAINED_GLASS = Blocks.MAGENTA_STAINED_GLASS;
    public static final Block LIGHT_BLUE_STAINED_GLASS = Blocks.LIGHT_BLUE_STAINED_GLASS;
    public static final Block YELLOW_STAINED_GLASS = Blocks.YELLOW_STAINED_GLASS;
    public static final Block LIME_STAINED_GLASS = Blocks.LIME_STAINED_GLASS;
    public static final Block PINK_STAINED_GLASS = Blocks.PINK_STAINED_GLASS;
    public static final Block GRAY_STAINED_GLASS = Blocks.GRAY_STAINED_GLASS;
    public static final Block LIGHT_GRAY_STAINED_GLASS = Blocks.LIGHT_GRAY_STAINED_GLASS;
    public static final Block CYAN_STAINED_GLASS = Blocks.CYAN_STAINED_GLASS;
    public static final Block PURPLE_STAINED_GLASS = Blocks.PURPLE_STAINED_GLASS;
    public static final Block BLUE_STAINED_GLASS = Blocks.BLUE_STAINED_GLASS;
    public static final Block BROWN_STAINED_GLASS = Blocks.BROWN_STAINED_GLASS;
    public static final Block GREEN_STAINED_GLASS = Blocks.GREEN_STAINED_GLASS;
    public static final Block RED_STAINED_GLASS = Blocks.RED_STAINED_GLASS;
    public static final Block BLACK_STAINED_GLASS = Blocks.BLACK_STAINED_GLASS;

    // stained glass pane
    public static final Block WHITE_STAINED_GLASS_PANE = Blocks.WHITE_STAINED_GLASS_PANE;
    public static final Block ORANGE_STAINED_GLASS_PANE = Blocks.ORANGE_STAINED_GLASS_PANE;
    public static final Block MAGENTA_STAINED_GLASS_PANE = Blocks.MAGENTA_STAINED_GLASS_PANE;
    public static final Block LIGHT_BLUE_STAINED_GLASS_PANE = Blocks.LIGHT_BLUE_STAINED_GLASS_PANE;
    public static final Block YELLOW_STAINED_GLASS_PANE = Blocks.YELLOW_STAINED_GLASS_PANE;
    public static final Block LIME_STAINED_GLASS_PANE = Blocks.LIME_STAINED_GLASS_PANE;
    public static final Block PINK_STAINED_GLASS_PANE = Blocks.PINK_STAINED_GLASS_PANE;
    public static final Block GRAY_STAINED_GLASS_PANE = Blocks.GRAY_STAINED_GLASS_PANE;
    public static final Block LIGHT_GRAY_STAINED_GLASS_PANE = Blocks.LIGHT_GRAY_STAINED_GLASS_PANE;
    public static final Block CYAN_STAINED_GLASS_PANE = Blocks.CYAN_STAINED_GLASS_PANE;
    public static final Block PURPLE_STAINED_GLASS_PANE = Blocks.PURPLE_STAINED_GLASS_PANE;
    public static final Block BLUE_STAINED_GLASS_PANE = Blocks.BLUE_STAINED_GLASS_PANE;
    public static final Block BROWN_STAINED_GLASS_PANE = Blocks.BROWN_STAINED_GLASS_PANE;
    public static final Block GREEN_STAINED_GLASS_PANE = Blocks.GREEN_STAINED_GLASS_PANE;
    public static final Block RED_STAINED_GLASS_PANE = Blocks.RED_STAINED_GLASS_PANE;
    public static final Block BLACK_STAINED_GLASS_PANE = Blocks.BLACK_STAINED_GLASS_PANE;

    // terracotta
    public static final Block WHITE_TERRACOTTA = Blocks.WHITE_TERRACOTTA;
    public static final Block ORANGE_TERRACOTTA = Blocks.ORANGE_TERRACOTTA;
    public static final Block MAGENTA_TERRACOTTA = Blocks.MAGENTA_TERRACOTTA;
    public static final Block LIGHT_BLUE_TERRACOTTA = Blocks.LIGHT_BLUE_TERRACOTTA;
    public static final Block YELLOW_TERRACOTTA = Blocks.YELLOW_TERRACOTTA;
    public static final Block LIME_TERRACOTTA = Blocks.LIME_TERRACOTTA;
    public static final Block PINK_TERRACOTTA = Blocks.PINK_TERRACOTTA;
    public static final Block GRAY_TERRACOTTA = Blocks.GRAY_TERRACOTTA;
    public static final Block LIGHT_GRAY_TERRACOTTA = Blocks.LIGHT_GRAY_TERRACOTTA;
    public static final Block CYAN_TERRACOTTA = Blocks.CYAN_TERRACOTTA;
    public static final Block PURPLE_TERRACOTTA = Blocks.PURPLE_TERRACOTTA;
    public static final Block BLUE_TERRACOTTA = Blocks.BLUE_TERRACOTTA;
    public static final Block BROWN_TERRACOTTA = Blocks.BROWN_TERRACOTTA;
    public static final Block GREEN_TERRACOTTA = Blocks.GREEN_TERRACOTTA;
    public static final Block RED_TERRACOTTA = Blocks.RED_TERRACOTTA;
    public static final Block BLACK_TERRACOTTA = Blocks.BLACK_TERRACOTTA;

    // wool
    public static final Block WHITE_WOOL = Blocks.WHITE_WOOL;
    public static final Block ORANGE_WOOL = Blocks.ORANGE_WOOL;
    public static final Block MAGENTA_WOOL = Blocks.MAGENTA_WOOL;
    public static final Block LIGHT_BLUE_WOOL = Blocks.LIGHT_BLUE_WOOL;
    public static final Block YELLOW_WOOL = Blocks.YELLOW_WOOL;
    public static final Block LIME_WOOL = Blocks.LIME_WOOL;
    public static final Block PINK_WOOL = Blocks.PINK_WOOL;
    public static final Block GRAY_WOOL = Blocks.GRAY_WOOL;
    public static final Block LIGHT_GRAY_WOOL = Blocks.LIGHT_GRAY_WOOL;
    public static final Block CYAN_WOOL = Blocks.CYAN_WOOL;
    public static final Block PURPLE_WOOL = Blocks.PURPLE_WOOL;
    public static final Block BLUE_WOOL = Blocks.BLUE_WOOL;
    public static final Block BROWN_WOOL = Blocks.BROWN_WOOL;
    public static final Block GREEN_WOOL = Blocks.GREEN_WOOL;
    public static final Block RED_WOOL = Blocks.RED_WOOL;
    public static final Block BLACK_WOOL = Blocks.BLACK_WOOL;
}
