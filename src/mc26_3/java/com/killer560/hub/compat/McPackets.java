package com.killer560.hub.compat;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * Packet field reads that differ between Minecraft versions. 26.3 turned most game packets into records (getX() became
 * x()), split the particle packet's one max speed into three, and replaced the move-entity packet's three short deltas
 * with a stepped {@code VecDelta} decoded against the entity's own position codec. Every version's copy of this class
 * has the same public signatures (CLAUDE.md, "Two Minecraft versions, one source tree").
 *
 * <p>26.3 copy.
 */
public final class McPackets {
    private McPackets() {
    }

    public static InteractionHand useItemHand(ServerboundUseItemPacket p) {
        return p.hand();
    }

    public static float useItemYRot(ServerboundUseItemPacket p) {
        return p.yRot();
    }

    public static float useItemXRot(ServerboundUseItemPacket p) {
        return p.xRot();
    }

    public static int useItemSequence(ServerboundUseItemPacket p) {
        return p.sequence();
    }

    public static InteractionHand useItemOnHand(ServerboundUseItemOnPacket p) {
        return p.hand();
    }

    public static BlockHitResult useItemOnHitResult(ServerboundUseItemOnPacket p) {
        return p.hitResult();
    }

    public static int useItemOnSequence(ServerboundUseItemOnPacket p) {
        return p.sequence();
    }

    public static ParticleOptions particle(ClientboundLevelParticlesPacket p) {
        return p.particle();
    }

    public static double particleX(ClientboundLevelParticlesPacket p) {
        return p.x();
    }

    public static double particleY(ClientboundLevelParticlesPacket p) {
        return p.y();
    }

    public static double particleZ(ClientboundLevelParticlesPacket p) {
        return p.z();
    }

    public static float particleXDist(ClientboundLevelParticlesPacket p) {
        return p.xDist();
    }

    public static float particleYDist(ClientboundLevelParticlesPacket p) {
        return p.yDist();
    }

    public static float particleZDist(ClientboundLevelParticlesPacket p) {
        return p.zDist();
    }

    /** 26.2's single max speed. 26.3 carries one per axis; its 11-argument constructor (the one the old wire format
     *  maps onto) writes the same value into all three, so the x one is that value. */
    public static float particleMaxSpeed(ClientboundLevelParticlesPacket p) {
        return p.xMaxSpeed();
    }

    public static int particleCount(ClientboundLevelParticlesPacket p) {
        return p.count();
    }

    public static boolean particleOverrideLimiter(ClientboundLevelParticlesPacket p) {
        return p.overrideLimiter();
    }

    /** True when the packet moves the entity at all (26.2: any of the three deltas non-zero; 26.3: it carries a
     *  position). Cheap and entity-free, for an early return before any lookup. */
    public static boolean moveEntityMoves(ClientboundMoveEntityPacket p) {
        return p.hasPosition();
    }

    /** The move in 26.2's wire units (1/4096 block per axis), or {0,0,0} when the packet carries no position. On 26.3
     *  the delta is decoded exactly as ClientPacketListener.handleMoveEntity does (javap, 2026-11-01): against the
     *  entity's position codec, whose base is still the entity's previous server position while the packet is being
     *  looked at before it is handled. */
    public static long[] moveEntityDelta(ClientboundMoveEntityPacket p, Entity entity) {
        if (!p.hasPosition() || entity == null) {
            return new long[] {0L, 0L, 0L};
        }
        VecDeltaCodec codec = entity.getPositionCodec();
        Vec3 base = codec.getBase();
        Vec3 end = p.getPositionDelta().decode(codec).endPosition();
        return new long[] {codec.encodeX(end) - codec.encodeX(base), codec.encodeY(end) - codec.encodeY(base),
                codec.encodeZ(end) - codec.encodeZ(base)};
    }

    public static IntList removedEntityIds(ClientboundRemoveEntitiesPacket p) {
        return p.entityIds();
    }

    public static int chunkX(ClientboundLevelChunkWithLightPacket p) {
        return p.x();
    }

    public static int chunkZ(ClientboundLevelChunkWithLightPacket p) {
        return p.z();
    }
}
