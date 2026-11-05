package com.killer560.hub.compat;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * Packet field reads that differ between Minecraft versions; see the 26.3 copy for the why. Same public signatures in
 * every version's copy.
 *
 * <p>26.2 copy: plain getters.
 */
public final class McPackets {
    private McPackets() {
    }

    public static InteractionHand useItemHand(ServerboundUseItemPacket p) {
        return p.getHand();
    }

    public static float useItemYRot(ServerboundUseItemPacket p) {
        return p.getYRot();
    }

    public static float useItemXRot(ServerboundUseItemPacket p) {
        return p.getXRot();
    }

    public static int useItemSequence(ServerboundUseItemPacket p) {
        return p.getSequence();
    }

    public static InteractionHand useItemOnHand(ServerboundUseItemOnPacket p) {
        return p.getHand();
    }

    public static BlockHitResult useItemOnHitResult(ServerboundUseItemOnPacket p) {
        return p.getHitResult();
    }

    public static int useItemOnSequence(ServerboundUseItemOnPacket p) {
        return p.getSequence();
    }

    public static ParticleOptions particle(ClientboundLevelParticlesPacket p) {
        return p.getParticle();
    }

    public static double particleX(ClientboundLevelParticlesPacket p) {
        return p.getX();
    }

    public static double particleY(ClientboundLevelParticlesPacket p) {
        return p.getY();
    }

    public static double particleZ(ClientboundLevelParticlesPacket p) {
        return p.getZ();
    }

    public static float particleXDist(ClientboundLevelParticlesPacket p) {
        return p.getXDist();
    }

    public static float particleYDist(ClientboundLevelParticlesPacket p) {
        return p.getYDist();
    }

    public static float particleZDist(ClientboundLevelParticlesPacket p) {
        return p.getZDist();
    }

    public static float particleMaxSpeed(ClientboundLevelParticlesPacket p) {
        return p.getMaxSpeed();
    }

    public static int particleCount(ClientboundLevelParticlesPacket p) {
        return p.getCount();
    }

    public static boolean particleOverrideLimiter(ClientboundLevelParticlesPacket p) {
        return p.isOverrideLimiter();
    }

    public static boolean moveEntityMoves(ClientboundMoveEntityPacket p) {
        return p.getXa() != 0 || p.getYa() != 0 || p.getZa() != 0;
    }

    public static long[] moveEntityDelta(ClientboundMoveEntityPacket p, Entity entity) {
        return new long[] {p.getXa(), p.getYa(), p.getZa()};
    }

    public static IntList removedEntityIds(ClientboundRemoveEntitiesPacket p) {
        return p.getEntityIds();
    }

    public static int chunkX(ClientboundLevelChunkWithLightPacket p) {
        return p.getX();
    }

    public static int chunkZ(ClientboundLevelChunkWithLightPacket p) {
        return p.getZ();
    }

    /** The absolute position an entity position-sync packet puts its entity at, or null when it carries none (26.3:
     *  {@code position().endPosition()} behind {@code hasPosition()}; 26.2: {@code values().position()}). */
    public static Vec3 positionSyncPos(ClientboundEntityPositionSyncPacket p) {
        return p.values() == null ? null : p.values().position();
    }

    public static int positionSyncId(ClientboundEntityPositionSyncPacket p) {
        return p.id();
    }

    /** The look a teleport accept carries (26.3 sends it in the accept; NaN on 26.2, whose accept has none). */
    public static float acceptYRot(ServerboundAcceptTeleportationPacket p) {
        return Float.NaN;
    }

    public static float acceptXRot(ServerboundAcceptTeleportationPacket p) {
        return Float.NaN;
    }

    /** The same explosion with no knockback for the player it is sent to (the sim's no-knockback rule, SimLaunch). */
    public static net.minecraft.network.protocol.game.ClientboundExplodePacket withoutKnockback(
            net.minecraft.network.protocol.game.ClientboundExplodePacket p) {
        return new net.minecraft.network.protocol.game.ClientboundExplodePacket(p.center(), p.radius(), p.blockCount(), java.util.Optional.empty(), p.explosionParticle(), p.explosionSound(), p.blockParticles());
    }
}
