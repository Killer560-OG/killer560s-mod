package com.killer560.hub.autopuzzles;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A human-looking turn towards a target rotation, one tick at a time, for Auto Boulder.
 *
 * <p>killer560 (2026-10-06): the Boulder walk "needs to look legit if the aura isnt on", and "it needs to have the
 * player actually looking at the button and still be fast with realistic rotations". A mouse turn has marks a set
 * rotation does not, and this reproduces them, in the spirit of {@code autoroutes/RouteRotation}'s humanized approach:
 * <ul>
 *   <li>it EASES - a fraction of what is left each tick, fast at first and slowing as the crosshair closes in - with a
 *       fresh fraction (0.45-0.65) rolled for every new target, so no two approaches have the same profile;</li>
 *   <li>one approach in five carries a small overshoot that decays (x0.35 a tick), so the crosshair goes a hair past
 *       and settles back, as a flick does;</li>
 *   <li>every step is capped (28 yaw / 14 pitch degrees a tick) and is a whole number of mouse counts - vanilla turns
 *       by {@code counts * (s*0.6+0.2)^3 * 8 * 0.15} degrees at sensitivity {@code s} (MouseHandler/Entity.turn), so a
 *       real client's rotation deltas sit on that grid - with a count of jitter on longer turns.</li>
 * </ul>
 * The yaw returned is his running yaw plus a wrapped delta - never wrapped or clamped itself (this mod's rule). Pitch
 * stays within -90..90.
 */
final class HumanLook {

    private static final float YAW_CAP = 28f;
    private static final float PITCH_CAP = 14f;

    private Object target = null;
    private float k = 0.55f;
    private float overYaw = 0f;
    private float overPitch = 0f;

    /** Starts a new approach when {@code key} (what is being looked at) changes; the same key keeps the current one. */
    void begin(Object key) {
        if (Objects.equals(key, target)) {
            return;
        }
        target = key;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        k = 0.45f + 0.2f * r.nextFloat();
        if (r.nextFloat() < 0.2f) {
            overYaw = (r.nextFloat() * 2f - 1f) * 1.6f;
            overPitch = (r.nextFloat() * 2f - 1f) * 1.0f;
        } else {
            overYaw = 0f;
            overPitch = 0f;
        }
    }

    /** @return {yaw, pitch} one tick on from (yaw, pitch) towards (targetYaw, targetPitch) */
    float[] step(Minecraft client, float yaw, float pitch, float targetYaw, float targetPitch) {
        float dy = Mth.wrapDegrees(targetYaw + overYaw - yaw);
        float dp = Mth.clamp(targetPitch + overPitch, -90f, 90f) - pitch;
        overYaw *= 0.35f;
        overPitch *= 0.35f;
        double gcd = gcd(client);
        double sy = quantize(ease(dy, k, YAW_CAP, 1.0f), gcd);
        double sp = quantize(ease(dp, k * 0.85f, PITCH_CAP, 0.6f), gcd);
        if (Math.abs(dy) > 4f) {
            sy += gcd * (ThreadLocalRandom.current().nextInt(3) - 1);
        }
        return new float[]{(float) (yaw + sy), Mth.clamp((float) (pitch + sp), -90f, 90f)};
    }

    /** Fraction {@code k} of what is left, at least {@code min} (so it arrives), at most {@code cap}. */
    private static float ease(float d, float k, float cap, float min) {
        float a = Math.abs(d);
        if (a < 1e-3f) {
            return 0f;
        }
        float s = Math.max(Math.min(a, min), a * k);
        return Math.copySign(Math.min(s, cap), d);
    }

    private static double quantize(double step, double gcd) {
        return Math.round(step / gcd) * gcd;
    }

    /** Degrees per mouse count at his sensitivity. */
    static double gcd(Minecraft client) {
        double s = 0.5;
        try {
            s = client.options.sensitivity().get();
        } catch (RuntimeException ignored) {
            // keep the default
        }
        double f = s * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }
}
