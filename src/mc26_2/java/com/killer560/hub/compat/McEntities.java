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
    public static final EntityType<net.minecraft.world.entity.monster.Silverfish> SILVERFISH = EntityTypes.SILVERFISH;
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
     * <p>The vector convention is vanilla's own and is the easy thing to get backward: {@code dx}/{@code dz}
     * point FROM the target TO the attacker, and knockback pushes the opposite way.
     */
    public static void knockback(net.minecraft.world.entity.LivingEntity target,
                                 double strength, double dx, double dz) {
        target.knockback(strength, dx, dz, target.damageSources().generic(), 0.0f);
    }

    // ---- 26.3 renames (same signatures in every copy) ---------------------------------------------------------------

    /** Makes an entity immune to damage for good: 26.2 {@code setInvulnerable}; 26.3 {@code setPermanentlyInvulnerable}. */
    public static void setInvulnerable(net.minecraft.world.entity.Entity entity, boolean invulnerable) {
        entity.setInvulnerable(invulnerable);
    }

    /** The hurt-cooldown ticks left: a public field on 26.2, a private one behind a getter/setter on 26.3. */
    public static int invulnerableTime(net.minecraft.world.entity.Entity entity) {
        return entity.invulnerableTime;
    }

    public static void setInvulnerableTime(net.minecraft.world.entity.Entity entity, int ticks) {
        entity.invulnerableTime = ticks;
    }

    /** Where an entity's active interpolation is heading (the last position the server sent): 26.2
     *  {@code InterpolationHandler.position()}; 26.3 made the handler an interface whose {@code target()} carries it. */
    public static net.minecraft.world.phys.Vec3 interpolationTarget(net.minecraft.world.entity.InterpolationHandler handler) {
        return handler.position();
    }

    /** A server-side drop of a stack at the player's feet (inventory full), as 26.2's {@code drop(stack, false)}.
     *  26.3 added a Prediction argument; SERVER_ONLY is the server-side case. */
    public static void serverDrop(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        player.drop(stack, false);
    }

}
