package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A mirror of GrimAC 2.3.74 Reach's {@code cancelBuffer} (ap3auralook-1116, javap on common-2.3.74.jar): one entity
 * click whose heard look misses a living entity's box (Hitboxes) sets it to 1, and each click that strikes takes 0.25
 * off. While it is above 0 the server re-checks every entity click against the look it has and CANCELS one that misses,
 * so a no-look click on a terminal stand is only taken while this reads clear.
 * <p>
 * Also RotationPlace's {@code flagBuffer} (same jar): a block click whose look misses the block sets it to 1, each one
 * that strikes takes 0.1 off, and while it is above 0 a missing click is CANCELED. So after one no-look lever click the
 * next is thrown away until ten aimed block clicks have gone; {@link #placeClear()} says when one would be taken. The
 * mirror judges against the full block cube with no slack, so it reads a miss where the server may not: it can only
 * wait too long, never send a click the server drops.
 */
public final class GrimHitBuffer {

    private static final double REACH = 3.0;
    private static final double BOX_SLACK = 0.1;
    private static double buffer;
    private static double place;

    private GrimHitBuffer() {
    }

    /** Every entity click as it LEAVES the client (autoroutes/mixin/RightClickSendMixin, client thread): the mod's own
     *  {@code gameMode.interact} fires no Fabric UseEntityCallback on 26.x, so the callbacks missed Terminal Aura's clicks. */
    public static void onPacketSent(Packet<?> packet) {
        int id;
        if (packet instanceof ServerboundUseItemOnPacket p) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) notePlace(mc.player, com.killer560.hub.compat.McPackets.useItemOnHitResult(p).getBlockPos());
            return;
        }
        if (packet instanceof ServerboundInteractPacket p) id = p.entityId();
        else if (packet instanceof ServerboundAttackPacket p) id = p.entityId();
        else return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Entity e = mc.level.getEntity(id);
        if (e != null) note(mc.player, e);
    }

    /** A new connection is a new Grim player: its buffer starts empty. */
    public static void reset() { buffer = 0; place = 0; }

    /** Whether a click that misses would still be taken (the server's buffer is empty). */
    public static boolean clear() { return buffer <= 0; }

    public static double value() { return buffer; }

    /** Whether a block click whose look misses would still be taken (RotationPlace's buffer is empty). */
    public static boolean placeClear() { return place <= 0; }

    public static double placeValue() { return place; }

    private static void notePlace(LocalPlayer player, net.minecraft.core.BlockPos pos) {
        float[] look = RightClick.heardLook();
        if (look == null) look = new float[]{player.getYRot(), player.getXRot()};
        Vec3 eye = RightClick.heardFeet(player).add(0, player.getEyeHeight(), 0);
        // GrimAC's ray runs only to BLOCK_INTERACTION_RANGE: a cube entered past it is a miss there (a 6-block ray here read
        // a hit the server took as a miss, leverplace0-1117).
        Vec3 end = eye.add(Vec3.directionFromRotation(look[1], look[0]).scale(player.blockInteractionRange()));
        AABB box = new AABB(pos);
        boolean hit = box.contains(eye) || box.clip(eye, end).isPresent();
        place = hit ? Math.max(0, place - 0.1) : 1;
    }

    private static void note(LocalPlayer player, Entity e) {
        if (!(e instanceof LivingEntity) && !(e instanceof EndCrystal)) return;
        float[] look = RightClick.heardLook();
        if (look == null) look = new float[]{player.getYRot(), player.getXRot()};
        Vec3 eye = RightClick.heardFeet(player).add(0, player.getEyeHeight(), 0);
        Vec3 end = eye.add(Vec3.directionFromRotation(look[1], look[0]).scale(REACH));
        AABB box = e.getBoundingBox().inflate(BOX_SLACK);
        boolean hit = box.contains(eye) || box.clip(eye, end).isPresent();
        buffer = hit ? Math.max(0, buffer - 0.25) : 1;
    }
}
