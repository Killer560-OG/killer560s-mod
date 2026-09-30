package com.killer560.hub.compat;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * Entity types, which 26.2 moved wholesale.
 *
 * <p>26.2 leaves {@code EntityType} with no constants at all - only {@code CODEC} - and puts every
 * one on {@code EntityTypes}. The constants themselves are unchanged, so only the owner differs.
 *
 * <p>The generic parameter is spelled out on each one rather than left as {@code EntityType<?>}.
 * A wildcard compiles here and then breaks every caller: {@code new SimZombie(ZOMBIE, level)} wants
 * an {@code EntityType<? extends Zombie>}, and a capture of {@code ?} will not convert to it.
 *
 * <p>GENERATED SHAPE, hand-maintained: both versions come from one list. Add to one, add to all.
 */
public final class McEntities {

    private McEntities() {
    }

    public static final EntityType<net.minecraft.world.entity.ambient.Bat> BAT = EntityTypes.BAT;
    public static final EntityType<net.minecraft.world.entity.monster.Blaze> BLAZE = EntityTypes.BLAZE;
    public static final EntityType<net.minecraft.world.entity.monster.EnderMan> ENDERMAN = EntityTypes.ENDERMAN;
    public static final EntityType<net.minecraft.world.entity.boss.enderdragon.EndCrystal> END_CRYSTAL = EntityTypes.END_CRYSTAL;
    public static final EntityType<net.minecraft.world.entity.item.FallingBlockEntity> FALLING_BLOCK = EntityTypes.FALLING_BLOCK;
    public static final EntityType<net.minecraft.world.entity.LightningBolt> LIGHTNING_BOLT = EntityTypes.LIGHTNING_BOLT;
    public static final EntityType<net.minecraft.world.entity.monster.skeleton.Skeleton> SKELETON = EntityTypes.SKELETON;
    public static final EntityType<net.minecraft.world.entity.monster.zombie.Zombie> ZOMBIE = EntityTypes.ZOMBIE;
}
