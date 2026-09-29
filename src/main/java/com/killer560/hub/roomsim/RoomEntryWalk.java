package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The few steps into the spawn room that pull an extra ring of rooms into render distance.
 *
 * <p>killer560 (2026-09-28): "once it senses it is in the dungeon [have it] run forward to Mort's coordinates
 * (the npc) then goes left 5 blocks then forward 2. [...] that loads in an additional layer of rooms."
 *
 * <p>Worth the trouble because the recorder's whole yield is bounded by what the client has in memory: it
 * already sweeps every loaded room, so moving the player a few blocks deeper is the cheapest way to raise the
 * per-run haul. His first two runs took 22 rooms standing still at the entrance.
 *
 * <p><b>How it moves, and why it moves that way.</b> Discrete key presses on the real key mappings, exactly as
 * a hand would, and <b>no rotation at all</b>. The path is expressed the way he described it - forward, then
 * left, then forward - relative to the facing Hypixel drops you in with, so there is nothing to aim and no yaw
 * to fabricate. That is not laziness: a synthesised rotation is the single most recognisable thing this mod
 * could do here, and this task genuinely does not need one. Nothing in this file writes position or velocity,
 * and nothing uses fractional input.
 *
 * <p>Distance is measured from where the leg started, by projecting real movement onto the entry facing, so it
 * stops after the distance it actually travelled rather than after a count of ticks. If the player stops making
 * progress - a wall, a mob, a lag spike - it gives up and lets the scan carry on rather than holding a key
 * against something forever.
 */
public final class RoomEntryWalk {

    /** Legs of the walk, in order. */
    private enum Leg {
        /** Not walking. */
        IDLE,
        /** Forward, as far as Mort is. */
        TO_MORT,
        /** Five blocks left. */
        LEFT,
        /** Two blocks further forward. */
        FORWARD,
        /** Finished, successfully or not - either way the scan carries on. */
        DONE
    }

    private static final String MORT = "Mort";
    private static final double LEFT_BLOCKS = 5.0;
    private static final double FORWARD_BLOCKS = 2.0;

    /** Close enough. Sub-block precision would mean holding a key for single ticks, which is not how a hand
     *  walks and buys nothing - the point is to load chunks, not to land on a coordinate. */
    private static final double ARRIVE_EPSILON = 0.6;

    /** A leg that has not finished in this many ticks has hit something. */
    private static final int LEG_TIMEOUT_TICKS = 140;

    /** Ticks of no forward progress before calling it stuck, once there is nothing left to try. */
    private static final int STUCK_TICKS = 20;

    /**
     * Ticks of no progress before trying a jump.
     *
     * <p>killer560 (2026-09-28): "it does need to jump a few blocks in because their is a railing infront of
     * it." Handled by jumping WHEN BLOCKED rather than at a hardcoded distance, which is the same thing from
     * his side and does not quietly stop working the day Hypixel moves the railing a block - a fixed jump point
     * would then jump at open air and still walk into the obstacle. Stalling is also the signal a person acts
     * on: you jump because you stopped, not because you counted your steps.
     */
    private static final int STALL_BEFORE_JUMP_TICKS = 6;

    /** How many times to try jumping one obstacle before accepting it is not a railing. */
    private static final int MAX_JUMPS_PER_LEG = 3;

    /** How long to hold jump. One tick is enough to leave the ground; holding longer is not how a hop looks. */
    private static final int JUMP_HOLD_TICKS = 2;

    /** A jump larger than this between ticks is the server moving us, not us walking. */
    private static final double TELEPORT_JUMP = 4.0;

    private static Leg leg = Leg.IDLE;
    private static Vec3 legStart = Vec3.ZERO;
    private static Vec3 lastPos = Vec3.ZERO;
    private static double legTarget;
    private static int legTicks;
    private static int stuckTicks;
    private static double bestProgress;
    private static int jumpsThisLeg;
    private static int jumpHeldTicks;

    /** The facing we entered with. Captured once, never recomputed - the whole path is relative to it. */
    private static float entryYaw;

    /**
     * The world we were in when the run was reset, so we can tell a NEW dungeon from the last one.
     *
     * <p>killer560 (2026-09-28): "it worked the first room then once it clicked to start the second one it
     * started running and jumping instead of waiting for the next dungeon to load." This is why. The only gate
     * was "are we in a dungeon", and that is still TRUE for the old dungeon while the next one is loading - the
     * scoreboard has not caught up yet - so the walk fired during the transfer and paced out its five blocks in
     * whatever world happened to be under it.
     *
     * <p>A world swap is the one signal that cannot be stale: the level object is literally replaced. Held
     * weakly so a finished dungeon is not kept alive by this field.
     */
    private static java.lang.ref.WeakReference<Object> levelAtReset = new java.lang.ref.WeakReference<>(null);

    /** Ticks since a new world appeared, so the walk does not start mid-load. */
    private static int settleTicks;

    /** Ticks spent waiting for Mort before accepting he is not coming. */
    private static int waitingTicks;

    /** Let the world settle before moving - chunks, the player's own position, and the entry facing. */
    private static final int SETTLE_TICKS = 20;

    /** How long to keep looking for Mort after the world loads. */
    private static final int MORT_WAIT_TICKS = 200;

    /** Mort is right there at the spawn. Further than this and we are not where we think we are. */
    private static final double MORT_MAX_DISTANCE = 40.0;

    private RoomEntryWalk() {
    }

    public static boolean isWalking() {
        return leg != Leg.IDLE && leg != Leg.DONE;
    }

    /** Called when a fresh run starts, so the next dungeon walks again. */
    public static void reset() {
        leg = Leg.IDLE;
        settleTicks = 0;
        waitingTicks = 0;
        Minecraft client = Minecraft.getInstance();
        // Remember the world we are leaving. The walk stays shut until this is not the world any more.
        levelAtReset = new java.lang.ref.WeakReference<>(client.level);
        releaseKeys();
    }

    /**
     * Starts the walk, if it has not run for this dungeon yet.
     *
     * @return whether a walk was started
     */
    public static boolean begin(Minecraft client) {
        if (leg != Leg.IDLE || client.player == null || client.level == null) {
            return false;
        }
        if (client.level == levelAtReset.get()) {
            // Still the world we were in when the run was reset - the next dungeon has not loaded yet.
            return false;
        }
        if (++settleTicks < SETTLE_TICKS) {
            // Just arrived. Walking on the first tick of a world is how you walk before the floor exists.
            return false;
        }
        Entity mort = findMort(client);
        if (mort == null || mort.distanceToSqr(client.player) > MORT_MAX_DISTANCE * MORT_MAX_DISTANCE) {
            // He may simply not have loaded yet, so keep looking for a while rather than giving up the way this
            // used to on the first miss - that turned "the NPC was a tick late" into "no walk for this run".
            // But not forever: starting this walk in the middle of a run would pace five blocks sideways from
            // wherever he happens to be standing.
            if (++waitingTicks > MORT_WAIT_TICKS) {
                leg = Leg.DONE;
            }
            return false;
        }
        entryYaw = client.player.getYRot();
        lastPos = client.player.position();
        // How far forward Mort is, along the facing we came in with.
        double forwardToMort = mort.position().subtract(client.player.position()).dot(forward());
        if (forwardToMort <= ARRIVE_EPSILON) {
            // Already level with him or past him: skip straight to the sidestep.
            startLeg(client, Leg.LEFT, LEFT_BLOCKS);
        } else {
            startLeg(client, Leg.TO_MORT, forwardToMort);
        }
        return true;
    }

    /** One tick of walking. Safe to call when idle. */
    public static void tick(Minecraft client) {
        if (!isWalking()) {
            return;
        }
        if (client.player == null) {
            abort("no player");
            return;
        }
        Vec3 pos = client.player.position();

        // The server moved us - a teleport, a lag-back, a door. Whatever it was, the measured distance is no
        // longer about our own walking, so stop rather than keep pressing against it.
        if (pos.distanceTo(lastPos) > TELEPORT_JUMP) {
            abort("moved by the server");
            return;
        }
        lastPos = pos;

        // Let go of a jump that has done its job before anything else looks at progress.
        if (jumpHeldTicks > 0 && --jumpHeldTicks == 0) {
            client.options.keyJump.setDown(false);
        }

        double travelled = pos.subtract(legStart).dot(axisFor(leg));
        if (travelled > bestProgress + 0.05) {
            bestProgress = travelled;
            stuckTicks = 0;
        } else {
            stuckTicks++;
            if (stuckTicks == STALL_BEFORE_JUMP_TICKS && jumpsThisLeg < MAX_JUMPS_PER_LEG) {
                // Blocked by something low - the entrance railing, a step, a slab. Hop it and carry on; the
                // forward key is still held, so this is a jump WHILE walking, which is the only way over
                // anything and also the only shape of input that makes sense here.
                jumpsThisLeg++;
                jumpHeldTicks = JUMP_HOLD_TICKS;
                client.options.keyJump.setDown(true);
                stuckTicks = 0;
                return;
            }
            if (stuckTicks > STUCK_TICKS) {
                abort(jumpsThisLeg > 0 ? "blocked, jumping did not help" : "stuck");
                return;
            }
        }
        if (++legTicks > LEG_TIMEOUT_TICKS) {
            abort("took too long");
            return;
        }

        if (travelled >= legTarget - ARRIVE_EPSILON) {
            releaseKeys();
            switch (leg) {
                case TO_MORT -> startLeg(client, Leg.LEFT, LEFT_BLOCKS);
                case LEFT -> startLeg(client, Leg.FORWARD, FORWARD_BLOCKS);
                default -> {
                    leg = Leg.DONE;
                    ModChat.send("Room Recorder", ModChat.dim("walked in - more rooms in range"));
                }
            }
            return;
        }
        hold(client, leg);
    }

    private static void startLeg(Minecraft client, Leg next, double blocks) {
        leg = next;
        legStart = client.player.position();
        legTarget = blocks;
        legTicks = 0;
        stuckTicks = 0;
        bestProgress = 0;
        jumpsThisLeg = 0;
    }

    /**
     * Holds the key for this leg.
     *
     * <p>Only ever ONE key at a time. A diagonal would arrive sooner and is exactly the kind of input a person
     * does not produce when pacing out a fixed number of blocks.
     */
    private static void hold(Minecraft client, Leg which) {
        // Movement keys only - NOT the jump, which is on its own short timer. Clearing it here would cancel
        // every hop on the tick after it started and leave the walk pressing into the railing forever.
        client.options.keyUp.setDown(which == Leg.TO_MORT || which == Leg.FORWARD);
        client.options.keyLeft.setDown(which == Leg.LEFT);
        client.options.keyRight.setDown(false);
        client.options.keyDown.setDown(false);
    }

    private static void releaseKeys() {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyJump.setDown(false);
        jumpHeldTicks = 0;
    }

    private static void abort(String why) {
        releaseKeys();
        leg = Leg.DONE;
        ModChat.send("Room Recorder", ModChat.dim("walk-in stopped (" + why + ") - scanning from here"));
    }

    /** Unit vector along the entry facing. */
    private static Vec3 forward() {
        double rad = Math.toRadians(entryYaw);
        return new Vec3(-Math.sin(rad), 0, Math.cos(rad));
    }

    /** Unit vector to the entry facing's left. */
    private static Vec3 left() {
        Vec3 f = forward();
        return new Vec3(f.z, 0, -f.x);
    }

    private static Vec3 axisFor(Leg which) {
        return which == Leg.LEFT ? left() : forward();
    }

    /** Mort, the NPC by the entrance. Matched on the display name the same way the mob sweep matches stars. */
    private static Entity findMort(Minecraft client) {
        if (client.level == null || client.player == null) {
            return null;
        }
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : client.level.entitiesForRendering()) {
            var name = e.getCustomName();
            if (name == null || !name.getString().contains(MORT)) {
                continue;
            }
            double d = e.distanceToSqr(client.player);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}
