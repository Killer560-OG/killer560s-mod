package com.killer560.hub.autosecret;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.pathfinding.ShortWalk;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.witherdoors.WitherDoorsFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

/**
 * Switched on in the green room before the run (Dungeons Autopilot, or an armed Auto Clear / Auto Secret), the wait is used
 * to walk to the front of the Entrance's door (killer560, 2026-11-22: "if I activated in Green room have it teleport is
 * close to the door as possible so that way it doesn't waste an extra two ether warps just getting to a Start spot"). The
 * Entrance has one door, so every first trip leaves through it; from the spawn the planner spent two warps reaching the
 * spot four blocks in from it (his 11-22 runs). It WALKS (ShortWalk, discrete keys): nothing shows an etherwarp works
 * before Mort's line. CHEAT BUILD ONLY (its callers are). His own movement, jump, attack or use key ends it for this arm
 * (the body given back); a screen pauses it; the run's start ends it at once.
 */
public final class GreenRoomStart {
    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");
    private static final String WHO = "Green room start";
    /** Blocks in from the door block: where the planner's own first trips from the spawn stood (z -172.5 by -169). */
    static final int IN_FROM_DOOR = 4;
    private static final BodyAim BODY = new BodyAim(() -> { });

    private static boolean registered;
    /** Nothing more this arm: arrived, no way on foot, or he took over. */
    private static boolean done = true;
    private static boolean walking;
    private static int retryIn;
    private static BlockPos goal;
    private static BlockPos testGoal;
    private static String result = "idle";

    private GreenRoomStart() {
    }

    /** An autopilot armed in the green room: walk to the door's front while the run has not started. */
    static void arm() {
        if (!registered) {
            registered = true;
            ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("GreenRoomStart.tick", GreenRoomStart::tick));
        }
        done = false;
        retryIn = 0;
        goal = null;
        result = "armed";
    }

    /** Test hook (hx: no Entrance there): the next arm walks to {@code feet} instead of the door's front; null clears. */
    public static void testGoal(BlockPos feet) {
        testGoal = feet;
    }

    /** Test hook: what the last arm did (armed, walking, arrived, you moved, ...). */
    public static String result() {
        return result;
    }

    /** Test hook: the feet block it walks or walked to, or null. */
    public static BlockPos goal() {
        return goal;
    }

    private static boolean armed() {
        return DungeonsAutopilot.isArmed() || ArmedStart.pending() != null;
    }

    private static void tick(Minecraft client) {
        if (done && !walking) {
            return;
        }
        LocalPlayer p = client.player;
        if (p == null || client.level == null || !armed() || WitherDoorsFeature.runStarted() || !DungeonState.isInDungeon()
                || LiveMapFeature.isInBoss()) {
            end(p, armed() ? "the run started" : "switched off");
            return;
        }
        if (hisInput(client.options)) {
            LOGGER.info("[GreenRoomStart] you moved - the keys are yours until the run starts");
            end(p, "you moved");
            return;
        }
        if (McCompat.screen(client) != null) {
            if (walking) {
                ShortWalk.stop("a screen opened");
                walking = false;
                BODY.giveBack(p);
                retryIn = 10;
            }
            return;
        }
        if (!walking) {
            if (retryIn-- <= 0) {
                start(client, p);
            }
            return;
        }
        BODY.tick(p, true);
        float pitch = Mth.clamp(p.getXRot(), -90f, 90f);
        ShortWalk.Status s = ShortWalk.tick(client.level, p, yaw -> BODY.turn(p, yaw, pitch));
        if (s != ShortWalk.Status.WALKING) {
            end(p, s == ShortWalk.Status.ARRIVED ? "arrived" : s.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static boolean hisInput(Options o) {
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown()
                || o.keyAttack.isDown() || o.keyUse.isDown();
    }

    private static void start(Minecraft client, LocalPlayer p) {
        retryIn = 20;   // the world scan may not have the door yet: asked again in a second
        if (!p.onGround() || ShortWalk.isActive() || !ShortWalk.mixinApplied()) {
            return;
        }
        BlockPos to = testGoal != null ? testGoal : doorFront(p);
        if (to == null) {
            return;
        }
        double off = Math.hypot(to.getX() + 0.5 - p.getX(), to.getZ() + 0.5 - p.getZ());
        if (off < 1.0) {
            done = true;
            result = "there";
            return;
        }
        ShortWalk.Plan plan = ShortWalk.plan(client.level, p, to, null);
        if (plan == null) {
            plan = ShortWalk.plan(client.level, p, to.above(), null);   // a step or slab at the spot
        }
        if (plan == null) {
            return;
        }
        goal = plan.goal();
        walking = true;
        result = "walking";
        LOGGER.info("[GreenRoomStart] walking to the Entrance door's front at {} ({}) before the run starts",
                goal.toShortString(), plan.describe());
        ShortWalk.start(plan, WHO);
    }

    /**
     * The feet block {@link #IN_FROM_DOOR} in from the Entrance's door on his side, or null while the layout has no
     * Entrance door within a room of him. A door cell has one odd grid coordinate: the way through it runs along that axis.
     */
    static BlockPos doorFront(LocalPlayer p) {
        DungeonLayout layout = DungeonLayout.current();
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (layout.doorType(idx) != DungeonLayout.DOOR_ENTRANCE) {
                continue;
            }
            BlockPos d = DungeonLayout.doorBlock(idx);
            double ox = p.getX() - (d.getX() + 0.5);
            double oz = p.getZ() - (d.getZ() + 0.5);
            if (Math.hypot(ox, oz) > 24) {
                continue;
            }
            boolean alongX = (idx % DungeonLayout.GRID) % 2 == 1;
            int dx = alongX ? (int) Math.signum(ox) : 0;
            int dz = alongX ? 0 : (int) Math.signum(oz);
            if (dx == 0 && dz == 0) {
                return null;
            }
            return d.offset(dx * IN_FROM_DOOR, 0, dz * IN_FROM_DOOR);
        }
        return null;
    }

    private static void end(LocalPlayer p, String why) {
        if (walking) {
            ShortWalk.stop(why);
            walking = false;
        }
        BODY.giveBack(p);
        if (!done) {
            done = true;
            result = why;
        }
    }
}
