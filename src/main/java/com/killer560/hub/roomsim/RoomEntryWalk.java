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

    /** Ticks of no forward progress before calling it stuck. */
    private static final int STUCK_TICKS = 20;

    /** A jump larger than this between ticks is the server moving us, not us walking. */
    private static final double TELEPORT_JUMP = 4.0;

    private static Leg leg = Leg.IDLE;
    private static Vec3 legStart = Vec3.ZERO;
    private static Vec3 lastPos = Vec3.ZERO;
    private static double legTarget;
    private static int legTicks;
    private static int stuckTicks;
    private static double bestProgress;

    /** The facing we entered with. Captured once, never recomputed - the whole path is relative to it. */
    private static float entryYaw;

    private RoomEntryWalk() {
    }

    public static boolean isWalking() {
        return leg != Leg.IDLE && leg != Leg.DONE;
    }

    /** Called when a fresh run starts, so the next dungeon walks again. */
    public static void reset() {
        leg = Leg.IDLE;
        releaseKeys();
    }

    /**
     * Starts the walk, if it has not run for this dungeon yet.
     *
     * @return whether a walk was started
     */
    public static boolean begin(Minecraft client) {
        if (leg != Leg.IDLE || client.player == null) {
            return false;
        }
        Entity mort = findMort(client);
        if (mort == null) {
            // No Mort: either not the spawn room or the NPC has not loaded. Skipped rather than guessed at -
            // walking a fixed distance towards nothing is how you end up in a wall.
            leg = Leg.DONE;
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

        double travelled = pos.subtract(legStart).dot(axisFor(leg));
        if (travelled > bestProgress + 0.05) {
            bestProgress = travelled;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            abort("stuck");
            return;
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
    }

    /**
     * Holds the key for this leg.
     *
     * <p>Only ever ONE key at a time. A diagonal would arrive sooner and is exactly the kind of input a person
     * does not produce when pacing out a fixed number of blocks.
     */
    private static void hold(Minecraft client, Leg which) {
        releaseKeys();
        switch (which) {
            case TO_MORT, FORWARD -> client.options.keyUp.setDown(true);
            case LEFT -> client.options.keyLeft.setDown(true);
            default -> { }
        }
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
