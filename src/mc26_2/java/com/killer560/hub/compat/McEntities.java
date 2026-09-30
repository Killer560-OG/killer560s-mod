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

    /**
     * Horizontal knockback, the way a Skyblock ability applies it.
     *
     * <p>26.2 gave {@code LivingEntity.knockback} a {@code DamageSource} and a strength float; 26.1.2's takes
     * the three doubles alone. Nothing in the 26.2 body reads either of the new arguments - disassembled, its
     * six-argument form is byte-for-byte the 26.1.2 three-argument one - so the sim passes a generic source
     * and zero rather than null, which is what a subclass override would be entitled to dereference.
     *
     * <p>The vector convention is vanilla's own and is the easy thing to get backwards: {@code dx}/{@code dz}
     * point FROM the target TO the attacker, and knockback pushes the opposite way.
     */
    public static void knockback(net.minecraft.world.entity.LivingEntity target,
                                 double strength, double dx, double dz) {
        target.knockback(strength, dx, dz, target.damageSources().generic(), 0.0f);
    }

}
