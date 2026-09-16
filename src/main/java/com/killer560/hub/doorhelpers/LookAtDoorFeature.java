package com.killer560.hub.doorhelpers;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Look At Door - smoothly turns the camera onto the nearest locked Wither door (or the Blood door).
 * <p>
 * QUOI has no standalone "look at wither door" module (checked every upstream branch: 26.1.x, 26.2, 26.3,
 * legacy/1.21.10, legacy/1.21.11). The closest upstream code is {@code autoclear/AutoClearUtils.kt}: {@code getLockedDoor()}
 * (nearest locked WITHER/BLOOD door) and {@code pathToDoor(faceOnArrival)} ({@code InteractiveMap}'s "Face door on
 * arrival"), which rotates to {@code Vec3(door.x + 0.5, 71.0, door.z + 0.5)}. This ports that target and selection
 * (straight-line distance instead of QUOI's map-pathfinder distance), but as a humanized per-frame turn instead of
 * QUOI's instant snap:
 * <ul>
 * <li>Keybind: turn to the target. With the Blood Key in the run, the Blood door is preferred; otherwise the nearest
 * locked Wither door, falling back to the Blood door when no Wither doors remain.
 * <li>On Key Pickup: the same turn once, when a Wither/Blood Key is obtained (chat).
 * <li>Every frame (like Simon Says' Rotate mode): eased exponential approach with a speed cap that ramps up, a
 * per-turn random speed, occasional slight overshoot and a small curve. Yaw is always
 * {@code current + wrapDegrees(target - current)} - never wrapped or clamped to 0..360.
 * <li>Stops as soon as the camera moves by anything other than this turn (mouse input, server rotation), when a
 * screen opens, on settle, after 3s, or on leaving the clear phase.
 * </ul>
 */
public final class LookAtDoorFeature {

    private static final long TIMEOUT_MS = 3000L;
    private static final int AUTO_PENDING_TICKS = 40;
    private static final float SETTLE_DEGREES = 0.4f;

    // Key state for the run, from Hypixel's own chat lines.
    private static boolean witherKey = false;
    private static boolean bloodKey = false;
    private static int autoPendingTicks = 0;
    private static Object lastLevel = null;
    private static boolean keyWasDown = false;

    // Current turn.
    private static Vec3 target = null;
    private static String targetDesc = null;
    private static long startedAtMs = 0L;
    private static long lastFrameNanos = 0L;
    private static float elapsedTicks = 0f;
    private static float smoothing = 0.4f;
    private static float maxDegPerTick = 40f;
    private static float overshootYaw = 0f;
    private static float overshootPitch = 0f;
    private static boolean curveOn = false;
    private static float curveSign = 1f;
    private static float lastSetYaw = Float.NaN;
    private static float lastSetPitch = Float.NaN;

    private LookAtDoorFeature() {
    }

    static void onChat(String raw) {
        String msg = ChatFormatting.stripFormatting(raw);
        if (msg == null) {
            return;
        }
        if (msg.contains("has obtained Wither Key") || msg.contains("Wither Key was picked up")
                || msg.startsWith("RIGHT CLICK on a WITHER door")) {
            onKeyObtained("Wither");
        } else if (msg.contains("has obtained Blood Key") || msg.contains("Blood Key was picked up")
                || msg.startsWith("RIGHT CLICK on the BLOOD DOOR")) {
            onKeyObtained("Blood");
        } else if (msg.contains("opened a WITHER door")) {
            witherKey = false;
        } else if (msg.contains("The BLOOD DOOR has been opened")) {
            bloodKey = false;
        }
    }

    private static void onKeyObtained(String kind) {
        boolean wasHeld = "Wither".equals(kind) ? witherKey : bloodKey;
        if ("Wither".equals(kind)) {
            witherKey = true;
        } else {
            bloodKey = true;
        }
        if (!wasHeld && DoorHelpersConfig.getInstance().isLookAtDoorOnKeyPickup()) {
            autoPendingTicks = AUTO_PENDING_TICKS;
            DoorHelpersFeature.LOGGER.info("[DoorHelpers] {} Key obtained - Look At Door queued", kind);
        }
    }

    /** Every tick, regardless of gating: world-change reset. */
    static void tickAlways(Minecraft client, boolean inClear) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            witherKey = false;
            bloodKey = false;
            autoPendingTicks = 0;
            cancel("world change");
        }
        if (!inClear) {
            keyWasDown = false;
        }
    }

    /** Clear phase, feature enabled. */
    static void tick(Minecraft client, DoorHelpersConfig cfg) {
        int key = cfg.getLookAtDoorKey();
        boolean down = key >= 0 && client.screen == null && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), key);
        boolean pressed = down && !keyWasDown;
        keyWasDown = down;

        boolean auto = false;
        if (autoPendingTicks > 0) {
            autoPendingTicks--;
            auto = cfg.isLookAtDoorOnKeyPickup() && client.screen == null;
        }
        if (!pressed && !auto) {
            return;
        }
        if (DoorHelpersFeature.isDead(client)) {
            return;
        }
        DoorScanner.Door door = selectDoor(client);
        if (door == null) {
            if (pressed) {
                DoorHelpersFeature.LOGGER.info("[DoorHelpers] Look At Door: no locked doors scanned");
            }
            return;
        }
        autoPendingTicks = 0;
        begin(client, door, pressed ? "keybind" : "key pickup", cfg.getLookAtDoorSpeed());
    }

    /** Blood door first while the Blood Key is held; else nearest Wither door; else the Blood door. */
    private static DoorScanner.Door selectDoor(Minecraft client) {
        List<DoorScanner.Door> locked = DoorScanner.lockedDoors(client);
        Vec3 eye = client.player.getEyePosition();
        DoorScanner.Door nearestWither = null;
        DoorScanner.Door nearestBlood = null;
        double witherDist = Double.MAX_VALUE;
        double bloodDist = Double.MAX_VALUE;
        for (DoorScanner.Door door : locked) {
            double d = eye.distanceToSqr(aimPoint(door));
            if (door.type() == DoorScanner.DoorType.WITHER && d < witherDist) {
                witherDist = d;
                nearestWither = door;
            } else if (door.type() == DoorScanner.DoorType.BLOOD && d < bloodDist) {
                bloodDist = d;
                nearestBlood = door;
            }
        }
        if (bloodKey && nearestBlood != null) {
            return nearestBlood;
        }
        return nearestWither != null ? nearestWither : nearestBlood;
    }

    /** QUOI {@code pathToDoor(faceOnArrival)}: {@code Vec3(door.pos.x + 0.5, 71.0, door.pos.z + 0.5)}. */
    private static Vec3 aimPoint(DoorScanner.Door door) {
        return new Vec3(door.x() + 0.5, 71.0, door.z() + 0.5);
    }

    private static void begin(Minecraft client, DoorScanner.Door door, String why, int speed) {
        target = aimPoint(door);
        targetDesc = door.type() + " door at " + door.x() + "," + door.z();
        startedAtMs = System.currentTimeMillis();
        lastFrameNanos = 0L;
        elapsedTicks = 0f;
        float jitter = 0.85f + (float) (Math.random() * 0.30);
        smoothing = Mth.clamp((0.12f + 0.05f * speed) * jitter, 0.05f, 0.9f);
        maxDegPerTick = (10f + 6f * speed) * jitter;
        if (Math.random() < 0.25) {
            overshootYaw = (float) ((Math.random() * 2 - 1) * 1.5);
            overshootPitch = (float) ((Math.random() * 2 - 1) * 0.8);
        } else {
            overshootYaw = 0f;
            overshootPitch = 0f;
        }
        curveOn = Math.random() < 0.3;
        curveSign = Math.random() < 0.5 ? 1f : -1f;
        lastSetYaw = Float.NaN;
        lastSetPitch = Float.NaN;
        LocalPlayer player = client.player;
        DoorHelpersFeature.LOGGER.info("[DoorHelpers] Look At Door ({}) -> {} (yaw={} pitch={}, witherKey={} bloodKey={})",
                why, targetDesc, String.format(Locale.US, "%.1f", player.getYRot()),
                String.format(Locale.US, "%.1f", player.getXRot()), witherKey, bloodKey);
    }

    static void cancel(String why) {
        if (target != null) {
            DoorHelpersFeature.LOGGER.info("[DoorHelpers] Look At Door stopped ({}) - {}", why, targetDesc);
        }
        target = null;
        targetDesc = null;
        lastFrameNanos = 0L;
    }

    /** Per render frame. */
    static void onFrame() {
        if (target == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !DoorHelpersConfig.getInstance().isLookAtDoorEnabled()) {
            cancel("disabled/no player");
            return;
        }
        if (client.screen != null) {
            cancel("screen opened");
            return;
        }
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        if (!Float.isNaN(lastSetYaw)
                && (Math.abs(currentYaw - lastSetYaw) > 0.001f || Math.abs(currentPitch - lastSetPitch) > 0.001f)) {
            cancel("camera moved");
            return;
        }
        if (System.currentTimeMillis() - startedAtMs > TIMEOUT_MS) {
            cancel("timeout");
            return;
        }

        long now = System.nanoTime();
        double dtTicks = lastFrameNanos == 0L ? 1.0 : (now - lastFrameNanos) / 50_000_000.0;
        lastFrameNanos = now;
        dtTicks = Mth.clamp(dtTicks, 0.0, 3.0);
        elapsedTicks += (float) dtTicks;

        Vec3 eye = player.getEyePosition();
        Vec3 diff = target.subtract(eye);
        double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float targetYaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(Mth.atan2(diff.y, horizontal) * (180.0 / Math.PI));

        float rawYawDelta = Mth.wrapDegrees(targetYaw - currentYaw);
        float rawPitchDelta = Mth.wrapDegrees(targetPitch - currentPitch);

        if (curveOn) {
            // Rise-then-fade offset on the minor axis so the path isn't a perfectly straight line.
            float t = elapsedTicks;
            float magnitude = t <= 3f ? (t / 3f) * 1.5f : (float) (1.5 * Math.pow(0.4, t - 3f));
            if (Math.abs(rawYawDelta) >= Math.abs(rawPitchDelta)) {
                targetPitch += magnitude * curveSign;
            } else {
                targetYaw += magnitude * curveSign;
            }
        }
        targetYaw += overshootYaw;
        targetPitch += overshootPitch;
        double decay = Math.pow(0.35, dtTicks);
        overshootYaw = Math.abs(overshootYaw * (float) decay) < 0.05f ? 0f : overshootYaw * (float) decay;
        overshootPitch = Math.abs(overshootPitch * (float) decay) < 0.05f ? 0f : overshootPitch * (float) decay;

        float yawDelta = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDelta = Mth.wrapDegrees(targetPitch - currentPitch);
        float frameSmoothing = 1f - (float) Math.pow(1.0 - smoothing, dtTicks);
        float stepYaw = yawDelta * frameSmoothing;
        float stepPitch = pitchDelta * frameSmoothing;
        // Speed cap ramps up over the first ~2 ticks so a large turn accelerates instead of snapping.
        float cap = maxDegPerTick * Math.min(1f, 0.35f + elapsedTicks / 2f) * (float) dtTicks;
        float stepLen = (float) Math.sqrt(stepYaw * stepYaw + stepPitch * stepPitch);
        if (stepLen > cap && stepLen > 0f) {
            float scale = cap / stepLen;
            stepYaw *= scale;
            stepPitch *= scale;
        }
        float newYaw = currentYaw + stepYaw;
        float newPitch = Mth.clamp(currentPitch + stepPitch, -90f, 90f);
        player.setYRot(newYaw);
        player.setXRot(newPitch);
        lastSetYaw = player.getYRot();
        lastSetPitch = player.getXRot();

        if (Math.abs(rawYawDelta) < SETTLE_DEGREES && Math.abs(rawPitchDelta) < SETTLE_DEGREES
                && overshootYaw == 0f && overshootPitch == 0f && elapsedTicks > 3f) {
            cancel("settled");
        }
    }
}
