package com.killer560.hub.autoclear;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.mobesp.MobEspFeature;
import com.killer560.hub.pathfinding.ShortWalk;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCorrections;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /test} (cheat build; killer560, 2026-10-20): a fixed set of Spirit Sceptre shots at a mob in a real dungeon run,
 * each bat's flight logged by {@link BatLog}, then a summary - so the bat's real speed and whether it curves can be
 * measured on Hypixel and put into {@link WeaponReach#BAT_BLOCKS_PER_TICK}.
 * <p>
 * It never starts itself: only his {@code /test}, in a dungeon's clear, with a Spirit Sceptre in the hotbar and no other
 * automation running. It picks a mob it can see (Auto Clear's starred mobs first, any hostile mob else), and for each
 * shot of {@link #SHOTS} - about 10, 20 and 30 blocks aimed straight at the mob, then 10 blocks aimed 15 degrees to the
 * side and 15 degrees up - moves to a block that far from the mob with a clear line to it (walking with Auto Clear's
 * {@link ShortWalk} when that is faster, else an Interactive Map etherwarp trip, the same choice Auto Clear makes), turns
 * the body with the camera held ({@link BodyAim}), and on the next tick presses use for exactly one bat. The next shot
 * waits on that bat's own end ({@link BatLog.Shot#done}), bounded by a timeout worked out from the shot's distance. A
 * shot that cannot be set up (no such block, a failed trip, a use that never left) is logged and skipped, never repeated:
 * at most {@code SHOTS.length} uses ever.
 * <p>
 * Ends after the set, or at once on: a movement or jump key he presses, a screen opening, a server correction (said in
 * chat with the alarm, as every correction is; it ends the test because this is a measurement, not a route), his death,
 * leaving the dungeon or the world, another automation starting, or {@link #RUN_TIMEOUT_TICKS}. Every line goes to
 * {@code killer560smod-bats} with the prefix {@code [BatTest]}.
 */
public final class BatTest {

    static final String CHAT = "Bat Test";

    /** One planned shot: about {@code blocks} from the mob, the look turned {@code yawOff}/{@code pitchOff} off it. */
    record Spec(String label, double blocks, float yawOff, float pitchOff) {
    }

    /** The set - its length is the most uses a test ever sends. Pitch is negative upward. */
    static final Spec[] SHOTS = {
            new Spec("10 blocks, straight", 10, 0f, 0f),
            new Spec("20 blocks, straight", 20, 0f, 0f),
            new Spec("30 blocks, straight", 30, 0f, 0f),
            new Spec("10 blocks, 15 deg to the side", 10, 15f, 0f),
            new Spec("10 blocks, 15 deg up", 10, 0f, -15f),
    };

    /** Where he stands is good enough for a shot when its distance to the mob is within this of the shot's. */
    static final double DISTANCE_TOLERANCE = 3.0;
    /** The whole test ends after this many ticks (two minutes) whatever it is doing. */
    static final int RUN_TIMEOUT_TICKS = 2400;
    /** A move (walk or trip) that has not ended in this many ticks ends the shot. */
    static final int MOVE_TIMEOUT_TICKS = 200;
    /** Settling onto the ground after a move, turning and the gate: this many ticks at most before the shot is skipped. */
    static final int AIM_TIMEOUT_TICKS = 20;
    /** The held use must have left within this many ticks, or the shot is skipped (never pressed again). */
    static final int USE_TIMEOUT_TICKS = 4;
    /** Mobs further than this are not looked at. */
    static final double MOB_RANGE = 40.0;

    private enum Phase { PICK, MOVE_TRIP, MOVE_WALK, AIM, FIRE, FLY }

    private static boolean running = false;
    private static Phase phase = Phase.PICK;
    private static int shotIndex = 0;
    private static int runTicks = 0;
    private static int phaseTicks = 0;
    private static int targetId = -1;
    private static String targetName = "";
    private static float aimYaw;
    private static float aimPitch;
    private static int usesBefore;
    private static int batShotNo = -1;
    private static int flightTimeout;
    private static double shotDistance;
    private static boolean useHeld = false;
    private static boolean bodyWanted = false;
    private static boolean sawNoScreen = false;
    private static int slotBefore = -1;
    private static Object level;
    private static final BodyAim BODY = new BodyAim(() -> { });
    private static final boolean[] wasDown = new boolean[5];
    private static final String[] KEY_NAMES = {"forward", "back", "left", "right", "jump"};

    // corrections
    private static volatile int positionPackets = 0;
    private static volatile Vec3 posBeforePacket = null;
    private static int positionPacketsSeen = 0;
    private static int executorCorrectionsSeen = 0;

    /** The outcome of each shot of the set, in order (null = not reached). */
    private record Outcome(Spec spec, String skipped, int batNo, double distance) {
    }

    private static final List<Outcome> OUTCOMES = new ArrayList<>();

    private BatTest() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("BatTest.tick", BatTest::tick));
        // A client command: never forwarded to the server (util/ServerCommands).
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("test").executes(ctx -> {
                    start();
                    return 1;
                })));
    }

    public static boolean isRunning() {
        return running;
    }

    /** /test: refuses unless every condition holds; a second /test while it runs ends it. */
    static void start() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (running) {
            stop("you typed /test again");
            return;
        }
        String refused = null;
        if (player == null || client.level == null) {
            refused = "not in a world";
        } else if (!DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            refused = "only in a dungeon's clear (not outside one, not in the boss)";
        } else if (sceptreSlot(player) < 0) {
            refused = "no Spirit Sceptre in the hotbar";
        } else if (player.isDeadOrDying()) {
            refused = "you are dead";
        } else if (AutoClearFeature.isBusy() || com.killer560.hub.autoroutes.RouteExecutor.isRunning()
                || ClearExecutor.isBusy() || ShortWalk.isActive()
                || com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
            refused = "another automation is running (Auto Clear, Auto Routes, a map trip, a walk or an auto puzzle)";
        }
        if (refused != null) {
            ModChat.send(CHAT, ModChat.bad("Not started: "), ModChat.text(refused));
            BatLog.LOGGER.info("[BatTest] refused: {}", refused);
            return;
        }
        running = true;
        phase = Phase.PICK;
        shotIndex = 0;
        runTicks = 0;
        phaseTicks = 0;
        targetId = -1;
        batShotNo = -1;
        OUTCOMES.clear();
        level = client.level;
        sawNoScreen = McCompat.screen(client) == null;
        slotBefore = player.getInventory().getSelectedSlot();
        positionPacketsSeen = positionPackets;
        executorCorrectionsSeen = ClearExecutor.serverCorrections();
        KeyMapping[] keys = keys(client);
        for (int i = 0; i < keys.length; i++) {
            wasDown[i] = keys[i].isDown();   // a key held already is the walk that got him here, not a stop
        }
        ModChat.send(CHAT, ModChat.good("Started"), ModChat.dim(" - " + SHOTS.length
                + " Spirit Sceptre shots; any movement key stops it"));
        BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] started at %s: %d shots %s, run timeout %d ticks",
                BatLog.fmt(player.position()), SHOTS.length, java.util.Arrays.stream(SHOTS).map(Spec::label).toList(),
                RUN_TIMEOUT_TICKS));
    }

    // =========================================================================================== ticking

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player != null) {
            BODY.tick(player, running && bodyWanted);
        }
        if (!running) {
            return;
        }
        String why = mustStop(client, player);
        if (why != null) {
            stop(why);
            return;
        }
        runTicks++;
        phaseTicks++;
        try {
            step(client, player);
        } catch (RuntimeException e) {
            BatLog.LOGGER.warn("[BatTest] tick failed", e);
            stop("internal error: " + e);
        }
    }

    /** Every reason to end the test now, or null. */
    private static String mustStop(Minecraft client, LocalPlayer player) {
        if (player == null || client.level == null || client.level != level) {
            return "the world changed";
        }
        if (player.isDeadOrDying()) {
            return "you died";
        }
        if (!DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return "left the dungeon's clear";
        }
        if (McCompat.screen(client) != null) {
            if (sawNoScreen) {
                return "a screen opened";
            }
        } else {
            sawNoScreen = true;
        }
        KeyMapping[] keys = keys(client);
        String pressed = null;
        for (int i = 0; i < keys.length; i++) {
            boolean down = keys[i].isDown();
            if (down && !wasDown[i] && McCompat.screen(client) == null && pressed == null) {
                pressed = KEY_NAMES[i];
            }
            wasDown[i] = down;
        }
        if (pressed != null) {
            return "you pressed " + pressed;
        }
        if (AutoClearFeature.isBusy() || com.killer560.hub.autoroutes.RouteExecutor.isRunning()
                || com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
            return "another automation started";
        }
        String correction = correction(player);
        if (correction != null) {
            return correction;
        }
        if (runTicks >= RUN_TIMEOUT_TICKS) {
            return "the test's time limit (" + RUN_TIMEOUT_TICKS + " ticks)";
        }
        return null;
    }

    private static void step(Minecraft client, LocalPlayer player) {
        switch (phase) {
            case PICK -> pick(client, player);
            case MOVE_TRIP -> stepTrip(client, player);
            case MOVE_WALK -> stepWalk(client, player);
            case AIM -> aim(client, player);
            case FIRE -> fire(client, player);
            case FLY -> fly(client, player);
        }
    }

    private static void to(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    // ---- choosing the mob and the block for this shot ----

    private static void pick(Minecraft client, LocalPlayer player) {
        if (shotIndex >= SHOTS.length) {
            finish();
            return;
        }
        Spec spec = SHOTS[shotIndex];
        Entity target = currentTarget(client);
        if (target == null) {
            target = pickTarget(client, player);
            if (target == null) {
                stop("no mob in sight within " + (int) MOB_RANGE + " blocks");
                return;
            }
            targetId = target.getId();
            targetName = target.getName().getString();
            BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] target %s id %d at %s, %.1f blocks (%s)", targetName,
                    targetId, BatLog.fmt(target.position()), target.distanceTo(player),
                    MobEspFeature.starredMobs(client).contains(target) ? "starred, Auto Clear's targeting"
                            : "no starred mob in sight - the nearest hostile mob"));
        }
        Vec3 centre = target.getBoundingBox().getCenter();
        double here = player.getEyePosition().distanceTo(centre);
        if (Math.abs(here - spec.blocks()) <= DISTANCE_TOLERANCE && clearLine(client, player, player.getEyePosition(), target)
                && player.onGround()) {
            BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] shot %d/%d (%s): shooting from here, %.2f blocks from %s",
                    shotIndex + 1, SHOTS.length, spec.label(), here, targetName));
            to(Phase.AIM);
            return;
        }
        BlockPos spot = standSpot(client, player, target, spec.blocks());
        if (spot == null) {
            skip(spec, String.format(Locale.US, "no block about %.0f blocks from %s with a clear line to it (here %.1f)",
                    spec.blocks(), targetName, here));
            return;
        }
        if (!player.onGround()) {
            if (phaseTicks > AIM_TIMEOUT_TICKS) {
                skip(spec, "not on the ground to start the move");
            }
            return;
        }
        Vec3 goalFeet = new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
        double straight = player.position().distanceTo(goalFeet);
        int warps = Math.max(1, (int) Math.ceil(straight / ClearExecutor.hopRange()));
        int etherTicks = AutoClearFeature.ETHER_FIXED_TICKS + AutoClearFeature.ETHER_TICKS_PER_WARP * warps;
        ShortWalk.Plan walk = null;
        if (ShortWalk.mixinApplied() && straight <= AutoClearFeature.WALK_MAX_BLOCKS) {
            walk = ShortWalk.plan(client.level, player, spot.above(), null);
        }
        boolean canTrip = AutoClearUtils.canPath(DungeonLayout.capture());
        boolean walking = walk != null && (walk.ticks() < etherTicks || !canTrip);
        if (!walking && canTrip && BODY.isHeld()) {
            // The body goes back to his view before a trip (ClearExecutor turns it with its own BodyAim); next tick.
            bodyWanted = false;
            return;
        }
        BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] shot %d/%d (%s): move to %s (%.1f blocks away; from there"
                        + " %.2f to %s) - walk %s vs etherwarp ~%d ticks (%d warp(s)%s) - %s", shotIndex + 1, SHOTS.length,
                spec.label(), spot.toShortString(), straight, goalFeet.add(0, player.getEyeHeight(), 0).distanceTo(centre),
                targetName, walk == null ? "none" : "~" + walk.ticks() + " ticks", etherTicks, warps,
                canTrip ? "" : ", no trip from here", walking ? "walking" : canTrip ? "etherwarp" : "neither"));
        if (walking) {
            ShortWalk.start(walk, "Bat Test");
            bodyWanted = true;
            to(Phase.MOVE_WALK);
            return;
        }
        if (!canTrip) {
            skip(spec, "no way there (no walk, and the map cannot start a trip from here)");
            return;
        }
        bodyWanted = false;
        final BlockPos goal = spot;
        ClearExecutor.setExternalOwner(true);
        ClearExecutor.etherPathFast(goal, null);
        to(Phase.MOVE_TRIP);
    }

    private static void stepTrip(Minecraft client, LocalPlayer player) {
        if (ClearExecutor.isBusy() && !ClearExecutor.finishArrivalEarly(client)) {
            if (phaseTicks > MOVE_TIMEOUT_TICKS) {
                ClearExecutor.cancel();
                ClearExecutor.setExternalOwner(false);
                skip(SHOTS[shotIndex], "the trip took over " + MOVE_TIMEOUT_TICKS + " ticks");
            }
            return;
        }
        if (ClearExecutor.isBusy()) {
            return;
        }
        ClearExecutor.setExternalOwner(false);
        if (ClearExecutor.lastPathFailed()) {
            skip(SHOTS[shotIndex], "the etherwarp trip failed");
            return;
        }
        BatLog.LOGGER.info("[BatTest] shot {}/{}: the trip ended at {} after {} ticks", shotIndex + 1, SHOTS.length,
                BatLog.fmt(player.position()), phaseTicks);
        to(Phase.AIM);
    }

    private static void stepWalk(Minecraft client, LocalPlayer player) {
        if (!ShortWalk.isActive()) {
            to(Phase.AIM);
            return;
        }
        if (phaseTicks > MOVE_TIMEOUT_TICKS) {
            ShortWalk.stop("Bat Test: over " + MOVE_TIMEOUT_TICKS + " ticks");
            bodyWanted = false;
            skip(SHOTS[shotIndex], "the walk took over " + MOVE_TIMEOUT_TICKS + " ticks");
            return;
        }
        bodyWanted = true;
        float pitch = Mth.clamp(player.getXRot(), -90f, 90f);
        ShortWalk.Status status = ShortWalk.tick(client.level, player, yaw -> BODY.turn(player, yaw, pitch));
        if (status == ShortWalk.Status.WALKING) {
            return;
        }
        ShortWalk.stop(status.name().toLowerCase(Locale.ROOT));
        BatLog.LOGGER.info("[BatTest] shot {}/{}: the walk ended {} at {} after {} ticks", shotIndex + 1, SHOTS.length,
                status, BatLog.fmt(player.position()), phaseTicks);
        if (status != ShortWalk.Status.ARRIVED) {
            skip(SHOTS[shotIndex], "the walk ended " + status);
            return;
        }
        to(Phase.AIM);
    }

    // ---- the shot ----

    /** The sceptre in hand, on the ground, the body turned to this shot's look; the use goes on a later tick. */
    private static void aim(Minecraft client, LocalPlayer player) {
        Spec spec = SHOTS[shotIndex];
        Entity target = currentTarget(client);
        if (target == null) {
            BatLog.LOGGER.info("[BatTest] shot {}/{}: {} is gone - picking another mob", shotIndex + 1, SHOTS.length,
                    targetName);
            targetId = -1;
            to(Phase.PICK);
            return;
        }
        if (phaseTicks > AIM_TIMEOUT_TICKS) {
            skip(spec, "could not aim within " + AIM_TIMEOUT_TICKS + " ticks (not on the ground, or the gate never opened)");
            return;
        }
        int slot = sceptreSlot(player);
        if (slot < 0) {
            stop("no Spirit Sceptre in the hotbar");
            return;
        }
        if (!player.onGround()) {
            return;   // settles in a tick or two after a landing
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            com.killer560.hub.util.HotbarSelect.select(client, player, slot);
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 centre = target.getBoundingBox().getCenter();
        TeleportUtils.Rotation direct = TeleportUtils.getDirection(eye, centre);
        float yaw = player.getYRot() + Mth.wrapDegrees(direct.yaw() + spec.yawOff() - player.getYRot());
        float pitch = Mth.clamp(direct.pitch() + spec.pitchOff(), -90f, 90f);
        bodyWanted = true;
        // Turned on an earlier tick (so a movement packet has reported it), still turned there, and the mob has hardly
        // moved since: that look is the shot's. Otherwise turn now and send on a later tick.
        boolean turned = BODY.isHeld() && Math.abs(player.getYRot() - aimYaw) <= 0.01f
                && Math.abs(player.getXRot() - aimPitch) <= 0.01f
                && Math.abs(Mth.wrapDegrees(yaw - aimYaw)) < 2.0f && Math.abs(pitch - aimPitch) < 2.0f;
        if (!turned) {
            aimYaw = yaw;
            aimPitch = pitch;
            BODY.turn(player, yaw, pitch);
            return;
        }
        if (com.killer560.hub.util.RightClick.mustTurnFirst(player, aimYaw, aimPitch, "[BatTest] shot")) {
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.ROUTE)) {
            return;
        }
        shotDistance = eye.distanceTo(centre);
        flightTimeout = flightTimeout(shotDistance);
        usesBefore = com.killer560.hub.util.RightClick.itemUsesSent();
        BatLog.expectTarget(targetId);
        BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] shot %d/%d (%s): use at %s, look %.2f/%.2f (%+.1f/%+.1f off"
                        + " %s), %.2f blocks, waiting on its bat up to %d ticks", shotIndex + 1, SHOTS.length, spec.label(),
                BatLog.fmt(player.position()), aimYaw, aimPitch, spec.yawOff(), spec.pitchOff(), targetName, shotDistance,
                flightTimeout));
        holdUse(client);
        to(Phase.FIRE);
    }

    /** The tick after the press: the use left (one bat) - let go at once; else give the shot up after a few ticks. */
    private static void fire(Minecraft client, LocalPlayer player) {
        bodyWanted = true;
        if (com.killer560.hub.util.RightClick.itemUsesSent() != usesBefore) {
            releaseUse(client);
            batShotNo = BatLog.shots();
            to(Phase.FLY);
            return;
        }
        if (phaseTicks >= USE_TIMEOUT_TICKS) {
            releaseUse(client);
            skip(SHOTS[shotIndex], "the use never left the client in " + USE_TIMEOUT_TICKS + " ticks");
        }
    }

    /** Waits on the bat's own end (its result line), bounded by the shot's own timeout. The body stays on the aim. */
    private static void fly(Minecraft client, LocalPlayer player) {
        bodyWanted = true;
        BatLog.Shot s = BatLog.shot(batShotNo);
        if (s != null && s.done()) {
            OUTCOMES.add(new Outcome(SHOTS[shotIndex], null, s.no, shotDistance));
            BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] shot %d/%d (%s) done: bat #%d %s, %d ticks, %s",
                    shotIndex + 1, SHOTS.length, SHOTS[shotIndex].label(), s.no, s.how(), s.flightTicks(), s.curve));
            nextShot();
            return;
        }
        if (phaseTicks > flightTimeout) {
            skip(SHOTS[shotIndex], "its bat had no end within " + flightTimeout + " ticks");
        }
    }

    /** The bat's own flight at the planning speed, both ways over, plus its appearance and the health read after. */
    static int flightTimeout(double blocks) {
        return WeaponReach.BAT_LAUNCH_TICKS + (int) Math.ceil(2.0 * (blocks + WeaponReach.SCEPTRE_RADIUS)
                / WeaponReach.BAT_BLOCKS_PER_TICK) + BatLog.APPEAR_TICKS + BatLog.AFTER_TICKS + 2;
    }

    private static void skip(Spec spec, String why) {
        OUTCOMES.add(new Outcome(spec, why, -1, -1));
        BatLog.LOGGER.info("[BatTest] shot {}/{} ({}) skipped: {}", shotIndex + 1, SHOTS.length, spec.label(), why);
        ModChat.log(CHAT, ModChat.bad("Shot skipped: "), ModChat.dim(spec.label() + " - " + why));
        nextShot();
    }

    private static void nextShot() {
        releaseUse(Minecraft.getInstance());
        shotIndex++;
        batShotNo = -1;
        to(Phase.PICK);
    }

    // ---- the end ----

    private static void finish() {
        summary("all " + SHOTS.length + " shots done");
        ModChat.send(CHAT, ModChat.good("Done"), ModChat.dim(" - the summary is in the log ([BatTest] summary)"));
        end();
    }

    private static void stop(String why) {
        if (!running) {
            return;
        }
        summary("stopped early: " + why);
        ModChat.send(CHAT, ModChat.text("Stopped: "), ModChat.value(why));
        end();
    }

    private static void end() {
        Minecraft client = Minecraft.getInstance();
        running = false;
        bodyWanted = false;
        releaseUse(client);
        if (ShortWalk.isActive()) {
            ShortWalk.stop("Bat Test ended");
        }
        if (phase == Phase.MOVE_TRIP && ClearExecutor.isBusy()) {
            ClearExecutor.cancel();
        }
        if (phase == Phase.MOVE_TRIP) {
            ClearExecutor.setExternalOwner(false);
        }
        if (client.player != null && slotBefore >= 0 && slotBefore <= 8) {
            com.killer560.hub.util.HotbarSelect.select(client, client.player, slotBefore);
        }
        slotBefore = -1;
        phase = Phase.PICK;
    }

    /** Per shot: distance, look, flight time and speed, damage, curve; then the speed over all and the curve verdict. */
    private static void summary(String how) {
        BatLog.LOGGER.info("[BatTest] summary: {} after {} ticks - {} of {} shot(s) reached", how, runTicks,
                OUTCOMES.size(), SHOTS.length);
        double blocks = 0;
        int ticks = 0;
        int straight = 0;
        int towardTarget = 0;
        int curved = 0;
        int bats = 0;
        double seenSpeedSum = 0;
        int seenSpeeds = 0;
        for (int i = 0; i < OUTCOMES.size(); i++) {
            Outcome o = OUTCOMES.get(i);
            if (o.skipped() != null) {
                BatLog.LOGGER.info("[BatTest] summary shot {} ({}): skipped - {}", i + 1, o.spec().label(), o.skipped());
                continue;
            }
            BatLog.Shot s = BatLog.shot(o.batNo());
            if (s == null) {
                BatLog.LOGGER.info("[BatTest] summary shot {} ({}): bat #{} no longer logged", i + 1, o.spec().label(),
                        o.batNo());
                continue;
            }
            if (s.batSeen() && s.flightTicks() > 0) {
                bats++;
                if (s.trackedSpeed() > 0) {
                    seenSpeedSum += s.trackedSpeed();
                    seenSpeeds++;
                }
                blocks += s.reach();
                ticks += s.flightTicks();
                if (s.curve.startsWith("straight along the look")) {
                    straight++;
                }
                if (s.curve.startsWith("curved")) {
                    curved++;
                }
                if (s.curve.contains("toward the target")) {
                    towardTarget++;
                }
            }
            BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] summary shot %d (%s): bat #%d, %.2f blocks to %s, look off"
                            + " %.1f deg (yaw %+.1f, pitch %+.1f) | %s | flight %d ticks over %.2f blocks = %s b/t (seen +%d,"
                            + " %s b/t while seen) | hp %s -> %s%s | curve: %s", i + 1, o.spec().label(), s.no, s.targetDist,
                    s.targetName, s.lookOffDeg, s.yawOffDeg, s.pitchOffDeg, s.how(), s.flightTicks(), s.reach(),
                    s.flightTicks() > 0 && s.batSeen() ? String.format(Locale.US, "%.2f", s.reach() / s.flightTicks()) : "-",
                    s.seenTick < 0 ? -1 : s.seenTick - s.useTick,
                    s.trackedSpeed() < 0 ? "-" : String.format(Locale.US, "%.2f", s.trackedSpeed()),
                    Float.isNaN(s.healthBefore) ? "-" : String.format(Locale.US, "%.1f", s.healthBefore),
                    Float.isNaN(s.healthAfter) ? "?" : String.format(Locale.US, "%.1f", s.healthAfter),
                    s.targetGone ? " (dead or gone)" : "", s.curve));
        }
        BatLog.LOGGER.info(String.format(Locale.US, "[BatTest] summary: %d bat(s) seen; %s; %d flew straight along the look"
                        + " (the crosshair the server heard), %d curved, %d went toward the target - verdict: %s; planning"
                        + " speed is %.2f b/t (WeaponReach.BAT_BLOCKS_PER_TICK)", bats,
                bats == 0 ? "no flight measured" : String.format(Locale.US, "average %.2f blocks/tick (%.2f blocks in %d"
                        + " ticks, use to hit; %s while seen)", blocks / ticks, blocks, ticks, seenSpeeds == 0 ? "no speed"
                        : String.format(Locale.US, "%.2f b/t", seenSpeedSum / seenSpeeds)), straight, curved, towardTarget,
                bats == 0 ? "not measured" : straight == bats
                        ? "every bat flew straight along the crosshair it was fired with (held still), none toward the mob"
                        : towardTarget > 0 ? towardTarget + " bat(s) left the crosshair's line toward the mob"
                        : "mixed - see each shot",
                WeaponReach.BAT_BLOCKS_PER_TICK));
    }

    // =========================================================================================== helpers

    private static Entity currentTarget(Minecraft client) {
        if (targetId < 0 || client.level == null) {
            return null;
        }
        Entity e = client.level.getEntity(targetId);
        return e == null || e.isRemoved() || !e.isAlive() ? null : e;
    }

    /** The nearest mob he can see: Auto Clear's starred mobs first, any hostile mob when none is in sight. */
    private static Entity pickTarget(Minecraft client, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Entity best = nearestVisible(client, player, eye, MobEspFeature.starredMobs(client));
        if (best == null) {
            List<Entity> hostile = client.level.getEntities(player, new AABB(eye, eye).inflate(MOB_RANGE),
                    e -> e instanceof net.minecraft.world.entity.Mob && e instanceof LivingEntity && !(e instanceof ArmorStand) && !(e instanceof Bat)
                            && e.isAlive());
            best = nearestVisible(client, player, eye, hostile);
        }
        return best;
    }

    private static Entity nearestVisible(Minecraft client, LocalPlayer player, Vec3 eye, List<Entity> mobs) {
        Entity best = null;
        double bestD = MOB_RANGE;
        for (Entity e : mobs) {
            double d = eye.distanceTo(e.getBoundingBox().getCenter());
            if (d < bestD && clearLine(client, player, eye, e)) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** No block (collision shape) between {@code eye} and the mob's centre. */
    static boolean clearLine(Minecraft client, LocalPlayer player, Vec3 eye, Entity mob) {
        Vec3 c = mob.getBoundingBox().getCenter();
        HitResult hit = client.level.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(c) < 0.3;
    }

    /**
     * A block to stand ON (one an etherwarp lands on: solid, two air above) whose eye is about {@code blocks} from the
     * mob's centre with a clear line to it: 36 directions round the mob, the distance give or take two, six blocks up or
     * down; the one nearest him wins, a distance closer to the shot's breaking ties.
     */
    private static BlockPos standSpot(Minecraft client, LocalPlayer player, Entity mob, double blocks) {
        Vec3 c = mob.getBoundingBox().getCenter();
        int baseY = mob.blockPosition().getY();
        double eyeUp = player.getEyeHeight();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        java.util.Set<Long> tried = new java.util.HashSet<>();
        for (int a = 0; a < 360; a += 10) {
            double rad = Math.toRadians(a);
            for (double r = blocks - 2; r <= blocks + 2; r += 1.0) {
                int x = Mth.floor(c.x + Math.cos(rad) * r);
                int z = Mth.floor(c.z + Math.sin(rad) * r);
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos pos = new BlockPos(x, baseY - 1 + dy, z);
                    if (!tried.add(pos.asLong()) || !TeleportUtils.etherwarpable(pos)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(x + 0.5, pos.getY() + 1.0 + eyeUp, z + 0.5);
                    double d = eye.distanceTo(c);
                    if (Math.abs(d - blocks) > 2.5 || !clearLine(client, player, eye, mob)) {
                        continue;
                    }
                    double score = player.position().distanceTo(new Vec3(x + 0.5, pos.getY() + 1.0, z + 0.5))
                            + Math.abs(d - blocks) * 2.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static int sceptreSlot(LocalPlayer player) {
        for (int slot = 0; slot <= 8; slot++) {
            if (com.killer560.hub.autoroutes.ItemIdentity.matches(player.getInventory().getItem(slot),
                    AutoClearConfig.Weapon.SPIRIT_SCEPTRE.itemId())) {
                return slot;
            }
        }
        return -1;
    }

    private static KeyMapping[] keys(Minecraft client) {
        var o = client.options;
        return new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump};
    }

    private static void holdUse(Minecraft client) {
        client.options.keyUse.setDown(true);
        useHeld = true;
    }

    private static void releaseUse(Minecraft client) {
        if (!useHeld) {
            return;
        }
        useHeld = false;
        if (client != null) {
            client.options.keyUse.setDown(false);
        }
    }

    /**
     * While the use key is held with the body turned, the block vanilla's held use acts on is the one along the BODY's
     * look - Auto Clear's {@code heldUsePick} rule, from {@code autoroutes/mixin/HeldUsePickMixin}.
     */
    public static HitResult heldUsePick(Minecraft client) {
        if (!useHeld || !running || client.player == null || client.level == null || !BODY.isHeld()) {
            return null;
        }
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition();
        Vec3 look = TeleportUtils.getLook(player.getYRot(), player.getXRot()).scale(player.blockInteractionRange());
        return client.level.clip(new ClipContext(eye, eye.add(look), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
    }

    // ---- corrections ----

    /** From {@code LiveMapPacketListenerMixin}, client thread, before vanilla applies the position. */
    public static void onServerPositionPacket() {
        Minecraft client = Minecraft.getInstance();
        posBeforePacket = client.player == null ? null : client.player.position();
        positionPackets++;
    }

    /** A server correction since the last tick, said the way every correction is; the reason the test ends, or null. */
    private static String correction(LocalPlayer player) {
        int executor = ClearExecutor.serverCorrections();
        if (executor != executorCorrectionsSeen) {
            executorCorrectionsSeen = executor;
            positionPacketsSeen = positionPackets;
            // The map's trip has said it in chat with the alarm already.
            return "a server correction during the trip";
        }
        int packets = positionPackets;
        if (packets == positionPacketsSeen) {
            return null;
        }
        positionPacketsSeen = packets;
        if (ClearExecutor.isBusy() || ClearExecutor.lastFastArrivalTick() == ClearExecutor.clientTick()) {
            return null;   // the trip's own landings (it judges them itself, above)
        }
        Vec3 before = posBeforePacket;
        double moved = before == null ? 0.0 : player.position().distanceTo(before);
        if (moved < 0.3) {
            return null;   // a rotation-only answer
        }
        ServerCorrections.report(CHAT, "- the bat test ends", moved);
        return String.format(Locale.US, "a server correction (moved %.2f blocks)", moved);
    }
}
