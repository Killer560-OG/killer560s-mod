package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a dig sent now is aimed as GrimAC judges it (gridexp-1102).
 *
 * <p>GrimAC 2.3.74's experimental RotationBreak traces a START_DESTROY_BLOCK along the look it has for him: the NEXT
 * movement packet's look only when that packet arrives within 15 ms of the dig ({@code handleQueuedBreaks}), otherwise
 * the look of the movement packet BEFORE it; and once it has flagged one dig (its buffer is 1, decaying 0.1 per good
 * dig) every later dig is also judged on arrival by the look before ("pre-flying"). A dig sent in the same tick as the
 * body turn that aims it passes only while the server is fast: 62-argrim's breaker, breakerfall and boomsneak drew
 * RotationBreak pre-flying on every dig after the first slow one. So a dig goes out only once the look the server
 * already HEARD strikes the block's box from where the server has him - the turn goes a tick ahead, as a hand's does -
 * and then any look the next movement packet carries still passes, because Grim also tries the previous look.
 *
 * <p>Grim's ray: from the position it has for him, at his eye height, along the heard look, the block's full cube,
 * {@code block_interaction_range} (4.5) long, and no occlusion; standing inside the block (eye box grown by 0.03) always
 * passes.
 */
public final class DigAim {

    /** The reach GrimAC's ray uses (the survival block interaction range). */
    public static final double REACH = 4.5;

    private DigAim() {
    }

    /** True when a dig on {@code pos} sent now passes GrimAC RotationBreak on the look the server last heard. */
    public static boolean heardLookStrikes(LocalPlayer player, BlockPos pos) {
        float[] look = RightClick.heardLook();
        if (look == null || player == null) return false;
        return strikes(RightClick.heardFeet(player), player.getEyeHeight(), look[0], look[1], pos);
    }

    /** GrimAC RotationBreak's ray from {@code feet} at {@code eyeHeight} along {@code yaw}/{@code pitch}. */
    public static boolean strikes(Vec3 feet, double eyeHeight, float yaw, float pitch, BlockPos pos) {
        AABB box = new AABB(pos);
        Vec3 eye = feet.add(0, eyeHeight, 0);
        if (new AABB(eye, eye).inflate(0.03).intersects(box)) {
            return true;
        }
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(REACH));
        return box.clip(eye, end).isPresent();
    }
}
