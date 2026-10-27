package com.killer560.hub.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Turn-then-act for an aura whose target the reported look does not reach: Terminal Aura's Turn To Terminal
 * (2026-10-07), made shareable.
 * <p>
 * Why: an interaction aimed at something the rotation in the movement packets does not point at is not something a
 * vanilla client sends. Measured against GrimAC (testkit, 2026-10-07): Secret Aura clicking a lever directly behind the
 * player drew {@code RotationPlace post-flying} (419); a terminal stand behind drew {@code Hitboxes type=armor_stand}
 * (415, 418). With the body turned the same clicks are clean.
 * <p>
 * How: {@link #readyBlock}/{@link #readyBox} answer whether the look the server was last told about (the body's, never
 * the camera's) already reaches the target. When it does, act now. When it does not, the BODY is turned to the target -
 * camera held by {@link ViewFreeze}, yaw a wrapped delta on the running value, pitch within +-90 - and the owner acts on
 * a later tick, once that tick's movement packet has reported the turn; the body is given back the tick after it acted.
 * <p>
 * Given back to WHAT depends on who held the camera. Nobody: this took the hold, so the body joins the held view (where
 * his mouse steered it) and the hold is released - {@link BodyAim}'s hand-back. Somebody else (an auto puzzle shooting,
 * Auto Boulder walking): their hold is left alone and the body goes back to the rotation it had before this turn, which
 * is theirs. Releasing a hold this did not take let the camera fall onto the body mid-puzzle (testkit 93-solve-
 * blazemiss-lower and -boulder-aura, 2026-10-07, on the first cut, which used BodyAim). One instance per owner. Nothing
 * here sends a packet or touches position or velocity.
 */
public final class TurnFirst {

    /** Ticks a turn waits for its owner to act before it is given up (and the body given back). */
    private static final int MAX_WAIT = 5;
    /** Ticks a turn waits while somebody else holds the camera (and is therefore turning him) before turning anyway. */
    private static final int FOREIGN_WAIT = 10;

    /** The one owner with a turn out, mod-wide: a second aura waits for it rather than turning the body under it. */
    private static TurnFirst current;

    /**
     * Something that is steering the body itself right now and must not have it turned under it: AP3 while an align
     * plans its key presses against the body's yaw, and while a Boom / Use / Block is aimed (killer560, 2026-10-26: Lights
     * Device's pre-flick turned him mid-align and walked him off the ledge; "if boom is actively looking dont let other
     * nodes override it"). An aura asked to turn while any of these answers true waits (asks again next tick) instead.
     */
    private static final java.util.List<java.util.function.BooleanSupplier> BODY_OWNERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerBodyOwner(java.util.function.BooleanSupplier owner) {
        BODY_OWNERS.add(owner);
    }

    /**
     * The yaws the body may be turned to right now without changing where he is being walked, or null for any yaw
     * (killer560, 2026-10-27: "when it looks at stuff like levers and things it can mess with where my ap3 is going").
     * An AP3 held walk (and Auto Routes walking) moves him along a fixed world direction with one of the eight key
     * combinations, and each combination points exactly along that direction at one yaw - so a turn to one of those
     * yaws, with the keys re-picked for it, leaves the path exactly as it was. Pitch never affects movement and is free.
     * The provider returns them in preference order (the ones that keep sprint - W, W+A, W+D - first).
     */
    private static final java.util.List<java.util.function.Supplier<float[]>> YAW_CONSTRAINTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerYawConstraint(java.util.function.Supplier<float[]> c) {
        YAW_CONSTRAINTS.add(c);
    }

    private static float[] yawConstraint() {
        for (java.util.function.Supplier<float[]> c : YAW_CONSTRAINTS) {
            float[] y = c.get();
            if (y != null) {
                return y;
            }
        }
        return null;
    }

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-turnfirst");

    /** How the last click this owner was told to make got its look: for the log line at the click. */
    private String method = "already on the look";

    /** Whether some feature owns the body's rotation right now (see {@link #registerBodyOwner}). */
    public static boolean bodyOwned() {
        for (java.util.function.BooleanSupplier o : BODY_OWNERS) {
            if (o.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private final Runnable onWrite;
    /** True while the camera hold is this owner's own (an auto puzzle's free camera): no foreign wait then. */
    private final java.util.function.BooleanSupplier ownHold;
    /** True from a turn until the body has been given back. */
    private boolean turned;
    /** This turn took the camera hold itself (nobody held it), so the hand-back joins the view and releases it. */
    private boolean ownsHold;
    /** The body's rotation before the turn, for a hand-back under somebody else's hold. */
    private float prevYaw;
    private float prevPitch;
    private boolean pending;
    private int pendingAge;
    private int ticksSinceTurn;
    /** {@link ViewFreeze#holdCount()} right after this owner's last hold: more since means somebody else holds too. */
    private long myLastHold;
    private int foreignWait;

    public TurnFirst() {
        this(() -> { });
    }

    /** {@code onWrite}: run after every write to his rotation (an owner with a camera-turn detector rebases it). */
    public TurnFirst(Runnable onWrite) {
        this(onWrite, () -> false);
    }

    /**
     * {@code ownHold}: true while a held camera belongs to this owner's own run - an auto puzzle that holds its free camera
     * for the whole room and asks for its clicks through this. Then the hold is not "somebody else turning him" and the
     * turn goes at once: the {@link #FOREIGN_WAIT} that waited on it held every Auto Water lever 11 ticks late (killer560,
     * 2026-10-15) and any other puzzle click made under the auto's own camera (a reward chest) the same.
     */
    public TurnFirst(Runnable onWrite, java.util.function.BooleanSupplier ownHold) {
        this.onWrite = onWrite;
        this.ownHold = ownHold;
    }

    /**
     * Every client tick of the owner, FIRST, whether it is enabled or not: keeps the body held while a turned-for action
     * is still to go out, and gives it back the tick after.
     */
    public void tick(LocalPlayer player) {
        if (pending && ++pendingAge > MAX_WAIT) {
            pending = false;
        }
        if (!turned) {
            return;
        }
        if (player == null) {
            turned = false;
            release();
            if (ownsHold) {
                ViewFreeze.release();
            }
            return;
        }
        ticksSinceTurn++;
        if (pending || ticksSinceTurn < 1) {
            ViewFreeze.hold(player.getYRot(), player.getXRot()); // renew the lease; keeps the first view
            myLastHold = ViewFreeze.holdCount();
            return;
        }
        turned = false;
        release();
        if (ViewFreeze.holdCount() != myLastHold) {
            // Somebody else (an auto puzzle, Auto Boulder's walk) has held the camera since: they are driving the body
            // now and their own hand-back puts it under the view. Releasing their hold, or writing the body under them,
            // is what made the camera jump in 93-solve-boulder-aura on the first cut. Leave both alone.
            return;
        }
        float toYaw = prevYaw;
        float toPitch = prevPitch;
        if (ownsHold) {
            float vy = ViewFreeze.viewYaw();
            if (!Float.isNaN(vy)) {
                toYaw = vy;
                toPitch = ViewFreeze.viewPitch();
            }
        }
        float yaw = player.getYRot() + Mth.wrapDegrees(toYaw - player.getYRot());
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(toPitch, -90f, 90f));
        if (ownsHold) {
            ViewFreeze.release();
        }
        onWrite.run();
    }

    private void release() {
        if (current == this) {
            current = null;
        }
    }

    /** Whether a turn is still out (the body is turned away from where it was). */
    /** Whether any aura's turn is out (turned, not yet given back) - Kill Mimic waits for the hand-back. */
    public static boolean anyTurnOut() {
        return current != null;
    }

    public boolean isTurned() {
        return turned;
    }

    /**
     * A block: true when its outline is on the reported look within {@code reach} - act now. Otherwise turns the body to
     * {@code aimPoint} (a point on the block, e.g. {@link BlockHits#surface}'s) and returns false: ask again next tick.
     */
    public boolean readyBlock(LocalPlayer player, BlockGetter level, BlockPos pos, Vec3 aimPoint, double reach) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(look(player).scale(reach));
        boolean hits = shape.isEmpty() ? new AABB(pos).clip(eye, end).isPresent() : shape.clip(eye, end, pos) != null;
        java.util.function.BiPredicate<Vec3, Vec3> ray = (a, b) -> shape.isEmpty() ? new AABB(pos).clip(a, b).isPresent()
                : shape.clip(a, b, pos) != null;
        return settle(player, hits, aimPoint, ray, reach, pos.toShortString());
    }

    /** As {@link #readyBlock}, for an entity (or any) box. */
    public boolean readyBox(LocalPlayer player, AABB box, Vec3 aimPoint, double reach) {
        Vec3 eye = player.getEyePosition();
        boolean hits = box.contains(eye) || box.clip(eye, eye.add(look(player).scale(reach))).isPresent();
        java.util.function.BiPredicate<Vec3, Vec3> ray = (a, b) -> box.contains(a) || box.clip(a, b).isPresent();
        return settle(player, hits, aimPoint, ray, reach, String.format(java.util.Locale.US, "box at %.1f, %.1f, %.1f",
                box.getCenter().x, box.getCenter().y, box.getCenter().z));
    }

    private boolean settle(LocalPlayer player, boolean hits, Vec3 aimPoint, java.util.function.BiPredicate<Vec3, Vec3> ray,
                           double reach, String what) {
        if (hits) {
            pending = false; // acting now; the body (if turned) is still held this tick and given back the next
            foreignWait = 0;
            LOGGER.info("[TurnFirst] click on {} with {}", what, turned ? method : "the look he already had");
            return true;
        }
        if (current != null && current != this) {
            return false; // another aura's turn is out: wait for its hand-back instead of turning him under it
        }
        if (!turned && bodyOwned()) {
            return false; // AP3 is aligning or aiming with this body: the turn waits for it to let go
        }
        if (!turned && ViewFreeze.isHeld() && !ownHold.getAsBoolean() && ++foreignWait <= FOREIGN_WAIT) {
            // Somebody else holds the camera, so somebody else is turning his body - Auto Boulder walks him to the bars
            // and turns him to the chest at a human pace for Secret Aura. A snap from here would cut across that turn
            // (93-solve-boulder-aura's camera check, 2026-10-07). Give them half a second to bring the look round.
            return false;
        }
        foreignWait = 0;
        Vec3 d = aimPoint.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        float[] allowed = yawConstraint();
        if (allowed != null) {
            // A walk is steering him: only a yaw that some key combination walks exactly along the walk at, with a pitch
            // whose ray meets the target from where the click will be judged - the eye after this tick's move (the
            // click goes out at the next START, judged by the position and look of this tick's movement packet).
            Vec3 v = player.getDeltaMovement();
            Vec3 eyeNext = player.getEyePosition().add(v.x, 0.0, v.z);
            float[] found = walkKeyedLook(allowed, eyeNext, pitch, ray, reach);
            if (found == null) {
                method = "deferred";
                return false; // none this tick: no turn at all; the bearing moves as he walks, try again next tick
            }
            yaw = found[0];
            pitch = found[1];
            method = String.format(java.util.Locale.US, "a walk-keyed look (yaw %.1f, pitch %.1f: the walk's path unchanged)",
                    yaw, pitch);
        } else {
            method = String.format(java.util.Locale.US, "a body turn (yaw %.1f, pitch %.1f)", yaw, pitch);
        }
        if (!turned) {
            ownsHold = !ViewFreeze.isHeld();
            prevYaw = player.getYRot();
            prevPitch = player.getXRot();
        }
        ViewFreeze.hold(player.getYRot(), player.getXRot());
        myLastHold = ViewFreeze.holdCount();
        float run = player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot());
        player.setYRot(run);
        player.setYHeadRot(run);
        player.setXRot(Mth.clamp(pitch, -90f, 90f));
        onWrite.run();
        turned = true;
        current = this;
        ticksSinceTurn = 0;
        pending = true;
        pendingAge = 0;
        return false;
    }

    /** The first allowed yaw with a pitch (searched out from {@code pitch0} in quarter degrees) whose ray from {@code eye}
     *  meets the target within {@code reach}; null when none does. */
    private static float[] walkKeyedLook(float[] yaws, Vec3 eye, float pitch0, java.util.function.BiPredicate<Vec3, Vec3> ray,
                                         double reach) {
        for (float yaw : yaws) {
            for (int i = 0; i <= 720; i++) {
                float step = (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 0.25f;
                float pitch = pitch0 + step;
                if (pitch < -90f || pitch > 90f) {
                    continue;
                }
                float yr = yaw * Mth.DEG_TO_RAD;
                float pr = pitch * Mth.DEG_TO_RAD;
                float cp = Mth.cos(pr);
                Vec3 dir = new Vec3(-Mth.sin(yr) * cp, -Mth.sin(pr), Mth.cos(yr) * cp);
                if (ray.test(eye, eye.add(dir.scale(reach)))) {
                    return new float[]{yaw, pitch};
                }
            }
        }
        return null;
    }

    /** The look the movement packets report: the body's rotation, NOT getViewVector (which a held camera answers). */
    public static Vec3 look(LocalPlayer player) {
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float pitch = player.getXRot() * Mth.DEG_TO_RAD;
        float cp = Mth.cos(pitch);
        return new Vec3(-Mth.sin(yaw) * cp, -Mth.sin(pitch), Mth.cos(yaw) * cp);
    }
}
