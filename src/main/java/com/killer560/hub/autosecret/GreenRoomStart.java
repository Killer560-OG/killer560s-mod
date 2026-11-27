package com.killer560.hub.autosecret;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.autoroutes.AutoRoutesFeature;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.pathfinding.ShortWalk;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.witherdoors.WitherDoorsFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Switched on in the green room before the run (Dungeons Autopilot, or an armed Auto Clear / Auto Secret), the wait is used
 * to walk to the front of the Entrance's door (killer560, 2026-11-22: "if I activated in Green room have it teleport is
 * close to the door as possible so that way it doesn't waste an extra two ether warps just getting to a Start spot"). The
 * Entrance has one door, so every first trip leaves through it; from the spawn the planner spent two warps reaching the
 * spot four blocks in from it (his 11-22 runs). It WALKS there (ShortWalk, discrete keys) unless it can warp.
 *
 * <p>Warp (autopilot-greenroom-warp-1126, killer560 2026-11-26: "it should etherwarp in green room to get to the gate before
 * it starts if autopilot is on not run there. or furthermore to whatever spot in the room most benefits it for the next"):
 * with Warp to Door Before Start on (off by default) and an etherwarp item in the hotbar, one sneaking etherwarp (ClearExecutor's
 * {@code etherDirectFast}, the map's own sender) puts him on the stand nearest the gate. Hypixel answers an etherwarp in the
 * green room before the start (his 11-23 and 11-25 logs: AOTV uses there answered by a landing 2 ticks later). That stand
 * is the best one for what comes next because every first trip leaves through the Entrance's only door: the nearer the
 * gate, the wider the look through the doorway and the further the first warp after the start reaches. No such look from
 * here, or a warp that did not land there: the walk, as before.
 *
 * <p>CHEAT BUILD ONLY (its callers are). His own movement, jump, attack or use key ends it for this arm (the body given
 * back, a warp in flight cancelled); a screen pauses the walk; the run's start ends it at once.
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
    /** A warp to the gate's stand is in flight (ClearExecutor's queue). */
    private static boolean warping;
    /** Ticks the warp has been out; a direct hop is answered in a few, so past this it missed (re-planned: cut). */
    private static final int WARP_TICKS = 12;
    private static int warpTicks;
    /** Busy ticks this warp may take: one straight hop, or a planned path (its search runs off thread first). */
    private static final int PATH_TICKS = 80;
    private static int warpBudget = WARP_TICKS;
    /** His hotbar slot when the warp began, given back once its use is out; and the newest hop by then. */
    private static int hisSlot = -1;
    private static long hopAtWarp;
    /** Warps that ended off their stand since the arm; after {@link #MAX_WARPS} it walks. */
    private static final int MAX_WARPS = 3;
    private static int warpMisses;
    /** The walk found no plan from here (logged once a stand). */
    private static BlockPos noPlanAt;
    /** This arm tried the warp already (landed, or could not): the rest is the walk's. */
    private static boolean warpTried;
    /** The Entrance door cell {@link #doorFront} last found. */
    private static int doorIdx;
    /**
     * Walks planned again from where he stands after one went off its route or stuck (acrun-1126-noon, his F7 at 11:35:17:
     * four blocks in, his body met a block the carrot's line crossed and the walk ended "off_route" in its 7th tick, short of
     * the door). At most {@link #MAX_REPLANS}; his own keys still end it at once.
     */
    private static final int MAX_REPLANS = 3;
    private static int replans;
    private static int retryIn;
    private static BlockPos goal;
    private static BlockPos testGoal;
    private static String result = "idle";
    /** Why it is not acting yet, last logged (one line per change, never per tick). */
    private static String lastWait;
    /** The world (identity hash) whose "Starting in 1 second." line came: the run starts in a second, nothing new begins. */
    private static int lastSecondLevel;

    private GreenRoomStart() {
    }

    /** Boot (cheat build): listens for the countdown's last line, the point after which a new warp would land in the run. */
    static void register() {
        ChatObserver.subscribe(m -> {
            if ("Starting in 1 second.".equals(ChatObserver.strip(m))) {
                lastSecondLevel = System.identityHashCode(Minecraft.getInstance().level);
            }
        });
    }

    private static boolean lastSecond(Minecraft client) {
        return lastSecondLevel != 0 && lastSecondLevel == System.identityHashCode(client.level);
    }

    private static void waiting(String why) {
        if (!why.equals(lastWait)) {
            lastWait = why;
            LOGGER.info("[GreenRoomStart] not acting yet: {}", why);
        }
    }

    /** An autopilot armed in the green room: walk to the door's front while the run has not started. */
    static void arm() {
        if (!registered) {
            registered = true;
            ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("GreenRoomStart.tick", GreenRoomStart::tick));
        }
        lastWait = null;
        done = false;
        warpTried = false;
        warpMisses = 0;
        replans = 0;
        noPlanAt = null;
        retryIn = 0;
        goal = null;
        result = "armed";
    }

    /** Test hook (hx: no Entrance there): the next arm walks to {@code feet} instead of the door's front; null clears. */
    public static void testGoal(BlockPos feet) {
        testGoal = feet;
    }

    private static boolean testUnidentified;

    /** Test hook: the dungeon reads as not identified yet (the join second) until cleared. */
    public static void testUnidentified(boolean v) {
        testUnidentified = v;
    }

    /** Test hook: what the last arm did (armed, warping, warped, warp missed, walking, arrived, you moved, ...). */
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
        if (done && !walking && !warping) {
            return;
        }
        LocalPlayer p = client.player;
        if (p == null || client.level == null || !armed() || WitherDoorsFeature.runStarted() || LiveMapFeature.isInBoss()) {
            end(p, armed() ? "the run started" : "switched off");
            return;
        }
        if (testUnidentified || !DungeonState.isInDungeon()) {
            // Armed while the world was still loading (his key in the join second, 11-27 runs 3 and 6): the sidebar names
            // the floor a few ticks later, so wait for it, never give the arm up. The arming feature ends on a world change.
            if (warping || walking) {
                end(p, "left the dungeon");
            } else {
                waiting("the dungeon is not identified yet (the sidebar is not read)");
            }
            return;
        }
        if (!warping && !walking && lastSecond(client)) {
            end(p, "the run starts in a second (\"Starting in 1 second.\" was already said) - the autopilot takes over");
            return;
        }
        if (hisInput(client.options)) {
            LOGGER.info("[GreenRoomStart] you moved - the keys are yours until the run starts");
            end(p, "you moved");
            return;
        }
        if (warping) {
            tickWarp(client, p);
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
        if ((s == ShortWalk.Status.OFF_ROUTE || s == ShortWalk.Status.STUCK) && replans < MAX_REPLANS) {
            replans++;
            String why = s.name().toLowerCase(java.util.Locale.ROOT);
            LOGGER.info("[GreenRoomStart] the walk went {} at {} - planning it again from here ({} of {})", why,
                    p.blockPosition().toShortString(), replans, MAX_REPLANS);
            ShortWalk.stop(why + ", planned again");
            walking = false;
            retryIn = 0;
        } else if (s != ShortWalk.Status.WALKING) {
            end(p, s == ShortWalk.Status.ARRIVED ? "arrived" : s.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static boolean hisInput(Options o) {
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown()
                || o.keyAttack.isDown() || o.keyUse.isDown();
    }

    private static void start(Minecraft client, LocalPlayer p) {
        retryIn = 4;   // the world scan may not have the door yet: asked again in four ticks
        if (!p.onGround()) {
            waiting("not on the ground yet");
            return;
        }
        if (ShortWalk.isActive()) {
            waiting("another walk is running");
            return;
        }
        if (!warpTried && startWarp(client, p)) {
            return;
        }
        if (!ShortWalk.mixinApplied()) {
            waiting("the walk helper is not applied");
            return;
        }
        BlockPos to = testGoal != null ? testGoal : doorFront(p);
        if (to == null) {
            waiting("no Entrance door in the map's layout within a room of him yet");
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
            if (!p.blockPosition().equals(noPlanAt)) {
                noPlanAt = p.blockPosition();
                LOGGER.info("[GreenRoomStart] no walk from {} to {} - asked again each second", noPlanAt.toShortString(),
                        to.toShortString());
            }
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
     * One etherwarp onto the stand nearest the gate that a sneaking look from here lands on (testGoal on hx), or false
     * (switch off, no etherwarp item, no Entrance door yet, no such look, the map's executor busy): the walk then.
     */
    private static boolean startWarp(Minecraft client, LocalPlayer p) {
        if (!AutoSecretConfig.getInstance().isGreenRoomWarp()) {
            warpTried = true;
            LOGGER.info("[GreenRoomStart] Warp to Door Before Start is off - walking");
            return false;
        }
        if (ItemIdentity.findEtherwarpSlot(p) < 0) {
            warpTried = true;
            LOGGER.info("[GreenRoomStart] no warp to the gate (no etherwarp item in the hotbar) - walking");
            return false;
        }
        if (ClearExecutor.isBusy()) {
            waiting("the map's warp runner is busy");   // asked again in four ticks, not a reason to walk
            return true;
        }
        BlockPos[] stands = testGoal != null ? new BlockPos[]{testGoal} : gateStands(p);
        if (stands == null) {
            waiting("no Entrance door in the map's layout within a room of him yet");
            return true;   // asked again in four ticks
        }
        warpTried = true;
        Vec3 eye = p.position().add(0, TeleportUtils.eyeHeight(true), 0);
        double range = ClearExecutor.hopRange() + 1.0;
        for (BlockPos feet : stands) {
            if (Math.hypot(feet.getX() + 0.5 - p.getX(), feet.getZ() + 0.5 - p.getZ()) < 1.0) {
                done = true;
                result = "there";
                return true;
            }
            if (TeleportUtils.getEtherwarpDirection(eye, feet.below(), range) == null) {
                continue;
            }
            AutoRoutesFeature.cancelForInteractiveMap(WHO);
            ClearExecutor.swapAtFirstHop(true);
            if (!ClearExecutor.etherDirectFast(feet.below(), null)) {
                ClearExecutor.swapAtFirstHop(false);
                return false;
            }
            beginWarp(p, feet, WARP_TICKS);
            LOGGER.info("[GreenRoomStart] etherwarping to the stand by the Entrance gate at {} before the run starts",
                    feet.toShortString());
            return true;
        }
        if (stands.length == 0) {
            return false;
        }
        // No straight look (the green room's spawn ledge hides the floor by the gate: its slab edge is in every line from
        // the sim's Entrance spawn): the planner's warps there, the first onto a spot that sees the stand.
        LOGGER.info("[GreenRoomStart] etherwarping to the stand by the Entrance gate at {} in planned warps before the run"
                + " starts: no straight look from {} (eye y {}) onto any of {} stand(s) (first: {})", stands[0].toShortString(),
                p.blockPosition().toShortString(), String.format(java.util.Locale.ROOT, "%.2f", eye.y), stands.length,
                blockers(p, eye, stands));
        AutoRoutesFeature.cancelForInteractiveMap(WHO);
        ClearExecutor.swapAtFirstHop(true);
        ClearExecutor.etherPathArriveInRoom(stands[0].below());
        beginWarp(p, stands[0], PATH_TICKS);
        return true;
    }

    private static void beginWarp(LocalPlayer p, BlockPos feet, int budget) {
        ClearExecutor.setExternalOwner(true);
        goal = feet;
        warping = true;
        warpTicks = 0;
        warpBudget = budget;
        hisSlot = p.getInventory().getSelectedSlot();
        hopAtWarp = lastHop();
        result = "warping";
    }

    /** The warp's tick: once its queue is over it landed by the gate (done) or the walk takes over at once. */
    private static void tickWarp(Minecraft client, LocalPlayer p) {
        ClearExecutor.finishArrivalEarly(client);
        giveSlotBack(client, p);
        if (ClearExecutor.isBusy() && ++warpTicks <= warpBudget) {
            return;
        }
        if (ClearExecutor.isBusy()) {
            ClearExecutor.cancel();   // a missed hop is re-planned there, and before the start that is a gate hold: walk
        }
        warping = false;
        ClearExecutor.setExternalOwner(false);
        giveSlotBack(client, p);
        hisSlot = -1;
        double off = Math.hypot(goal.getX() + 0.5 - p.getX(), goal.getZ() + 0.5 - p.getZ());
        if (off < 1.0) {
            done = true;
            result = "warped";
            LOGGER.info("[GreenRoomStart] warped to {} - waiting there for the run to start", goal.toShortString());
            return;
        }
        result = "warp missed";
        retryIn = 0;
        warpTried = ++warpMisses >= MAX_WARPS;   // a landing it did not plan (his click, a correction): warp again from there
        LOGGER.info("[GreenRoomStart] the warp ended {} from {} - {} from here",
                String.format(java.util.Locale.ROOT, "%.1f", off), goal.toShortString(), warpTried ? "walking" : "warping again");
    }

    /**
     * Once the warp's use is out, his own hotbar slot back (the next tick at the soonest, never behind a use in one server
     * tick): his right clicks before the start go with HIS item, not the warp item the trip swapped to, which made each a
     * 12-block Instant Transmission (1126-hx-greenwarp-same-use). A hop sent again swaps to the warp item itself.
     */
    /** The client tick of ClearExecutor's newest hop use (his own clicks are not hops), -1 before any. */
    private static long lastHop() {
        java.util.List<Long> h = ClearExecutor.hopSendTicks();
        return h.isEmpty() ? -1 : h.get(h.size() - 1);
    }

    private static void giveSlotBack(Minecraft client, LocalPlayer p) {
        if (hisSlot < 0 || p.getInventory().getSelectedSlot() == hisSlot
                || lastHop() == hopAtWarp
                || com.killer560.hub.util.HotbarSelect.swapWouldFlag() || !com.killer560.hub.util.HotbarSelect.inTick()) {
            return;
        }
        com.killer560.hub.util.HotbarSelect.select(client, p, hisSlot);
        hopAtWarp = lastHop();
    }

    /**
     * The feet blocks 1 to {@link #IN_FROM_DOOR} in from the Entrance's door on his side, nearest the gate first, or null
     * while the layout has no Entrance door within a room of him.
     */
    static BlockPos[] gateStands(LocalPlayer p) {
        BlockPos far = doorFront(p);
        if (far == null) {
            return null;
        }
        BlockPos door = DungeonLayout.doorBlock(doorIdx);
        int dx = Integer.signum(far.getX() - door.getX());
        int dz = Integer.signum(far.getZ() - door.getZ());
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int k = 1; k <= STAND_DEPTH; k++) {
            for (int l : LATERAL) {
                BlockPos feet = standIn(p, door.offset(dx * k + dz * l, 0, dz * k + dx * l));
                if (feet != null) {
                    out.add(feet);
                }
            }
        }
        return out.toArray(new BlockPos[0]);
    }

    /** Rows of stands tried in from the gate, and their offsets across the doorway, nearest the door's middle first. */
    private static final int STAND_DEPTH = 6;
    private static final int[] LATERAL = {0, -1, 1, -2, 2};

    /** The feet block in this column at the floor height, one up (a step or slab), one or two down, or null. */
    private static BlockPos standIn(LocalPlayer p, BlockPos at) {
        var level = p.level();
        for (int dy : new int[]{0, 1, -1, -2}) {
            BlockPos feet = at.above(dy);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return feet;
            }
        }
        return null;
    }

    /** What the straight line from his eye to the top of the nearest stands' floor meets first (the no-look log line). */
    private static String blockers(LocalPlayer p, Vec3 eye, BlockPos[] stands) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(4, stands.length); i++) {
            BlockPos f = stands[i];
            TeleportUtils.RaycastResult r = TeleportUtils.traverseVoxels(p.level(), eye.x, eye.y, eye.z, f.getX() + 0.5,
                    f.getY() - 0.001, f.getZ() + 0.5, true);
            b.append(i == 0 ? "" : "; ").append(f.toShortString()).append(" -> ")
                    .append(r.pos() == null ? "nothing" : r.pos().toShortString() + " " + r.state().getBlock().getName().getString());
        }
        return b.toString();
    }

    /**
     * The feet block {@link #IN_FROM_DOOR} in from the Entrance's door on his side, or null while the layout has no
     * Entrance door within a room of him. A door cell has one odd grid coordinate: the way through it runs along that axis.
     */
    static BlockPos doorFront(LocalPlayer p) {
        DungeonLayout layout = DungeonLayout.current();
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!ClearExecutor.entranceDoor(layout, idx)) {   // the map may type the Entrance's door a normal one
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
            doorIdx = idx;
            return d.offset(dx * IN_FROM_DOOR, 0, dz * IN_FROM_DOOR);
        }
        return null;
    }

    private static void end(LocalPlayer p, String why) {
        if (warping) {
            warping = false;
            if (p != null) {
                giveSlotBack(Minecraft.getInstance(), p);
            }
            hisSlot = -1;
            if (!"the run started".equals(why)) {
                ClearExecutor.cancel();   // his keys or switched off; at the start it lands and the run's trip follows
            }
            ClearExecutor.setExternalOwner(false);
        }
        if (walking) {
            ShortWalk.stop(why);
            walking = false;
        }
        BODY.giveBack(p);
        if (!done) {
            done = true;
            result = why;
            LOGGER.info("[GreenRoomStart] ended: {}", why);
        }
    }
}
