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
    public static final Item WHITE_CARPET = Items.CARPET.pick(DyeColor.WHITE);
    public static final Item ORANGE_CARPET = Items.CARPET.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_CARPET = Items.CARPET.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_CARPET = Items.CARPET.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_CARPET = Items.CARPET.pick(DyeColor.YELLOW);
    public static final Item LIME_CARPET = Items.CARPET.pick(DyeColor.LIME);
    public static final Item PINK_CARPET = Items.CARPET.pick(DyeColor.PINK);
    public static final Item GRAY_CARPET = Items.CARPET.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_CARPET = Items.CARPET.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_CARPET = Items.CARPET.pick(DyeColor.CYAN);
    public static final Item PURPLE_CARPET = Items.CARPET.pick(DyeColor.PURPLE);
    public static final Item BLUE_CARPET = Items.CARPET.pick(DyeColor.BLUE);
    public static final Item BROWN_CARPET = Items.CARPET.pick(DyeColor.BROWN);
    public static final Item GREEN_CARPET = Items.CARPET.pick(DyeColor.GREEN);
    public static final Item RED_CARPET = Items.CARPET.pick(DyeColor.RED);
    public static final Item BLACK_CARPET = Items.CARPET.pick(DyeColor.BLACK);

    // concrete
    public static final Item WHITE_CONCRETE = Items.CONCRETE.pick(DyeColor.WHITE);
    public static final Item ORANGE_CONCRETE = Items.CONCRETE.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_CONCRETE = Items.CONCRETE.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_CONCRETE = Items.CONCRETE.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_CONCRETE = Items.CONCRETE.pick(DyeColor.YELLOW);
    public static final Item LIME_CONCRETE = Items.CONCRETE.pick(DyeColor.LIME);
    public static final Item PINK_CONCRETE = Items.CONCRETE.pick(DyeColor.PINK);
    public static final Item GRAY_CONCRETE = Items.CONCRETE.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_CONCRETE = Items.CONCRETE.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_CONCRETE = Items.CONCRETE.pick(DyeColor.CYAN);
    public static final Item PURPLE_CONCRETE = Items.CONCRETE.pick(DyeColor.PURPLE);
    public static final Item BLUE_CONCRETE = Items.CONCRETE.pick(DyeColor.BLUE);
    public static final Item BROWN_CONCRETE = Items.CONCRETE.pick(DyeColor.BROWN);
    public static final Item GREEN_CONCRETE = Items.CONCRETE.pick(DyeColor.GREEN);
    public static final Item RED_CONCRETE = Items.CONCRETE.pick(DyeColor.RED);
    public static final Item BLACK_CONCRETE = Items.CONCRETE.pick(DyeColor.BLACK);

    // dye
    public static final Item WHITE_DYE = Items.DYE.pick(DyeColor.WHITE);
    public static final Item ORANGE_DYE = Items.DYE.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_DYE = Items.DYE.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_DYE = Items.DYE.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_DYE = Items.DYE.pick(DyeColor.YELLOW);
    public static final Item LIME_DYE = Items.DYE.pick(DyeColor.LIME);
    public static final Item PINK_DYE = Items.DYE.pick(DyeColor.PINK);
    public static final Item GRAY_DYE = Items.DYE.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_DYE = Items.DYE.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_DYE = Items.DYE.pick(DyeColor.CYAN);
    public static final Item PURPLE_DYE = Items.DYE.pick(DyeColor.PURPLE);
    public static final Item BLUE_DYE = Items.DYE.pick(DyeColor.BLUE);
    public static final Item BROWN_DYE = Items.DYE.pick(DyeColor.BROWN);
    public static final Item GREEN_DYE = Items.DYE.pick(DyeColor.GREEN);
    public static final Item RED_DYE = Items.DYE.pick(DyeColor.RED);
    public static final Item BLACK_DYE = Items.DYE.pick(DyeColor.BLACK);

    // stained glass
    public static final Item WHITE_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.WHITE);
    public static final Item ORANGE_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.YELLOW);
    public static final Item LIME_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.LIME);
    public static final Item PINK_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.PINK);
    public static final Item GRAY_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.CYAN);
    public static final Item PURPLE_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.PURPLE);
    public static final Item BLUE_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.BLUE);
    public static final Item BROWN_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.BROWN);
    public static final Item GREEN_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.GREEN);
    public static final Item RED_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.RED);
    public static final Item BLACK_STAINED_GLASS = Items.STAINED_GLASS.pick(DyeColor.BLACK);

    // stained glass pane
    public static final Item WHITE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.WHITE);
    public static final Item ORANGE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.YELLOW);
    public static final Item LIME_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.LIME);
    public static final Item PINK_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.PINK);
    public static final Item GRAY_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.CYAN);
    public static final Item PURPLE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.PURPLE);
    public static final Item BLUE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.BLUE);
    public static final Item BROWN_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.BROWN);
    public static final Item GREEN_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.GREEN);
    public static final Item RED_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.RED);
    public static final Item BLACK_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK);

    // terracotta
    public static final Item WHITE_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.WHITE);
    public static final Item ORANGE_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.YELLOW);
    public static final Item LIME_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.LIME);
    public static final Item PINK_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.PINK);
    public static final Item GRAY_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.CYAN);
    public static final Item PURPLE_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.PURPLE);
    public static final Item BLUE_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.BLUE);
    public static final Item BROWN_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.BROWN);
    public static final Item GREEN_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.GREEN);
    public static final Item RED_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.RED);
    public static final Item BLACK_TERRACOTTA = Items.DYED_TERRACOTTA.pick(DyeColor.BLACK);

    // wool
    public static final Item WHITE_WOOL = Items.WOOL.pick(DyeColor.WHITE);
    public static final Item ORANGE_WOOL = Items.WOOL.pick(DyeColor.ORANGE);
    public static final Item MAGENTA_WOOL = Items.WOOL.pick(DyeColor.MAGENTA);
    public static final Item LIGHT_BLUE_WOOL = Items.WOOL.pick(DyeColor.LIGHT_BLUE);
    public static final Item YELLOW_WOOL = Items.WOOL.pick(DyeColor.YELLOW);
    public static final Item LIME_WOOL = Items.WOOL.pick(DyeColor.LIME);
    public static final Item PINK_WOOL = Items.WOOL.pick(DyeColor.PINK);
    public static final Item GRAY_WOOL = Items.WOOL.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_WOOL = Items.WOOL.pick(DyeColor.LIGHT_GRAY);
    public static final Item CYAN_WOOL = Items.WOOL.pick(DyeColor.CYAN);
    public static final Item PURPLE_WOOL = Items.WOOL.pick(DyeColor.PURPLE);
    public static final Item BLUE_WOOL = Items.WOOL.pick(DyeColor.BLUE);
    public static final Item BROWN_WOOL = Items.WOOL.pick(DyeColor.BROWN);
    public static final Item GREEN_WOOL = Items.WOOL.pick(DyeColor.GREEN);
    public static final Item RED_WOOL = Items.WOOL.pick(DyeColor.RED);
    public static final Item BLACK_WOOL = Items.WOOL.pick(DyeColor.BLACK);
}
