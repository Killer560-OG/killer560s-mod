package com.killer560.hub.compat;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Coloured items and dyes that moved between Minecraft versions.
 *
 * <p>GENERATED SHAPE, hand-maintained: every version's copy is produced from the same list so the two cannot
 * drift, which is the one failure mode a facade like this has. Add a constant to one and you must add it to
 * all, or a clean compile on one Minecraft version becomes a break on another.
 *
 * <p>26.2 collapsed the sixteen coloured variants of each family into a {@code ColorCollection} - so
 * {@code Items.RED_DYE} became {@code Items.DYE.pick(DyeColor.RED)}, and the class went from 1147 static fields to 881.
 * The registry ids are untouched (a {@code ColorCollection}'s ids are still {@code <colour>_<base>}), which
 * matters because room captures store block states as strings and are shared between versions over the relay.
 */
public final class McItems {

    private McItems() {
    }

    // carpet
    public static final Item WHITE_CARPET = Items.WHITE_CARPET;
    public static final Item ORANGE_CARPET = Items.ORANGE_CARPET;
    public static final Item MAGENTA_CARPET = Items.MAGENTA_CARPET;
    public static final Item LIGHT_BLUE_CARPET = Items.LIGHT_BLUE_CARPET;
    public static final Item YELLOW_CARPET = Items.YELLOW_CARPET;
    public static final Item LIME_CARPET = Items.LIME_CARPET;
    public static final Item PINK_CARPET = Items.PINK_CARPET;
    public static final Item GRAY_CARPET = Items.GRAY_CARPET;
    public static final Item LIGHT_GRAY_CARPET = Items.LIGHT_GRAY_CARPET;
    public static final Item CYAN_CARPET = Items.CYAN_CARPET;
    public static final Item PURPLE_CARPET = Items.PURPLE_CARPET;
    public static final Item BLUE_CARPET = Items.BLUE_CARPET;
    public static final Item BROWN_CARPET = Items.BROWN_CARPET;
    public static final Item GREEN_CARPET = Items.GREEN_CARPET;
    public static final Item RED_CARPET = Items.RED_CARPET;
    public static final Item BLACK_CARPET = Items.BLACK_CARPET;

    // concrete
    public static final Item WHITE_CONCRETE = Items.WHITE_CONCRETE;
    public static final Item ORANGE_CONCRETE = Items.ORANGE_CONCRETE;
    public static final Item MAGENTA_CONCRETE = Items.MAGENTA_CONCRETE;
    public static final Item LIGHT_BLUE_CONCRETE = Items.LIGHT_BLUE_CONCRETE;
    public static final Item YELLOW_CONCRETE = Items.YELLOW_CONCRETE;
    public static final Item LIME_CONCRETE = Items.LIME_CONCRETE;
    public static final Item PINK_CONCRETE = Items.PINK_CONCRETE;
    public static final Item GRAY_CONCRETE = Items.GRAY_CONCRETE;
    public static final Item LIGHT_GRAY_CONCRETE = Items.LIGHT_GRAY_CONCRETE;
    public static final Item CYAN_CONCRETE = Items.CYAN_CONCRETE;
    public static final Item PURPLE_CONCRETE = Items.PURPLE_CONCRETE;
    public static final Item BLUE_CONCRETE = Items.BLUE_CONCRETE;
    public static final Item BROWN_CONCRETE = Items.BROWN_CONCRETE;
    public static final Item GREEN_CONCRETE = Items.GREEN_CONCRETE;
    public static final Item RED_CONCRETE = Items.RED_CONCRETE;
    public static final Item BLACK_CONCRETE = Items.BLACK_CONCRETE;

    // dye
    public static final Item WHITE_DYE = Items.WHITE_DYE;
    public static final Item ORANGE_DYE = Items.ORANGE_DYE;
    public static final Item MAGENTA_DYE = Items.MAGENTA_DYE;
    public static final Item LIGHT_BLUE_DYE = Items.LIGHT_BLUE_DYE;
    public static final Item YELLOW_DYE = Items.YELLOW_DYE;
    public static final Item LIME_DYE = Items.LIME_DYE;
    public static final Item PINK_DYE = Items.PINK_DYE;
    public static final Item GRAY_DYE = Items.GRAY_DYE;
    public static final Item LIGHT_GRAY_DYE = Items.LIGHT_GRAY_DYE;
    public static final Item CYAN_DYE = Items.CYAN_DYE;
    public static final Item PURPLE_DYE = Items.PURPLE_DYE;
    public static final Item BLUE_DYE = Items.BLUE_DYE;
    public static final Item BROWN_DYE = Items.BROWN_DYE;
    public static final Item GREEN_DYE = Items.GREEN_DYE;
    public static final Item RED_DYE = Items.RED_DYE;
    public static final Item BLACK_DYE = Items.BLACK_DYE;

    // stained glass
    public static final Item WHITE_STAINED_GLASS = Items.WHITE_STAINED_GLASS;
    public static final Item ORANGE_STAINED_GLASS = Items.ORANGE_STAINED_GLASS;
    public static final Item MAGENTA_STAINED_GLASS = Items.MAGENTA_STAINED_GLASS;
    public static final Item LIGHT_BLUE_STAINED_GLASS = Items.LIGHT_BLUE_STAINED_GLASS;
    public static final Item YELLOW_STAINED_GLASS = Items.YELLOW_STAINED_GLASS;
    public static final Item LIME_STAINED_GLASS = Items.LIME_STAINED_GLASS;
    public static final Item PINK_STAINED_GLASS = Items.PINK_STAINED_GLASS;
    public static final Item GRAY_STAINED_GLASS = Items.GRAY_STAINED_GLASS;
    public static final Item LIGHT_GRAY_STAINED_GLASS = Items.LIGHT_GRAY_STAINED_GLASS;
    public static final Item CYAN_STAINED_GLASS = Items.CYAN_STAINED_GLASS;
    public static final Item PURPLE_STAINED_GLASS = Items.PURPLE_STAINED_GLASS;
    public static final Item BLUE_STAINED_GLASS = Items.BLUE_STAINED_GLASS;
    public static final Item BROWN_STAINED_GLASS = Items.BROWN_STAINED_GLASS;
    public static final Item GREEN_STAINED_GLASS = Items.GREEN_STAINED_GLASS;
    public static final Item RED_STAINED_GLASS = Items.RED_STAINED_GLASS;
    public static final Item BLACK_STAINED_GLASS = Items.BLACK_STAINED_GLASS;

    // stained glass pane
    public static final Item WHITE_STAINED_GLASS_PANE = Items.WHITE_STAINED_GLASS_PANE;
    public static final Item ORANGE_STAINED_GLASS_PANE = Items.ORANGE_STAINED_GLASS_PANE;
    public static final Item MAGENTA_STAINED_GLASS_PANE = Items.MAGENTA_STAINED_GLASS_PANE;
    public static final Item LIGHT_BLUE_STAINED_GLASS_PANE = Items.LIGHT_BLUE_STAINED_GLASS_PANE;
    public static final Item YELLOW_STAINED_GLASS_PANE = Items.YELLOW_STAINED_GLASS_PANE;
    public static final Item LIME_STAINED_GLASS_PANE = Items.LIME_STAINED_GLASS_PANE;
    public static final Item PINK_STAINED_GLASS_PANE = Items.PINK_STAINED_GLASS_PANE;
    public static final Item GRAY_STAINED_GLASS_PANE = Items.GRAY_STAINED_GLASS_PANE;
    public static final Item LIGHT_GRAY_STAINED_GLASS_PANE = Items.LIGHT_GRAY_STAINED_GLASS_PANE;
    public static final Item CYAN_STAINED_GLASS_PANE = Items.CYAN_STAINED_GLASS_PANE;
    public static final Item PURPLE_STAINED_GLASS_PANE = Items.PURPLE_STAINED_GLASS_PANE;
    public static final Item BLUE_STAINED_GLASS_PANE = Items.BLUE_STAINED_GLASS_PANE;
    public static final Item BROWN_STAINED_GLASS_PANE = Items.BROWN_STAINED_GLASS_PANE;
    public static final Item GREEN_STAINED_GLASS_PANE = Items.GREEN_STAINED_GLASS_PANE;
    public static final Item RED_STAINED_GLASS_PANE = Items.RED_STAINED_GLASS_PANE;
    public static final Item BLACK_STAINED_GLASS_PANE = Items.BLACK_STAINED_GLASS_PANE;

    // terracotta
    public static final Item WHITE_TERRACOTTA = Items.WHITE_TERRACOTTA;
    public static final Item ORANGE_TERRACOTTA = Items.ORANGE_TERRACOTTA;
    public static final Item MAGENTA_TERRACOTTA = Items.MAGENTA_TERRACOTTA;
    public static final Item LIGHT_BLUE_TERRACOTTA = Items.LIGHT_BLUE_TERRACOTTA;
    public static final Item YELLOW_TERRACOTTA = Items.YELLOW_TERRACOTTA;
    public static final Item LIME_TERRACOTTA = Items.LIME_TERRACOTTA;
    public static final Item PINK_TERRACOTTA = Items.PINK_TERRACOTTA;
    public static final Item GRAY_TERRACOTTA = Items.GRAY_TERRACOTTA;
    public static final Item LIGHT_GRAY_TERRACOTTA = Items.LIGHT_GRAY_TERRACOTTA;
    public static final Item CYAN_TERRACOTTA = Items.CYAN_TERRACOTTA;
    public static final Item PURPLE_TERRACOTTA = Items.PURPLE_TERRACOTTA;
    public static final Item BLUE_TERRACOTTA = Items.BLUE_TERRACOTTA;
    public static final Item BROWN_TERRACOTTA = Items.BROWN_TERRACOTTA;
    public static final Item GREEN_TERRACOTTA = Items.GREEN_TERRACOTTA;
    public static final Item RED_TERRACOTTA = Items.RED_TERRACOTTA;
    public static final Item BLACK_TERRACOTTA = Items.BLACK_TERRACOTTA;

    // wool
    public static final Item WHITE_WOOL = Items.WHITE_WOOL;
    public static final Item ORANGE_WOOL = Items.ORANGE_WOOL;
    public static final Item MAGENTA_WOOL = Items.MAGENTA_WOOL;
    public static final Item LIGHT_BLUE_WOOL = Items.LIGHT_BLUE_WOOL;
    public static final Item YELLOW_WOOL = Items.YELLOW_WOOL;
    public static final Item LIME_WOOL = Items.LIME_WOOL;
    public static final Item PINK_WOOL = Items.PINK_WOOL;
    public static final Item GRAY_WOOL = Items.GRAY_WOOL;
    public static final Item LIGHT_GRAY_WOOL = Items.LIGHT_GRAY_WOOL;
    public static final Item CYAN_WOOL = Items.CYAN_WOOL;
    public static final Item PURPLE_WOOL = Items.PURPLE_WOOL;
    public static final Item BLUE_WOOL = Items.BLUE_WOOL;
    public static final Item BROWN_WOOL = Items.BROWN_WOOL;
    public static final Item GREEN_WOOL = Items.GREEN_WOOL;
    public static final Item RED_WOOL = Items.RED_WOOL;
    public static final Item BLACK_WOOL = Items.BLACK_WOOL;
}
