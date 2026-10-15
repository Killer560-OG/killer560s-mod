package com.killer560.hub.bloodcamp;

import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Blood Camp Aura's turn: a human mouse movement toward a point, applied every render frame and every tick.
 * <p>
 * killer560, 2026-10-15: "The aura feels very snappy I want you to make it feel very human." It used to set 15% of the
 * remaining angle once a tick, from the first tick a mob was due within 30 ticks: the fastest part of the turn on its
 * very first tick with no reaction time, and a 20 Hz staircase on screen. A hand moves differently, and this copies
 * what is measured of one:
 * <ul>
 *   <li><b>Reaction</b> - nothing moves for 3-6 ticks after a new mob is picked (1-3 when going straight on from
 *   another one).</li>
 *   <li><b>A bell-shaped speed profile</b> - the primary movement follows the minimum-jerk curve
 *   {@code 10t^3 - 15t^4 + 6t^5}: it starts slowly, is fastest in the middle and slows into the target. Its duration
 *   follows Fitts' law ({@code 2.5 + 1.6 log2(1 + D / 2.5)} ticks, +-15%), so a small correction is quick and a big turn
 *   takes longer, and it is stretched whenever the peak speed would pass {@link #YAW_CAP}/{@link #PITCH_CAP} a tick.</li>
 *   <li><b>It rarely lands exactly</b> - 60% of turns stop a few percent short, 30% go a few percent past, and a short
 *   second movement (2-4 ticks, the same curve) corrects it.</li>
 *   <li><b>A slight arc</b> - up to 5% of the turn sideways, peaking mid-movement, never a detour.</li>
 *   <li><b>A resting hand drifts</b> - once on target, a slow wander of a tenth of a degree or so.</li>
 *   <li><b>Mouse counts</b> - every write is a whole number of mouse counts at his sensitivity (vanilla turns by
 *   {@code counts * (s*0.6+0.2)^3 * 8 * 0.15} degrees), so the rotations sit on the grid a real mouse leaves.</li>
 * </ul>
 * The target is a POINT (re-aimed from the eye every time), and the planned motion is an ERROR relative to it, so a
 * target that shifts mid-turn (the landing spot refining, the real mob appearing) is followed without a restart.
 * <p>
 * <b>His mouse wins.</b> Any movement of his - the body moved by something other than this class, or, in Freecam
 * Mode, the held view moved - stops the aura at once and for {@link #MOUSE_YIELD_TICKS} after his last movement; in
 * Freecam Mode the camera is handed straight back (body under the view). Then it starts over from where he left it,
 * with a fresh reaction time.
 * <p>
 * <b>Freecam Mode</b> holds the camera with {@link ViewFreeze} (the same free camera AP3 and the auto puzzles use) while
 * the body turns; when the aura has nothing left to aim at, the body is turned back under the camera with the same
 * human movement and the camera released, so nothing on screen jumps.
 * <p>
 * Safety rules: yaw is only ever {@code current + wrapDegrees(...)} - the running value is never wrapped or clamped;
 * pitch stays within -90..90; nothing here touches position or sends a packet.
 */
final class BloodCampAim {

    /** Most a turn moves in one tick, degrees - a fast but ordinary flick (Auto Puzzles' HumanLook uses 28 / 14). */
    static final float YAW_CAP = 26f;
    static final float PITCH_CAP = 16f;
    /** Ticks his mouse must have been still before the aura takes over again. */
    static final int MOUSE_YIELD_TICKS = 10;
    /** Ticks with no target before Freecam Mode turns the body back and gives the camera back. */
    private static final int FREECAM_IDLE_TICKS = 6;
    /** A target that moves this far (degrees) while the aura rests on it gets a fresh (short) approach. */
    private static final float RETARGET_DEGREES = 6f;
    /** Below this movement (degrees) a body change is our own rounding, not his mouse. */
    private static final float MOUSE_EPSILON = 0.001f;

    // ---- what is aimed at ----
    private Object key;
    private Vec3 point;
    private boolean freecam;
    /** Approach plan, in ticks of {@link #now}. */
    private boolean planned;
    private double reactionEnd;
    private double t0;
    private double t1;
    private double t2;
    private float e0Yaw;
    private float e0Pitch;
    private float endYaw;
    private float endPitch;
    private float arc;
    private double tremorPhaseA;
    private double tremorPhaseB;
    private double tremorPeriodA;
    private double tremorPeriodB;
    private float tremorAmp;

    // ---- time ----
    private long ticks;
    private double lastT;

    // ---- his mouse ----
    private float lastAppliedYaw = Float.NaN;
    private float lastAppliedPitch = Float.NaN;
    private float lastViewYaw = Float.NaN;
    private float lastViewPitch = Float.NaN;
    private long yieldUntil = Long.MIN_VALUE;
    private boolean yielded;

    // ---- free camera ----
    private boolean ownsHold;
    private int idleTicks;
    private boolean handingBack;

    // ---- testkit ----
    int takeovers;
    long approaches;
    /** Ticks from picking a target to the first write of its approach, last approach. */
    int lastReactionTicks = -1;
    /** Biggest yaw / pitch change between two consecutive tick starts while this was turning. */
    float maxTickYaw;
    float maxTickPitch;
    private float prevTickYaw = Float.NaN;
    private float prevTickPitch = Float.NaN;
    private long pickedAtTick;
    private boolean wroteThisApproach;

    /** Every client tick (START), whatever the aura is doing. {@code point} null = nothing to aim at. */
    void tick(Minecraft client, Object newKey, Vec3 newPoint, boolean freecamMode) {
        LocalPlayer player = client.player;
        ticks++;
        if (player == null) {
            reset(null);
            return;
        }
        float bodyYaw = player.getYRot();
        float bodyPitch = player.getXRot();
        // Per-tick motion (testkit): what the movement packets will show, frames included.
        if (!Float.isNaN(prevTickYaw) && (isTurning() || handingBack)) {
            maxTickYaw = Math.max(maxTickYaw, Math.abs(Mth.wrapDegrees(bodyYaw - prevTickYaw)));
            maxTickPitch = Math.max(maxTickPitch, Math.abs(bodyPitch - prevTickPitch));
        }
        prevTickYaw = bodyYaw;
        prevTickPitch = bodyPitch;

        if (detectMouse(player)) {
            onMouse(player);
        }
        if (ownsHold && !freecamMode) {
            giveBackCamera(player); // Freecam Mode switched off mid-run
        }
        freecam = freecamMode;
        if (ownsHold && ViewFreeze.isHeld()) {
            lastViewYaw = ViewFreeze.viewYaw();
            lastViewPitch = ViewFreeze.viewPitch();
        }

        if (ticks < yieldUntil) {
            // His mouse has it. Keep nothing held and remember nothing to fight over.
            point = null;
            key = null;
            planned = false;
            return;
        }
        if (yielded) {
            yielded = false;
        }

        if (newPoint == null) {
            point = null;
            key = null;
            if (!handingBack) {
                planned = false; // (the hand-back's own plan must survive from tick to tick)
            }
            if (ownsHold) {
                if (++idleTicks >= FREECAM_IDLE_TICKS) {
                    handBack(player);
                } else {
                    ViewFreeze.hold(bodyYaw, bodyPitch);
                }
            }
            return;
        }
        idleTicks = 0;
        handingBack = false;
        // Another owner (an auto puzzle, AP3) holds the camera and is turning him: never turn the body under it.
        if (ViewFreeze.isHeld() && !ownsHold) {
            point = null;
            key = null;
            planned = false;
            return;
        }
        if (freecam && !ownsHold) {
            ViewFreeze.hold(bodyYaw, bodyPitch);
            ownsHold = true;
            lastViewYaw = ViewFreeze.viewYaw();
            lastViewPitch = ViewFreeze.viewPitch();
        } else if (ownsHold) {
            ViewFreeze.hold(bodyYaw, bodyPitch); // renew the lease
        }

        boolean newTarget = key == null || !key.equals(newKey);
        boolean straightOn = key != null; // going from one mob to the next, no idle in between
        key = newKey;
        point = newPoint;
        if (newTarget) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            planned = false;
            reactionEnd = ticks + (straightOn ? 1 + r.nextInt(3) : 3 + r.nextInt(4));
            pickedAtTick = ticks;
            wroteThisApproach = false;
        } else if (planned && now(client) >= t2) {
            float[] tgt = angles(player.getEyePosition(), point);
            float off = Math.max(Math.abs(Mth.wrapDegrees(bodyYaw - tgt[0])), Math.abs(bodyPitch - tgt[1]));
            if (off > RETARGET_DEGREES) {
                planned = false;
                reactionEnd = ticks + 1 + ThreadLocalRandom.current().nextInt(2);
                pickedAtTick = ticks;
                wroteThisApproach = false;
            }
        }
        apply(client, now(client));
    }

    /** Every render frame: the same motion at the frame's time, so it is smooth on screen rather than 20 steps a second. */
    void frame(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || (point == null && !handingBack)) {
            return;
        }
        if (detectMouse(player)) {
            onMouse(player);
            return;
        }
        apply(client, now(client));
    }

    boolean isTurning() {
        return point != null;
    }

    boolean ownsCamera() {
        return ownsHold;
    }

    /** World change, feature off, or he left the Blood Room: let go of everything (camera given back under the view). */
    void reset(LocalPlayer player) {
        if (ownsHold) {
            if (player != null) {
                giveBackCamera(player);
            } else {
                ownsHold = false;
                ViewFreeze.release();
            }
        }
        point = null;
        key = null;
        planned = false;
        handingBack = false;
        idleTicks = 0;
        lastAppliedYaw = Float.NaN;
        lastAppliedPitch = Float.NaN;
    }

    // ------------------------------------------------------------------------------------------------------------

    private double now(Minecraft client) {
        double partial = 0.0;
        try {
            partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        } catch (RuntimeException ignored) {
            // no partial tick available: whole ticks
        }
        double t = ticks + Mth.clamp(partial, 0.0, 0.999);
        if (t < lastT) {
            t = lastT; // never run time backwards (partial resets at the tick)
        }
        lastT = t;
        return t;
    }

    /** True when something other than this class moved the body (his mouse), or his mouse moved the held view. */
    private boolean detectMouse(LocalPlayer player) {
        if (ownsHold && ViewFreeze.isHeld() && !Float.isNaN(lastViewYaw)) {
            float vy = ViewFreeze.viewYaw();
            float vp = ViewFreeze.viewPitch();
            if (Math.abs(vy - lastViewYaw) > MOUSE_EPSILON || Math.abs(vp - lastViewPitch) > MOUSE_EPSILON) {
                return true;
            }
        }
        if (!Float.isNaN(lastAppliedYaw) && (point != null || handingBack)) {
            return Math.abs(Mth.wrapDegrees(player.getYRot() - lastAppliedYaw)) > MOUSE_EPSILON
                    || Math.abs(player.getXRot() - lastAppliedPitch) > MOUSE_EPSILON;
        }
        return false;
    }

    private void onMouse(LocalPlayer player) {
        if (!yielded) {
            takeovers++;
            BloodCampFeature.LOGGER.info("[BloodCamp] Aura: your mouse moved - aura stands down");
        }
        yielded = true;
        yieldUntil = ticks + MOUSE_YIELD_TICKS;
        if (ownsHold) {
            giveBackCamera(player);
        }
        point = null;
        key = null;
        planned = false;
        handingBack = false;
        lastAppliedYaw = Float.NaN;
        lastAppliedPitch = Float.NaN;
    }

    /** Body straight under the held view, camera released - nothing moves on screen (FreeCam.release's hand-back). */
    private void giveBackCamera(LocalPlayer player) {
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        if (!Float.isNaN(viewYaw)) {
            float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.clamp(viewPitch, -90f, 90f));
            float shift = yaw - viewYaw;
            player.yBob += shift;
            player.yBobO += shift;
        }
        ownsHold = false;
        handingBack = false;
        ViewFreeze.release();
        lastViewYaw = Float.NaN;
        lastViewPitch = Float.NaN;
    }

    /** Nothing left to aim at in Freecam Mode: turn the body back toward the camera like a hand would, then release. */
    private void handBack(LocalPlayer player) {
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        if (Float.isNaN(viewYaw)) {
            ownsHold = false;
            handingBack = false;
            return;
        }
        ViewFreeze.hold(player.getYRot(), player.getXRot());
        float dy = Mth.wrapDegrees(viewYaw - player.getYRot());
        float dp = viewPitch - player.getXRot();
        if (Math.abs(dy) < 0.4f && Math.abs(dp) < 0.4f) {
            giveBackCamera(player);
            return;
        }
        if (!handingBack) {
            handingBack = true;
            planned = false;
        }
        // Aim at a point straight down the view direction; the same human movement turns the body there.
        Vec3 eye = player.getEyePosition();
        point = null;
        Vec3 viewPoint = eye.add(Vec3.directionFromRotation(viewPitch, viewYaw).scale(10.0));
        Minecraft client = Minecraft.getInstance();
        double t = now(client);
        if (!planned) {
            plan(player, viewPoint, t, true);
        }
        write(player, client, viewPoint, t);
    }

    private void apply(Minecraft client, double t) {
        LocalPlayer player = client.player;
        if (handingBack) {
            float viewYaw = ViewFreeze.viewYaw();
            if (Float.isNaN(viewYaw)) {
                return;
            }
            Vec3 viewPoint = player.getEyePosition().add(
                    Vec3.directionFromRotation(ViewFreeze.viewPitch(), viewYaw).scale(10.0));
            write(player, client, viewPoint, t);
            return;
        }
        if (point == null) {
            return;
        }
        if (!planned) {
            if (t < reactionEnd) {
                lastAppliedYaw = player.getYRot(); // holding still while reacting
                lastAppliedPitch = player.getXRot();
                return;
            }
            plan(player, point, t, false);
        }
        write(player, client, point, t);
    }

    /** Rolls one approach from where the body is now to {@code target}. */
    private void plan(LocalPlayer player, Vec3 target, double t, boolean quick) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float[] tgt = angles(player.getEyePosition(), target);
        e0Yaw = Mth.wrapDegrees(player.getYRot() - tgt[0]);
        e0Pitch = player.getXRot() - tgt[1];
        float d = (float) Math.hypot(e0Yaw, e0Pitch);
        double fitts = (2.5 + 1.6 * (Math.log(1.0 + d / 2.5) / Math.log(2.0))) * (0.85 + 0.3 * r.nextDouble());
        // Stretch so the bell's peak (1.875 x average speed) stays under the per-tick caps, with a margin.
        double minYaw = 1.875 * Math.abs(e0Yaw) / (YAW_CAP * 0.85);
        double minPitch = 1.875 * Math.abs(e0Pitch) / (PITCH_CAP * 0.85);
        double dur = Math.max(quick ? 3.0 : 4.0, Math.max(fitts, Math.max(minYaw, minPitch)));
        float roll = r.nextFloat();
        float endFrac;
        if (d < 1.5f || quick) {
            endFrac = 0f;
        } else if (roll < 0.6f) {
            endFrac = 0.02f + 0.04f * r.nextFloat();   // stops a little short
        } else if (roll < 0.9f) {
            endFrac = -(0.03f + 0.05f * r.nextFloat()); // goes a little past
        } else {
            endFrac = 0f;
        }
        endYaw = e0Yaw * endFrac;
        endPitch = e0Pitch * endFrac;
        float endMag = (float) Math.hypot(endYaw, endPitch);
        if (endMag > 4f) {
            endYaw *= 4f / endMag;
            endPitch *= 4f / endMag;
        }
        arc = d < 2f ? 0f : (float) ((r.nextBoolean() ? 1 : -1) * Math.min(3.0, d * (0.015 + 0.035 * r.nextDouble())));
        t0 = t;
        t1 = t + dur;
        t2 = endFrac == 0f ? t1 : t1 + 2 + r.nextInt(3);
        tremorPhaseA = r.nextDouble() * Math.PI * 2;
        tremorPhaseB = r.nextDouble() * Math.PI * 2;
        tremorPeriodA = 13 + r.nextDouble() * 10;
        tremorPeriodB = 21 + r.nextDouble() * 12;
        tremorAmp = 0.06f + 0.1f * r.nextFloat();
        planned = true;
        approaches++;
        if (!quick) {
            lastReactionTicks = (int) (ticks - pickedAtTick);
        }
    }

    /** The planned error from the target at time {@code t}: {yaw, pitch}. */
    private float[] error(double t) {
        if (t <= t0) {
            return new float[]{e0Yaw, e0Pitch};
        }
        if (t < t1) {
            double tau = (t - t0) / (t1 - t0);
            float s = (float) minJerk(tau);
            float ey = e0Yaw + (endYaw - e0Yaw) * s;
            float ep = e0Pitch + (endPitch - e0Pitch) * s;
            float d = (float) Math.hypot(e0Yaw, e0Pitch);
            if (d > 1e-3f && arc != 0f) {
                float side = (float) (arc * Math.sin(Math.PI * tau));
                ey += -e0Pitch / d * side;
                ep += e0Yaw / d * side;
            }
            return new float[]{ey, ep};
        }
        if (t < t2) {
            float s = (float) minJerk((t - t1) / (t2 - t1));
            return new float[]{endYaw * (1 - s), endPitch * (1 - s)};
        }
        // Resting on target: a slow drift, never more than a few tenths.
        double since = t - t2;
        float ramp = (float) Math.min(1.0, since / 6.0);
        float wy = (float) (tremorAmp * ramp * (Math.sin(tremorPhaseA + since * 2 * Math.PI / tremorPeriodA)
                + 0.5 * Math.sin(tremorPhaseB + since * 2 * Math.PI / tremorPeriodB)));
        float wp = (float) (tremorAmp * 0.6f * ramp * Math.sin(tremorPhaseB + since * 2 * Math.PI / tremorPeriodA));
        return new float[]{wy, wp};
    }

    private void write(LocalPlayer player, Minecraft client, Vec3 target, double t) {
        float[] tgt = angles(player.getEyePosition(), target);
        float[] e = error(t);
        float wantYaw = tgt[0] + e[0];
        float wantPitch = Mth.clamp(tgt[1] + e[1], -90f, 90f);
        float curYaw = player.getYRot();
        float curPitch = player.getXRot();
        double gcd = gcd(client);
        double dy = Mth.wrapDegrees(wantYaw - curYaw);
        double dp = wantPitch - curPitch;
        // A frame can never carry more than a tick's cap (a lag spike must not turn into one big jump).
        dy = Mth.clamp(dy, -YAW_CAP, YAW_CAP);
        dp = Mth.clamp(dp, -PITCH_CAP, PITCH_CAP);
        dy = Math.round(dy / gcd) * gcd;
        dp = Math.round(dp / gcd) * gcd;
        if (dy != 0.0 || dp != 0.0) {
            float yaw = (float) (curYaw + dy); // running yaw + a delta: never wrapped
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.clamp((float) (curPitch + dp), -90f, 90f));
            wroteThisApproach = true;
        }
        lastAppliedYaw = player.getYRot();
        lastAppliedPitch = player.getXRot();
    }

    static double minJerk(double tau) {
        double x = Mth.clamp(tau, 0.0, 1.0);
        return x * x * x * (10 - 15 * x + 6 * x * x);
    }

    /** {yaw, pitch} from {@code eye} to {@code p} (vanilla's convention; the yaw is a direction, compared wrapped). */
    static float[] angles(Vec3 eye, Vec3 p) {
        Vec3 d = p.subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        return new float[]{yaw, Mth.clamp(pitch, -90f, 90f)};
    }

    /** Degrees per mouse count at his sensitivity (same as Auto Puzzles' HumanLook). */
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
