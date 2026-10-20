package com.killer560.hub.autoclear;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.mobesp.MobEspFeature;
import com.killer560.hub.pathfinding.ShortWalk;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModSounds;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Auto Clear (cheat build, off by default) - killer560, 2026-10-06: kill the STARRED mobs of the dungeon's mob rooms
 * (the ones a room clear counts; a starred miniboss included) with the Hyperion or the Spirit Sceptre, never melee.
 *
 * <h2>How it moves</h2>
 * Per hop it compares two estimates and takes the faster:
 * <ul>
 *   <li><b>Hyperion hops</b> - Wither Impact aimed toward the mob, each landing predicted by the sim's measured dash
 *       model ({@link WeaponReach#hyperionHop}); a hop that lands within reach both moves and kills. Cost: about one
 *       held-use cycle per hop (vanilla repeats a held use every 4 ticks).</li>
 *   <li><b>An etherwarp chain</b> - the Interactive Map's planner and runner ({@link ClearExecutor#etherPath}) onto a
 *       block from which the weapon reaches the mob ({@link WeaponReach#standSpot}). Cost: the runner's fixed
 *       overhead (search, sneak, the last landing's answer - {@link #ETHER_FIXED_TICKS}) plus about two ticks a warp.</li>
 *   <li><b>A Spirit Sceptre bat from where he stands</b> (since 2026-10-19) - no move at all, but the bat has to fly:
 *       launch plus flight at {@link WeaponReach#BAT_BLOCKS_PER_TICK}. With the Hyperion as the weapon it is taken when a
 *       sceptre is in the hotbar and it beats both of the above; with the sceptre as the weapon a far bat is weighed
 *       against etherwarping onto the mob's block and firing down.</li>
 *   <li><b>A walk</b> (since 2026-10-19, Walk When Faster) - sprinting, stepping and jumping over the loaded blocks
 *       ({@link ShortWalk}: the pathfinding package's planner, terrain and key model), to the same block an etherwarp
 *       would go to, ending the tick the weapon reaches the mob. Taken only when its estimate is strictly under the
 *       etherwarp chain's; a walk that gets stuck etherwarps instead. Roam trips choose the same way.</li>
 * </ul>
 * "sometimes etherwarping isn't worth it and instead it should just hype towards the mobs" - so a near mob with an open
 * line gets hops, a far or walled-off one an etherwarp chain.
 *
 * <h2>How it attacks</h2>
 * The weapon's real area, not a line of sight: Wither Impact's 6-block blast where he lands, the Guided Bat's 6-block
 * blast where it hits a block or a mob ({@link WeaponReach} has the wiki citations). A mob inside a wall dies to a
 * blast that goes off within reach of it. The use key is HELD, as the Auto Routes crypt node does (vanilla then uses
 * the item every 4 ticks) - never spam-clicked. The body turns with the camera held ({@link BodyAim}), a tick before
 * the use goes out, so the use carries a rotation a movement packet has already reported.
 *
 * <h2>When it stops</h2>
 * As Auto Secret: his own input (a movement, jump or attack key pressed after it started), the boss room, nothing left,
 * death, leaving the dungeon. NEVER a server correction (killer560, 2026-10-06: "Nothing in this mod should stop from
 * server corrections ever - just have it send a chat message and make noises"): a correction gets a chat line and
 * {@link ModSounds#playCorrectionAlarm}, and the plan starts again from where the server put him.
 */
public final class AutoClearFeature {

    static final String CHAT = "Auto Clear";
    private static final Logger LOGGER = ModLog.get("killer560smod-autoclear");

    /**
     * The Interactive Map runner's fixed cost of a trip, in ticks: the background search hands back a tick or two later,
     * the first warp waits for the sneak to reach the server, and the trip ends on the server's answer to the last
     * landing ({@code ClearExecutor.etherPathFast}). It was 14 while every trip also waited the ~9-tick arrival settle;
     * the 2026-10-19 log had one-to-three-warp trips at 14-16 ticks with it, so about 5 without.
     */
    static final int ETHER_FIXED_TICKS = 5;
    /** Ticks per warp of a chain once it is going (ClearExecutor issues one hop a tick and waits on the server). */
    static final int ETHER_TICKS_PER_WARP = 2;
    /** A held use repeats every 4 ticks (vanilla {@code rightClickDelay}, javap 26.1.2 and 26.2 - LESSONS). */
    static final int USE_REPEAT_TICKS = 4;
    /** Hops the planner will chain before an etherwarp is the better bet anyway. */
    static final int MAX_HOPS = 4;
    /** A hop that has not landed in this many ticks is given up on (for this mob, hops are off). */
    static final int HOP_TIMEOUT_TICKS = 12;
    /** In the target room with no starred mob visible and no clear on the map for this long: give the room up. */
    static final int NO_MOB_TICKS = 80;
    /** Failed trips (the runner gave up) on one room before it is given up. */
    static final int MAX_PATH_FAILURES = 4;

    private enum Phase {
        IDLE, RELEASE, TRAVEL, HOP, ATTACK, WALK
    }

    private static boolean running = false;
    /** Non-null while {@link #clearRoom} owns the run. */
    private static String singleRoom = null;
    private static Runnable onDone = null;
    private static Consumer<String> onGiveUp = null;

    private static Phase phase = Phase.IDLE;
    private static String roomName = null;
    private static int targetId = -1;
    private static String targetName = "";
    private static String action = "";
    private static int mobTicks = 0;
    private static int noMobTicks = 0;
    private static int pathFailures = 0;
    private static int hopTicks = 0;
    /** util/ServerLag readings on the first tick of a hop, a mob and a mob-less wait: a server stall since then extends
     *  that timeout by its length, so a lagging server never reads as a hop that missed or a mob that will not die
     *  (killer560, 2026-10-15). With no lag every timeout is the tick count it always was. */
    private static long hopLag = 0L;
    private static long mobLag = 0L;
    private static long noMobLag = 0L;
    private static Vec3 hopStart = null;
    private static Vec3 hopLook = null;
    private static boolean hopLanded = false;
    private static int hopsOffFor = -1;
    private static boolean travelStarted = false;
    private static int slotBefore = -1;
    private static final Set<String> skippedRooms = new HashSet<>();
    private static final Set<Integer> skippedMobs = new HashSet<>();
    private static int roomsCleared = 0;
    /** When this run began, for the time-left estimate's seconds per room (see {@link #publishPlan}). */
    private static long runStartMs = 0L;
    private static int mobsKilled = 0;
    /** Starred kills in the current room - with no dungeon map to read the clear from, these are the evidence. */
    private static int killsInRoom = 0;

    private static boolean useHeld = false;
    /** The last decision turned or is holding the body; false gives it back to his view (BodyAim.tick). */
    private static boolean bodyWanted = false;
    private static final BodyAim BODY = new BodyAim(() -> { });

    // ---- input ----
    private static final boolean[] wasDown = new boolean[6];
    private static boolean toggleKeyWasDown = false;

    // ---- corrections ----
    private static volatile int positionPackets = 0;
    private static volatile Vec3 posBeforePacket = null;
    private static int positionPacketsSeen = 0;
    /** {@link ClearExecutor#serverCorrections()} when last looked: a trip's corrections count toward this run's too. */
    private static int executorCorrectionsSeen = 0;
    private static int corrections = 0;

    // ---- cached plans (rebuilt when he, the target or the target's block moves) ----
    private static long planKey = Long.MIN_VALUE;
    private static WeaponReach.Aim cachedAttack = null;
    private static int cachedHops = -1;

    // ---- HUD / last reason ----
    private static String lastReason = null;
    private static long lastReasonMs = 0L;
    /** The run {@link #lastReason} ended was one he started (not a room handed over by Auto Secret / Autopilot). */
    private static boolean lastReasonStandalone = false;
    private static Object lastLevel = null;

    private AutoClearFeature() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        // START: the use key must be down before handleKeybinds, and the body turned before the movement packet.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoClearFeature.tick", AutoClearFeature::tick));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoClearSettings.capture", AutoClearSettings::pollCapture));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("autoclear")
                        .requires(src -> AutoClearConfig.getInstance().isEnabledRaw())
                        .executes(ctx -> {
                            toggle();
                            return 1;
                        })));
        // Auto Clear's rows in the shared Auto Clear tab (Auto Secret's tab; ours go below its section).
        com.killer560.hub.gui.tab.AutoClearTab.addSection((widgets, x, y, width, rebuild) -> {
            int[] at = {y};
            AutoClearSettings.addSettings(widgets, x, at, width, rebuild);
            return at[0];
        });
    }

    // =========================================================================================== public API (Auto Secret)

    /** Whether Auto Clear can take a room right now: cheat build, switched on, in a dungeon's clear (not the boss). */
    public static boolean isAvailable() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && AutoClearConfig.getInstance().isEnabled()
                && DungeonState.isInDungeon() && !LiveMapFeature.isInBoss();
    }

    /**
     * Clears one room's starred mobs, then calls {@code onDone}; calls {@code onGiveUp(reason)} instead when it cannot
     * (room not on the map, no weapon, no path, mobs out of reach, his input, the boss). Never both, and never after
     * {@link #cancel()} - the caller cancelled, so it already knows. A room already cleared calls {@code onDone} on the
     * next tick.
     */
    public static void clearRoom(String roomName, Runnable onDone, Consumer<String> onGiveUp) {
        if (!isAvailable()) {
            if (onGiveUp != null) {
                onGiveUp.accept("Auto Clear is not available (switched off, not in a dungeon, or in the boss)");
            }
            return;
        }
        if (running) {
            stopQuietly();
        }
        singleRoom = roomName;
        AutoClearFeature.onDone = onDone;
        AutoClearFeature.onGiveUp = onGiveUp;
        begin("room " + roomName);
    }

    public static boolean isBusy() {
        return running;
    }

    /** Whether Auto Clear would clear {@code room} at all: a NORMAL, RARE or CHAMPION room (Dungeon Autopilot asks). */
    public static boolean isMobRoom(DungeonLayout layout, int room) {
        return AutoClearTargets.isMobRoom(layout, room);
    }

    /** The rooms of the Blood Rush Split path, Entrance first, or null while the Blood door is not on the map. */
    public static List<Integer> bloodRushRooms(DungeonLayout layout) {
        return AutoClearTargets.bloodRushRooms(layout);
    }

    /** Stops whatever is running, with no callback (the caller cancelled). */
    public static void cancel() {
        if (running) {
            stopQuietly();
            say(ModChat.dim("Canceled"));
        }
    }

    // =========================================================================================== toggle

    public static void toggle() {
        if (running) {
            stop("stopped");
        } else {
            start();
        }
    }

    public static void start() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        if (!AutoClearConfig.getInstance().isEnabled()) {
            say(ModChat.bad("Switch Auto Clear on first (Auto Clear tab)"));
            return;
        }
        if (!DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            say(ModChat.bad("Only in a dungeon's clear (not outside one, not in the boss)"));
            return;
        }
        singleRoom = null;
        onDone = null;
        onGiveUp = null;
        begin(AutoClearConfig.getInstance().getMode().label());
    }

    private static void begin(String what) {
        Minecraft client = Minecraft.getInstance();
        running = true;
        phase = Phase.IDLE;
        roomName = null;
        targetId = -1;
        targetName = "";
        action = "starting";
        mobTicks = 0;
        noMobTicks = 0;
        pathFailures = 0;
        hopsOffFor = -1;
        walkOffFor = -1;
        walkDecisionKey = null;
        walkDecisionPlan = null;
        travelStarted = false;
        skippedRooms.clear();
        skippedMobs.clear();
        doorTarget = -1;
        keyId = -1;
        keyTrip = false;
        keysTried.clear();
        resetRoam();
        midasFor = null;
        seenMobs.clear();
        edgeLogged.clear();
        doomed.clear();
        batLogs.clear();
        oneTapLogged.clear();
        oneTapFailed.clear();
        leadLogged.clear();
        doorClicks = 0;
        doorClickSeq = -1;
        roomsCleared = 0;
        runStartMs = System.currentTimeMillis();
        mobsKilled = 0;
        corrections = 0;
        coverKey = null;
        coverIds = null;
        coverSpots = List.of();
        coverMobs = List.of();
        planKey = Long.MIN_VALUE;
        positionPacketsSeen = positionPackets;
        executorCorrectionsSeen = ClearExecutor.serverCorrections();
        slotBefore = client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        // A key already held when it starts is the walk that got him here, not a takeover (Auto Routes' rule).
        KeyMapping[] keys = watchedKeys(client);
        for (int i = 0; i < keys.length; i++) {
            wasDown[i] = keys[i].isDown();
        }
        AutoClearConfig cfg = AutoClearConfig.getInstance();
        say(ModChat.good("Started"), ModChat.dim(" - " + what + ", "), ModChat.value(cfg.getWeapon().label()));
        LOGGER.info("[AutoClear] started: {} weapon={} hops={}", what, cfg.getWeapon(), cfg.isHyperionHops());
    }

    /** Ends the run and tells the caller why ({@code onGiveUp}), unless it ended because the work is done. */
    private static void stop(String reason) {
        if (!running) {
            return;
        }
        Consumer<String> giveUp = onGiveUp;
        lastReasonStandalone = singleRoom == null;
        stopQuietly();
        lastReason = reason;
        lastReasonMs = System.currentTimeMillis();
        say(ModChat.text("Stopped: "), ModChat.value(reason));
        LOGGER.info("[AutoClear] stopped: {} (rooms cleared {}, starred kills {}, corrections {})", reason, roomsCleared,
                mobsKilled, corrections);
        if (giveUp != null) {
            giveUp.accept(reason);
        }
    }

    private static void finish(String why) {
        Runnable done = onDone;
        lastReasonStandalone = singleRoom == null;
        stopQuietly();
        lastReason = why;
        lastReasonMs = System.currentTimeMillis();
        say(ModChat.good("Done: "), ModChat.text(why));
        LOGGER.info("[AutoClear] done: {} (rooms cleared {}, starred kills {}, corrections {})", why, roomsCleared,
                mobsKilled, corrections);
        if (done != null) {
            done.run();
        }
    }

    private static void stopQuietly() {
        Minecraft client = Minecraft.getInstance();
        running = false;
        bodyWanted = false;
        releaseUse(client);
        endWalk("Auto Clear stopped");
        if (phase == Phase.TRAVEL && ClearExecutor.isBusy()) {
            ClearExecutor.cancel();
        }
        ClearExecutor.setExternalOwner(false);
        if (client.player != null && slotBefore >= 0 && slotBefore <= 8) {
            select(client, client.player, slotBefore);
        }
        slotBefore = -1;
        phase = Phase.IDLE;
        singleRoom = null;
        onDone = null;
        onGiveUp = null;
        com.killer560.hub.autoroutes.RunPlan.clear(com.killer560.hub.autoroutes.RunPlan.Source.AUTO_CLEAR);
    }

    // =========================================================================================== ticking

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (running) {
                stop("the world changed");
            }
        }
        LocalPlayer player = client.player;
        pollToggleKey(client);
        if (player == null) {
            return;
        }
        // Held while the last decision aimed (a turn waiting for its use, a held use, a hop in flight).
        BODY.tick(player, running && bodyWanted);
        if (!running) {
            return;
        }
        if (!AutoClearConfig.getInstance().isEnabled()) {
            stop("Auto Clear was switched off");
            return;
        }
        if (!DungeonState.isInDungeon()) {
            stop("left the dungeon");
            return;
        }
        if (LiveMapFeature.isInBoss()) {
            stop("the boss was entered");
            return;
        }
        if (player.isDeadOrDying()) {
            stop("you died");
            return;
        }
        String pressed = userInput(client);
        if (pressed != null) {
            stop("you pressed " + pressed);
            return;
        }
        try {
            // Every running tick, waits and trips included, so a mob that dies meanwhile is seen dying, not lost.
            observeMobs(client, player);
        } catch (RuntimeException e) {
            LOGGER.warn("[AutoClear] watching the room's mobs failed", e);
        }
        if (McCompat.screen(client) != null || com.killer560.hub.autoroutes.RouteExecutor.isRunning()) {
            // A screen (chat, a chest) or an Auto Routes route: wait, holding nothing.
            releaseUse(client);
            abandonWalk(McCompat.screen(client) != null ? "a screen opened" : "Auto Routes started");
            action = McCompat.screen(client) != null ? "waiting (a screen is open)" : "waiting (Auto Routes is running)";
            if (com.killer560.hub.autoroutes.RouteExecutor.isRunning()) {
                // The route's own warps are its business (it judges their landings itself), not corrections of ours.
                positionPacketsSeen = positionPackets;
            }
            return;
        }
        if (com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
            // An auto puzzle is moving him (killer560, 2026-10-14): wait, holding nothing, and start no trip until it
            // is done. Its warps and teleports are its own, not corrections of ours.
            releaseUse(client);
            abandonWalk(com.killer560.hub.autopuzzles.AutoPuzzleActivity.what() + " is working");
            action = "waiting (" + com.killer560.hub.autopuzzles.AutoPuzzleActivity.what() + " is working)";
            positionPacketsSeen = positionPackets;
            return;
        }
        checkPositionPackets(client, player);
        if (!running) {
            return;
        }
        noteBatSent(player);
        try {
            step(client, player);
        } catch (RuntimeException e) {
            LOGGER.warn("[AutoClear] tick failed", e);
            stop("internal error: " + e);
        }
    }

    private static void step(Minecraft client, LocalPlayer player) {
        if (phase == Phase.WALK && stepWalk(client, player)) {
            return;
        }
        if (phase == Phase.TRAVEL) {
            // Auto Clear's own trips (etherPathFast) end on the tick the server confirms the last landing, not ~9 ticks
            // later: Auto Routes asks this too, and whichever asks first ends it.
            if (ClearExecutor.isBusy() && !ClearExecutor.finishArrivalEarly(client)) {
                return;
            }
            if (ClearExecutor.isBusy()) {
                return;
            }
            phase = Phase.IDLE;
            planKey = Long.MIN_VALUE;
            if (!tripEnded(player, ClearExecutor.lastPathFailed())) {
                return;
            }
        }
        if (phase == Phase.RELEASE) {
            if (BODY.isHeld()) {
                return;   // the body goes back to his view next tick (BodyAim.tick), then the trip starts
            }
            phase = Phase.IDLE;
        }
        if (phase == Phase.HOP) {
            if (++hopTicks == 1) {
                hopLag = com.killer560.hub.util.ServerLag.mark();
            }
            if (hopLanded) {
                releaseUse(client);
                phase = Phase.IDLE;
                planKey = Long.MIN_VALUE;
            } else if (hopTicks > HOP_TIMEOUT_TICKS + com.killer560.hub.util.ServerLag.ticksSince(hopLag)) {
                releaseUse(client);
                hopsOffFor = targetId;
                phase = Phase.IDLE;
                planKey = Long.MIN_VALUE;
                LOGGER.info("[AutoClear] a Hyperion hop toward {} never landed - etherwarping to it instead", targetName);
            } else {
                // Keep holding along the same look until the landing is seen.
                holdUse(client);
                return;
            }
        }
        decide(client, player);
    }

    /**
     * The end of a trip (an Interactive Map trip, or a walk standing in for one): a key or roam trip's bookkeeping, a
     * failed trip counted against the room, a door trip noted as done.
     * @return false when the room was given up on (nothing more to do this tick)
     */
    private static boolean tripEnded(LocalPlayer player, boolean failed) {
        if (keyTrip) {
            // A trip to a dropped key is the key's business: its failure is not the room's (pathFailures).
            keyTrip = false;
            if (travelStarted && failed && keyId >= 0) {
                LOGGER.info("[AutoClear] key: the trip to the Wither Key #{} failed - leaving it", keyId);
                keysTried.add(keyId);
                keyId = -1;
            }
            travelStarted = false;
        }
        if (roamTrip) {
            // A roam trip's failure costs its spot (already counted), not one of the room's tries.
            roamTrip = false;
            if (travelStarted && failed) {
                LOGGER.info("[AutoClear] roam: the trip to spot {} in {} failed - looking from here", roamSpotsTried,
                        roomName);
            } else {
                LOGGER.info("[AutoClear] roam: at spot {} in {} ({}) - looking for starred mobs for {} ticks",
                        roamSpotsTried, roomName, fmt(player.position()), ROAM_LOOK_TICKS);
            }
            noMobTicks = 0;
            travelStarted = false;
        }
        if (travelStarted && failed) {
            pathFailures++;
            LOGGER.info("[AutoClear] trip {} failed ({} of {})", action, pathFailures, MAX_PATH_FAILURES);
            if (pathFailures >= MAX_PATH_FAILURES) {
                giveUpRoom("no etherwarp path (" + pathFailures + " tries)");
                travelStarted = false;
                return false;
            }
        }
        if (travelStarted && doorTarget >= 0 && !failed) {
            doorTripDone = doorTarget;   // the map took him as close to the door as it goes
        }
        travelStarted = false;
        return true;
    }

    private static void decide(Minecraft client, LocalPlayer player) {
        bodyWanted = false;
        DungeonLayout layout = DungeonLayout.capture();
        AutoClearConfig cfg = AutoClearConfig.getInstance();

        // ---- a dropped Wither Key first: the door it opens is the way on (before "room cleared" - the key often
        // drops from the room's last mob, and a single-room clear would hand back with it lying there) ----
        if (cfg.isPickUpKeys() && pickUpKey(client, player, layout)) {
            return;
        }

        // ---- the room ----
        int room = AutoClearTargets.roomByName(layout, roomName);
        if (room >= 0 && AutoClearTargets.isCleared(layout, room)) {
            roomsCleared++;
            LOGGER.info("[AutoClear] room cleared: {}{}", roomName, AutoClearTargets.isClearedOnMap(layout, room) ? ""
                    : " (its secret chest was grabbed - killer560's chest rule: " + ChestClearRule.describe() + ")");
            if (singleRoom != null) {
                finish(roomName + " cleared");
                return;
            }
            room = -1;
        }
        if (room < 0) {
            room = pickRoom(layout, cfg);
            if (!running || room < 0) {
                return;
            }
            roomName = layout.name(room);
            noMobTicks = 0;
            killsInRoom = 0;
            pathFailures = 0;
            targetId = -1;
            resetRoam();
            ModChat.log(CHAT, ModChat.text("Room: "), ModChat.value(roomName));
        }

        // ---- the mob ----
        if (targetId >= 0) {
            Entity previous = client.level.getEntity(targetId);
            if (previous == null || !previous.isAlive()) {
                mobsKilled++;
                killsInRoom++;
                LOGGER.info("[AutoClear] {} in {} is dead ({} starred kill(s) this run)", targetName, roomName, mobsKilled);
                targetId = -1;
            }
        }
        if (oneTapStep(client, player)) {
            return;
        }
        List<Entity> mobs = starredIn(client, layout, room);
        if (mobs.isEmpty()) {
            targetId = -1;
            targetName = "";
            String waitingOn = doomedIn(client, layout, room);
            if (waitingOn != null) {
                // Every mob left here is one a bat is on its way to: its death (or the bat being late) is the signal.
                releaseUse(client);
                action = "waiting for the bat at " + waitingOn;
                noMobTicks = 0;
                return;
            }
            if (layout.currentRoom() == room) {
                releaseUse(client);
                if (midasAltar(client, player, layout, room)) {
                    // King Midas: its mob is not there until the altar is open, so the mob-less wait starts after it.
                    noMobTicks = 0;
                    return;
                }
                action = "looking for starred mobs";
                if (++noMobTicks == 1) {
                    noMobLag = com.killer560.hub.util.ServerLag.mark();
                }
                long lag = com.killer560.hub.util.ServerLag.ticksSince(noMobLag);
                boolean doneWithoutMap = killsInRoom > 0 && !LiveMapFeature.hasRoomStates();
                boolean roamedAny = roamSpotsTried > 0 || roamKnownTried > 0;
                List<Known> known = roamDone || roamKnownTried >= ROAM_KNOWN_MAX ? List.of()
                        : knownMobs(client, layout, room);
                // A mob known to be in the room (lost from view, or skipped from where he stood) is gone for at once;
                // with none known the room is looked at a while first, as before.
                int roamAfter = !known.isEmpty() ? ROAM_KNOWN_AFTER_TICKS : roamedAny ? ROAM_LOOK_TICKS : ROAM_AFTER_TICKS;
                // Not cleared and nothing in sight: walk the room before giving up on it (killer560, 2026-10-19).
                if (!roamDone && !doneWithoutMap && noMobTicks > roamAfter + lag
                        && roam(client, player, layout, room, known)) {
                    return;
                }
                if (noMobTicks > (roamedAny ? ROAM_LOOK_TICKS : NO_MOB_TICKS) + lag) {
                    if (doneWithoutMap) {
                        // No dungeon map to say so (nothing ever reads cleared without one): every starred mob in
                        // sight is dead, which is all a clear is.
                        roomDoneWithoutMap();
                    } else {
                        giveUpRoom("no starred mob is visible but the room is not cleared"
                                + (roamedAny ? " (looked from " + (roamSpotsTried + roamKnownTried) + " more spot(s))"
                                : ""));
                    }
                }
                return;
            }
            travelToRoom(client, player, layout, room);
            return;
        }
        noMobTicks = 0;
        midasCheckOpened(client);
        planRepositions(client, player, mobs, cfg.getWeapon());
        Entity target = pickTarget(player, mobs, cfg.getWeapon());
        if (target.getId() != targetId) {
            targetId = target.getId();
            targetName = target.getName().getString();
            mobTicks = 0;
            planKey = Long.MIN_VALUE;
        }
        if (++mobTicks == 1) {
            mobLag = com.killer560.hub.util.ServerLag.mark();
        }
        if (mobTicks > cfg.getMobTimeoutTicks() + com.killer560.hub.util.ServerLag.ticksSince(mobLag)) {
            skippedMobs.add(targetId);
            LOGGER.info("[AutoClear] skipped {} at {} after {} ticks", targetName, target.blockPosition().toShortString(),
                    mobTicks);
            targetId = -1;
            releaseUse(client);
            return;
        }
        attack(client, player, target, cfg);
    }

    private static int pickRoom(DungeonLayout layout, AutoClearConfig cfg) {
        if (singleRoom != null) {
            int r = AutoClearTargets.roomByName(layout, singleRoom);
            if (r < 0) {
                stop(singleRoom + " is not on the map");
                return -1;
            }
            if (AutoClearTargets.isCleared(layout, r)) {
                finish(singleRoom + " is already cleared");
                return -1;
            }
            if (skippedRooms.contains(singleRoom)) {
                return -1;   // giveUpRoom already stopped the run
            }
            AutoClearTargets.Choice only = AutoClearTargets.choose(layout, List.of(r));
            if (only != null && only.door() >= 0) {
                openDoor(layout, only.door(), singleRoom);
                return -1;
            }
            doorTarget = -1;
            return r;
        }
        List<Integer> candidates = AutoClearTargets.candidates(layout, cfg.getMode(), skippedRooms);
        if (candidates == null) {
            stop("the blood door is not on the map yet (Blood Rush Split)");
            return -1;
        }
        if (candidates.isEmpty()) {
            finish(roomsCleared + " room(s) cleared, nothing left" + (skippedRooms.isEmpty() ? ""
                    : " (gave up on " + String.join(", ", skippedRooms) + ")"));
            return -1;
        }
        AutoClearTargets.Choice c = AutoClearTargets.choose(layout, candidates);
        if (c == null) {
            finish("the uncleared rooms left are behind doors it does not open");
            return -1;
        }
        publishPlan(layout, c.room(), AutoClearTargets.ordered(layout, candidates));
        if (c.door() >= 0) {
            // Nothing else reachable: the wither door on the way is the only thing left before it can go on.
            openDoor(layout, c.door(), layout.name(c.room()));
            return -1;
        }
        doorTarget = -1;
        List<Integer> rush = AutoClearTargets.bloodRushRooms(layout);
        LOGGER.info("[AutoClear] next room {} ({}) - order {}", layout.name(c.room()),
                rush != null && rush.contains(c.room()) ? "on the blood rush" : "off the blood rush",
                AutoClearTargets.ordered(layout, candidates).stream().map(layout::name).toList());
        return c.room();
    }

    /**
     * Publishes the queue this pick came from for the Auto Routes Progress HUD ({@link com.killer560.hub.autoroutes.RunPlan}):
     * the room picked first, then the rest in {@link AutoClearTargets#ordered} order. The pick is not always the head of
     * that order (the head may be behind a door that stays shut), so it is moved to the front rather than assumed.
     */
    private static void publishPlan(DungeonLayout layout, int picked, List<Integer> order) {
        List<String> names = new java.util.ArrayList<>();
        List<Integer> rooms = new java.util.ArrayList<>();
        names.add(layout.name(picked));
        rooms.add(picked);
        for (int r : order) {
            if (r != picked) {
                names.add(layout.name(r));
                rooms.add(r);
            }
        }
        com.killer560.hub.autoroutes.RunPlan.publish(com.killer560.hub.autoroutes.RunPlan.Source.AUTO_CLEAR, names, null,
                roomsCleared, estimateSeconds(layout, rooms));
    }

    /** Seconds a room's clear takes, travel excluded, before this run has cleared one (Dungeon Autopilot's start value). */
    static final double CLEAR_SECONDS_GUESS = 12.0;

    /**
     * The time the rooms left take (killer560, 2026-10-18: "an estimated time till completion that counts down"). Once
     * this run has cleared a room, its own pace: the seconds it has run over the rooms it cleared, per room left. Before
     * that: the etherwarp trip to each room in order (as {@link com.killer560.hub.autoroutes.RunPlan#tripSeconds} prices
     * one) plus {@link #CLEAR_SECONDS_GUESS} a room. NaN with no player.
     */
    private static double estimateSeconds(DungeonLayout layout, List<Integer> rooms) {
        if (roomsCleared > 0) {
            double perRoom = (System.currentTimeMillis() - runStartMs) / 1000.0 / roomsCleared;
            return perRoom * rooms.size();
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return Double.NaN;
        }
        double hop = ClearExecutor.hopRange();
        double x = client.player.getX();
        double z = client.player.getZ();
        double total = 0.0;
        for (int r : rooms) {
            int[] tiles = layout.tiles(r);
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            BlockPos c = DungeonLayout.cellCenter(tiles[0]);
            double dx = c.getX() + 0.5 - x;
            double dz = c.getZ() + 0.5 - z;
            total += com.killer560.hub.autoroutes.RunPlan.tripSeconds(Math.sqrt(dx * dx + dz * dz), hop, 1)
                    + CLEAR_SECONDS_GUESS;
            x = c.getX() + 0.5;
            z = c.getZ() + 0.5;
        }
        return total;
    }

    /** The rooms Auto Clear plans to do, the one under way first (empty when it is not running). For the testkit. */
    public static List<String> plannedRooms() {
        var plan = com.killer560.hub.autoroutes.RunPlan.of(com.killer560.hub.autoroutes.RunPlan.Source.AUTO_CLEAR);
        return plan == null ? List.of() : plan.rooms();
    }

    // ---- wither doors (killer560, 2026-10-06: only when opening one is the only thing left before it can progress) ----

    private static int doorTarget = -1;
    /** The door a trip to its approach spot has finished for: from then on it is clicked from where he stands. */
    private static int doorTripDone = -1;
    private static boolean saidNoKey = false;
    /** Our last click on the door: its block-click sequence (the server acks it, util/ChestWindows), -1 none waiting. */
    private static int doorClickSeq = -1;
    /** Keys the team had when that click went out: fewer since is the server taking the key - the door is opening. */
    private static int doorKeysAtClick = 0;
    /** The server took the key for our click: wait for the door to go, click no more. */
    private static boolean doorAccepted = false;
    /** Clicks of ours on this door, and how many the server answered without taking the key. */
    private static int doorClicks = 0;
    private static int doorRefusals = 0;
    private static long doorStartTick = 0L;
    /** A walk to this door got stuck: etherwarp to it from now on. */
    private static boolean walkOffDoor = false;
    /** Refused clicks before it stops clicking and waits for a teammate (the old behaviour clicked once a second for
     *  ever). */
    static final int DOOR_MAX_REFUSALS = 3;
    /** Ticks after the click's ack before an unchanged key count is read as a refusal (see openDoor). */
    static final int DOOR_ANSWER_TICKS = 2;
    private static long doorAckTick = -1;

    /** The team's wither keys as this client can count them: the sidebar / chat count, the sim's, a hotbar key item. */
    private static int witherKeyCount(LocalPlayer player) {
        int n = com.killer560.hub.doorkeys.DungeonKeys.witherKeys();
        if (com.killer560.hub.roomsim.SimState.isActive()) {
            n = Math.max(n, com.killer560.hub.roomsim.SimDoors.keysHeld());
        } else if (com.killer560.hub.witherdoors.WitherDoorsFeature.isWitherKeyHeld()) {
            n = Math.max(n, 1);
        }
        return Math.max(n, ItemIdentity.findHotbarSlotById(player, "WITHER_KEY") >= 0 ? 1 : 0);
    }

    /** Whether a click from an eye at {@code eye} reaches the door block {@code target} (its box, as the server
     *  measures). */
    private static boolean doorInReach(LocalPlayer player, Vec3 eye, BlockPos target) {
        double reach = player.blockInteractionRange();
        return new AABB(target).distanceToSqr(eye) <= reach * reach;
    }

    /** The first block of the door's column that is still there (the click goes on it), or null once it has gone. */
    private static BlockPos doorClickBlock(Minecraft client, int door) {
        BlockPos lock = DungeonLayout.doorBlock(door);
        for (BlockPos p : new BlockPos[]{lock.above(), lock, lock.above(2)}) {
            if (!client.level.getBlockState(p).getShape(client.level, p).isEmpty()) {
                return p;
            }
        }
        return null;
    }

    /**
     * Gets through a shut wither door as fast as the server lets it (killer560, 2026-10-20: "during Auto Clear it is
     * slow to open wither doors"): to the door's approach - a walk when Walk When Faster says it beats the etherwarp,
     * ending the tick the door is in reach, else an etherwarp trip that ends on the server's answer to its last landing
     * (it used the Interactive Map's slow trip, which waits ~9 ticks after landing) - and from the first tick the door is
     * in reach (trip or not) the key's slot and the body's turn together, the click the tick after. Then it waits only on
     * real signals: the server taking the key (the door is opening - no more clicks), or the server answering the click
     * without taking it (refused - click again, up to {@link #DOOR_MAX_REFUSALS}). Until 2026-10-20 it clicked at most
     * once a second (a 20-tick cooldown) and swapped to the key on a tick of its own. No key: it waits there and says so
     * once. Called each decision while the door is the only way on; it notices the door open and moves on.
     */
    private static void openDoor(DungeonLayout layout, int door, String behind) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        releaseUse(client);
        if (door != doorTarget) {
            doorTarget = door;
            doorTripDone = -1;
            saidNoKey = false;
            doorClickSeq = -1;
            doorAckTick = -1;
            doorAccepted = false;
            doorClicks = 0;
            doorRefusals = 0;
            walkOffDoor = false;
            doorStartTick = ClearExecutor.clientTick();
            LOGGER.info("[AutoClear] only a wither door ({}) stands before {} - going to it", door, behind);
        }
        BlockPos target = doorClickBlock(client, door);
        if (target == null) {
            action = "waiting for the wither door to finish opening";
            return;
        }
        Vec3 eye = player.getEyePosition();
        boolean inReach = doorInReach(player, eye, target);
        int cur = layout.currentRoom();
        BlockPos approach = cur < 0 ? null : DungeonMapPathfinder.getDoorPos(layout, cur, door);
        if (approach == null && !inReach) {
            action = "no way to the wither door";
            return;
        }
        // Once a trip there has finished he is as close as the map takes him: its planner calls anything within five
        // blocks of the approach "already there", so asking again from 2.5-5 blocks off asked every tick for ever
        // (2026-10-07, testkit 131: "trip to wither door" and "Already there" on every tick, never a click).
        boolean arrived = doorTripDone == door;
        if (!inReach && !arrived) {
            if (!AutoClearUtils.canPath(layout, true)) {
                action = "waiting to land before the trip to the wither door";
                return;
            }
            BlockPos goal = TeleportUtils.landable(approach) ? approach : TeleportUtils.nearestEtherwarpable(approach);
            if (goal == null) {
                action = "no block to stand on at the wither door";
                return;
            }
            final BlockPos stand = goal;
            final BlockPos click = target;
            double eyeHeight = player.getEyeHeight();
            if (!walkOffDoor && walkInsteadOfTrip(client, player, stand,
                    feet -> doorInReach(player, feet.add(0, eyeHeight, 0), click), "the wither door", true)) {
                return;
            }
            action = "etherwarp to the wither door";
            startTrip(client, player, () -> {
                ClearExecutor.etherPathFast(stand, null);
                return true;
            });
            if (phase == Phase.TRAVEL) {
                LOGGER.info("[AutoClear] trip to wither door {} (approach {}, standing on {})", door,
                        approach.toShortString(), stand.toShortString());
            }
            return;
        }
        boolean key = witherKeyCount(player) > 0;
        if (doorAccepted) {
            action = "opening the wither door";
            return;
        }
        if (doorClickSeq >= 0) {
            if (witherKeyCount(player) < doorKeysAtClick) {
                doorAccepted = true;
                LOGGER.info("[AutoClear] wither door {}: the server took the key {} ticks after the trip began - opening",
                        door, ClearExecutor.clientTick() - doorStartTick);
                action = "opening the wither door";
                return;
            }
            if (!com.killer560.hub.util.ChestWindows.acked(doorClickSeq)) {
                action = "waiting for the server's answer to the door click";
                return;
            }
            // The ack and the key's going can arrive a tick apart (the ack is flushed with the connection's tick, the
            // inventory and sidebar with the player's): the answer is read on the tick after the ack.
            long now = ClearExecutor.clientTick();
            if (doorAckTick < 0) {
                doorAckTick = now;
            }
            if (now - doorAckTick < DOOR_ANSWER_TICKS) {
                action = "reading the server's answer to the door click";
                return;
            }
            doorAckTick = -1;
            doorClickSeq = -1;
            doorRefusals++;
            LOGGER.info("[AutoClear] wither door {}: the server answered click {} without taking the key ({} of {})", door,
                    doorClicks, doorRefusals, DOOR_MAX_REFUSALS);
        }
        if (doorRefusals >= DOOR_MAX_REFUSALS) {
            action = "waiting at the wither door - it stayed shut";
            if (doorRefusals == DOOR_MAX_REFUSALS) {
                doorRefusals++;
                say(ModChat.bad("The wither door stayed shut"), ModChat.dim(" - waiting there for a teammate"));
            }
            return;
        }
        if (!key) {
            action = "waiting at the wither door - no Wither Key";
            if (!saidNoKey) {
                saidNoKey = true;
                say(ModChat.bad("Waiting at the wither door"), ModChat.dim(" - nobody has the Wither Key yet"));
                LOGGER.info("[AutoClear] at wither door {} with no key - waiting", door);
            }
            return;
        }
        Vec3 aimAt = Vec3.atCenterOf(target);
        if (!inReach) {
            // Measured to the block's box, as the server does. Out of reach from where the map leaves him: say so and
            // stop, rather than clicking at a door the server will never let him reach.
            stop(String.format(Locale.US, "the wither door is %.1f blocks away, out of reach from where the map put you",
                    Math.sqrt(new AABB(target).distanceToSqr(eye))));
            return;
        }
        // The key's slot and the body's turn on the same tick (WitherDoorOpener's AIM step), the click on the next.
        int keySlot = ItemIdentity.findHotbarSlotById(player, "WITHER_KEY");
        boolean swapped = false;
        if (keySlot >= 0 && player.getInventory().getSelectedSlot() != keySlot) {
            select(client, player, keySlot);
            swapped = true;
        }
        TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, aimAt);
        float yaw = player.getYRot() + Mth.wrapDegrees(r.yaw() - player.getYRot());
        float pitch = Mth.clamp(r.pitch(), -90f, 90f);
        bodyWanted = true;
        if (swapped || !BODY.isHeld() || Math.abs(yaw - player.getYRot()) > 0.5f
                || Math.abs(pitch - player.getXRot()) > 0.5f) {
            // Turn first: the click goes out a tick later, from a body a movement packet has already reported.
            BODY.turn(player, yaw, pitch);
            action = "turning to the wither door";
            return;
        }
        var shape = client.level.getBlockState(target).getShape(client.level, target);
        net.minecraft.world.phys.BlockHitResult hit = shape.clip(eye,
                eye.add(aimAt.subtract(eye).normalize().scale(eye.distanceTo(aimAt) + 1.5)), target);
        if (hit == null) {
            action = "no face of the wither door to click";
            return;
        }
        doorKeysAtClick = witherKeyCount(player);
        client.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        doorClickSeq = com.killer560.hub.util.RightClick.lastUseOnSequence();
        doorClicks++;
        action = "opening the wither door";
        LOGGER.info("[AutoClear] clicked wither door {} at {} with the key (click {}, seq {}, {} ticks after the door"
                + " trip began)", door, target.toShortString(), doorClicks, doorClickSeq,
                ClearExecutor.clientTick() - doorStartTick);
    }

    // ---- dropped Wither Keys (killer560, 2026-10-19: "if a Wither Key drops ... walk to it and pick it up before going
    // on, so the wither door can be opened") ----

    /** Keys further than this from him are left to Auto Secret / Autopilot (or him): a detour across the map is not "on
     *  the way". A key stand is only tracked by the client within its entity range anyway. */
    static final double KEY_SEARCH_RANGE = 40.0;
    /** Ticks to stand in pickup range of a key before calling the try failed (Auto Secret's KEY_WAIT_TICKS). */
    static final int KEY_WAIT_TICKS = 40;
    /** Within this many blocks a key vanishing from the client means it was taken (Auto Secret's KEY_GONE_NEAR). */
    static final double KEY_GONE_NEAR = 12.0;

    /** The key stand being gone for, or -1. */
    private static int keyId = -1;
    /** The trip under way (Phase.TRAVEL) is to the key: its failure is not a failure of the room. */
    private static boolean keyTrip = false;
    /** A trip to this key has finished: out of range after it means the map cannot get closer, so it is left. */
    private static boolean keyTripDone = false;
    private static int keyWaitTicks = 0;
    private static long keyWaitLag = 0L;
    private static int keyCountBefore = 0;
    private static double keyLastDist = -1;
    /** Keys tried and not picked up this run: never gone for again (no loop on a key in a wall). */
    private static final Set<Integer> keysTried = new HashSet<>();

    /**
     * Goes for a Wither Key lying within {@link #KEY_SEARCH_RANGE}: Auto Secret's key trip ({@code goKey}) - an
     * Interactive Map etherwarp onto the block nearest to him within the pickup range
     * ({@link com.killer560.hub.doorkeys.DungeonKeys#standWithin}, Key Base Range + 5 less a margin), then standing there
     * until the server picks it up. The key is taken when the team's count goes up
     * ({@link com.killer560.hub.doorkeys.DungeonKeys#witherKeys}: sidebar, else the pickup chat lines) or the stand goes
     * while he is near it. Bounded: one trip, {@link #KEY_WAIT_TICKS} in range, then the key is left for this run.
     * @return true while the key owns this tick (nothing else is decided)
     */
    private static boolean pickUpKey(Minecraft client, LocalPlayer player, DungeonLayout layout) {
        double range = com.killer560.hub.autosecret.AutoSecretConfig.getInstance().keyPickupRange();
        if (keyId >= 0) {
            Entity e = client.level.getEntity(keyId);
            boolean counted = com.killer560.hub.doorkeys.DungeonKeys.witherKeys() > keyCountBefore;
            if (counted || e == null || e.isRemoved()) {
                double gone = Math.max(KEY_GONE_NEAR, range + 4.0);
                if (counted || (keyLastDist >= 0 && keyLastDist <= gone)) {
                    LOGGER.info(String.format(Locale.US, "[AutoClear] key: Wither Key #%d picked up (%s), %.2f blocks"
                                    + " from it the tick before; team now %d wither key(s); chat: %s", keyId,
                            counted ? "the team's count went up" : "the key left the world", keyLastDist,
                            com.killer560.hub.doorkeys.DungeonKeys.witherKeys(),
                            com.killer560.hub.doorkeys.DungeonKeys.lastPickupLine()));
                    ModChat.log(CHAT, ModChat.good("Wither Key"), ModChat.dim(" picked up"));
                } else {
                    // Gone from the client far off is not picked up: it left tracking range.
                    LOGGER.info(String.format(Locale.US, "[AutoClear] key: Wither Key #%d left the client's view %.1f"
                            + " blocks away - not counted as picked up", keyId, keyLastDist));
                }
                keyId = -1;
                return false;
            }
            keyLastDist = player.position().distanceTo(e.position());
        } else {
            com.killer560.hub.doorkeys.DungeonKeys.Dropped best = null;
            double bestD = KEY_SEARCH_RANGE;
            for (com.killer560.hub.doorkeys.DungeonKeys.Dropped k
                    : com.killer560.hub.doorkeys.DungeonKeys.dropped(client)) {
                if (k.blood() || keysTried.contains(k.id())) {
                    continue;
                }
                double d = player.position().distanceTo(new Vec3(k.x(), k.y(), k.z()));
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            if (best == null) {
                return false;
            }
            keyId = best.id();
            keyTripDone = false;
            keyWaitTicks = 0;
            keyCountBefore = com.killer560.hub.doorkeys.DungeonKeys.witherKeys();
            keyLastDist = bestD;
            releaseUse(client);
            ModChat.log(CHAT, ModChat.text("Picking up the "), ModChat.value("Wither Key"),
                    ModChat.dim(String.format(Locale.US, " (%.1f blocks away)", bestD)));
            LOGGER.info(String.format(Locale.US, "[AutoClear] key: a Wither Key #%d lies at %.1f %.1f %.1f, %.1f blocks"
                            + " away (pickup range %.1f; team has %d) - going for it before %s", keyId, best.x(), best.y(),
                    best.z(), bestD, range, keyCountBefore, roomName == null ? "the next room" : roomName));
        }
        releaseUse(client);
        Entity stand = client.level.getEntity(keyId);
        Vec3 keyPos = stand.position();
        double reach = Math.max(1.0, range - com.killer560.hub.doorkeys.DungeonKeys.STAND_MARGIN);
        if (keyLastDist <= reach) {
            action = "waiting for the Wither Key pickup";
            if (++keyWaitTicks == 1) {
                keyWaitLag = com.killer560.hub.util.ServerLag.mark();
                LOGGER.info(String.format(Locale.US, "[AutoClear] key: %.2f blocks from Wither Key #%d, inside the %.1f"
                        + " reach - waiting for the pickup", keyLastDist, keyId, reach));
            }
            if (keyWaitTicks > KEY_WAIT_TICKS + com.killer560.hub.util.ServerLag.ticksSince(keyWaitLag)) {
                LOGGER.info(String.format(Locale.US, "[AutoClear] key: Wither Key #%d still lying there after %d ticks"
                        + " %.2f blocks away - leaving it", keyId, keyWaitTicks, keyLastDist));
                ModChat.log(CHAT, ModChat.bad("The Wither Key was not picked up"),
                        ModChat.dim(String.format(Locale.US, " from %.1f blocks - going on", keyLastDist)));
                keysTried.add(keyId);
                keyId = -1;
                return false;
            }
            return true;
        }
        if (keyTripDone) {
            LOGGER.info(String.format(Locale.US, "[AutoClear] key: the trip ended %.2f blocks from Wither Key #%d (reach"
                    + " %.1f) - the map gets no closer, leaving it", keyLastDist, keyId, reach));
            keysTried.add(keyId);
            keyId = -1;
            return false;
        }
        if (!AutoClearUtils.canPath(layout)) {
            action = "waiting to land before the trip to the Wither Key";
            return true;
        }
        BlockPos goal = com.killer560.hub.doorkeys.DungeonKeys.standWithin(keyPos, reach, player.position());
        if (goal == null) {
            goal = TeleportUtils.nearestEtherwarpable(BlockPos.containing(keyPos));
        }
        if (goal == null) {
            LOGGER.info("[AutoClear] key: no block to stand on near Wither Key #{} - leaving it", keyId);
            keysTried.add(keyId);
            keyId = -1;
            return false;
        }
        final BlockPos stand0 = goal;
        action = "etherwarp to the Wither Key";
        startTrip(client, player, () -> {
            ClearExecutor.etherPathFast(stand0, null);
            return true;
        });
        if (phase == Phase.TRAVEL) {
            keyTrip = true;
            keyTripDone = true;
            LOGGER.info(String.format(Locale.US, "[AutoClear] key: trip to Wither Key #%d, standing on %s (%.1f from the"
                    + " key)", keyId, stand0.toShortString(),
                    keyPos.distanceTo(new Vec3(stand0.getX() + 0.5, stand0.getY() + 1.0, stand0.getZ() + 0.5))));
        }
        return true;
    }

    // ---- roaming a room that shows nothing (killer560, 2026-10-19: "when the current room is not yet clear but no mob
    // is visible or reachable, move around the room ... before giving up on the room") ----

    /** No starred mob in sight (or every one skipped as out of reach) this long: the first roam. Before 2026-10-19 the
     *  room was given up at {@link #NO_MOB_TICKS} with no move at all. */
    static final int ROAM_AFTER_TICKS = 40;
    /** After each roam landing: this long looking for the room's mobs before the next spot (or giving up). */
    static final int ROAM_LOOK_TICKS = 30;
    /** At most this many spots per room. */
    static final int ROAM_MAX_SPOTS = 4;
    /** And at most this long from the first roam, however the trips went (real time; server lag stretches it). */
    static final long ROAM_TIME_LIMIT_MS = 25_000L;
    /** A spot must be this far from everywhere already looked from, or it shows nothing new. */
    static final double ROAM_MIN_SEPARATION = 8.0;
    /** The offset of each quadrant's look point from its tile centre (a tile is 31 across). */
    private static final int ROAM_QUADRANT = 8;

    private static int roamSpotsTried = 0;
    private static long roamStartMs = 0L;
    /** No spot left, or the bound reached: give up the room as before. */
    private static boolean roamDone = false;
    /** The trip under way (Phase.TRAVEL) is a roam: its failure is not one of the room's tries. */
    private static boolean roamTrip = false;
    private static final List<Vec3> roamLookedFrom = new java.util.ArrayList<>();
    /** The room's candidate spots (computed on the first roam), minus those taken. */
    private static List<BlockPos> roamSpots = null;

    private static void resetRoam() {
        roamSpotsTried = 0;
        roamKnownTried = 0;
        roamStartMs = 0L;
        roamDone = false;
        roamTrip = false;
        roamLookedFrom.clear();
        roamSpots = null;
        walkOffRoam = false;
    }

    // ---- mobs that left the client's view alive (killer560, 2026-10-19: "the clear was still really unoptimal") ----
    // Altar, 2026-10-19 22:15:35-46: two Zombies at -184, 68, -96 were in the room's plan until he warped 36 blocks away
    // to -187, 70, -60; there they left the client's entity view (alive - the room was not cleared), the room showed
    // nothing for 46 ticks, and the first roam went 43 blocks the other way. Where a mob was last seen is the best guess
    // of where it is, so a room with such mobs (or with mobs skipped as out of reach) roams to them first, at once.

    /** Gone from the client while alive and at least this far away: it left the entity view, it did not die. A death is
     *  seen first as the mob no longer alive (its death animation), on a tick this class looks at. */
    static final double LOST_MIN_DISTANCE = 20.0;
    /** With a known mob to go to, the roam starts after this long with nothing in sight (instead of ROAM_AFTER_TICKS):
     *  long enough for the server's entity packets after a landing, no longer. */
    static final int ROAM_KNOWN_AFTER_TICKS = 5;
    /** Trips to known mobs per room (each lost mob is gone for once, so this only bounds a room that keeps losing them). */
    static final int ROAM_KNOWN_MAX = 6;
    /** Within this of the block a known mob is reached from, and it is not in sight: it is not there any more. */
    private static final double KNOWN_THERE = 3.0;

    /**
     * A starred mob as last seen: where, how far from him, whether it has since left the view alive ({@code lost}), the
     * room it counts for ({@code room}, by name - the edge rule below can keep it there a little past the room's edge),
     * its velocity in blocks a tick (smoothed over consecutive sightings, for the bat lead), the health its name tag
     * read (-1 none), and the ClearExecutor tick it was seen.
     */
    private record Seen(String name, Vec3 pos, double dist, boolean lost, String room, Vec3 vel, double health,
                        long tick) {
    }

    /**
     * Every starred mob of the run as last seen, by entity id, whatever room it is in (killer560, 2026-10-20: "keep the
     * last known position of each mob in the room ... and use it when planning a pathfind"). Since 2026-10-20 it lasts
     * the whole run instead of being cleared with each room's roam, so a room's mobs seen while clearing the one next to
     * it are still known when Auto Clear gets there. An entry is overwritten whenever the mob is seen (elsewhere
     * included), and dropped when it dies.
     */
    private static final java.util.Map<Integer, Seen> seenMobs = new java.util.HashMap<>();
    private static int roamKnownTried = 0;

    /**
     * killer560, 2026-10-20: "Sometimes a mob walks out of the room by a hair; Auto Clear then loses it and pathfinds
     * around the room looking for it." A mob seen in a room still counts for it while it is no further than this outside
     * the room's tiles (horizontally, each tile 32 across round {@link DungeonLayout#cellCenter} as
     * {@code MobEspFeature.currentRoomBox} reads them). "About one block"; 1.5 lets a mob standing in a doorway's gap
     * block and a half step past it stay.
     */
    static final double EDGE_MARGIN = 1.5;
    /** Mobs the edge rule has kept in their room, each logged once a run. */
    private static final Set<Integer> edgeLogged = new HashSet<>();

    /** A mob to roam to: last seen at {@code pos}; {@code lostId} >= 0 for a lost one (forgotten once gone for). */
    private record Known(String name, Vec3 pos, int lostId, String why) {
    }

    /** How far (blocks, horizontally) {@code x, z} lies outside {@code room}'s tiles; 0 inside them. */
    static double outsideRoom(DungeonLayout layout, int room, double x, double z) {
        int[] tiles = room < 0 ? null : layout.tiles(room);
        if (tiles == null || tiles.length == 0) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (int t : tiles) {
            BlockPos c = DungeonLayout.cellCenter(t);
            double dx = Math.max(0.0, Math.abs(x - (c.getX() + 0.5)) - 16.0);
            double dz = Math.max(0.0, Math.abs(z - (c.getZ() + 0.5)) - 16.0);
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    /**
     * The room a starred mob counts for: the one under it, unless it was last counted for another room and is still
     * within {@link #EDGE_MARGIN} of that one (it walked out by a hair - it is still that room's mob, and that room's
     * clear still needs it). Null when it is on no room.
     */
    private static String mobRoom(DungeonLayout layout, Entity mob, Seen before) {
        int under = layout.roomAtWorld(mob.getX(), mob.getZ());
        String here = under < 0 ? null : layout.name(under);
        if (before == null || before.lost() || before.room() == null || before.room().equals(here)) {
            return here;
        }
        int was = AutoClearTargets.roomByName(layout, before.room());
        double out = was < 0 ? Double.MAX_VALUE : outsideRoom(layout, was, mob.getX(), mob.getZ());
        if (out <= EDGE_MARGIN) {
            if (edgeLogged.add(mob.getId())) {
                LOGGER.info(String.format(Locale.US, "[AutoClear] %s at %s is %.2f blocks outside %s (over into %s) - still"
                                + " counted as its mob (edge %.1f)", mob.getName().getString(),
                        mob.blockPosition().toShortString(), out, before.room(), here, EDGE_MARGIN));
            }
            return before.room();
        }
        return here;
    }

    /** Whether {@code mob} counts for {@code room}: under it, or kept there by the edge rule ({@link #mobRoom}). */
    private static boolean inRoom(DungeonLayout layout, int room, Entity mob) {
        if (layout.roomAtWorld(mob.getX(), mob.getZ()) == room) {
            return true;
        }
        Seen s = seenMobs.get(mob.getId());
        return s != null && !s.lost() && layout.name(room).equals(s.room())
                && outsideRoom(layout, room, mob.getX(), mob.getZ()) <= EDGE_MARGIN;
    }

    /** Every running tick (trips included, so a mob dying under a bat in flight is seen dying, not lost). */
    private static void observeMobs(Minecraft client, LocalPlayer player) {
        if (client.level == null) {
            seenMobs.clear();
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        long tick = ClearExecutor.clientTick();
        java.util.Map<Integer, String> tags = new java.util.HashMap<>();
        Set<Integer> now = new HashSet<>();
        for (Entity mob : MobEspFeature.starredMobs(client, tags)) {
            Seen before = seenMobs.get(mob.getId());
            String room = mobRoom(layout, mob, before);
            if (room == null) {
                continue;
            }
            now.add(mob.getId());
            if (before != null && before.lost()) {
                LOGGER.info(String.format(Locale.US, "[AutoClear] %s is in view again at %s, %.1f blocks away",
                        before.name(), mob.blockPosition().toShortString(), mob.distanceTo(player)));
            }
            Vec3 pos = mob.position();
            Vec3 vel = Vec3.ZERO;
            if (before != null && !before.lost() && tick - before.tick() == 1) {
                Vec3 step = pos.subtract(before.pos());
                // A step longer than two blocks is a teleport (or a fresh entity id's first move), not a walk.
                vel = step.lengthSqr() > 4.0 ? Vec3.ZERO : before.vel().scale(0.5).add(step.scale(0.5));
            }
            double hp = WeaponReach.tagHealth(tags.get(mob.getId()));
            if (hp < 0 && before != null) {
                hp = before.health();
            }
            seenMobs.put(mob.getId(), new Seen(mob.getName().getString(), pos, mob.distanceTo(player), false, room, vel,
                    hp, tick));
        }
        for (var it = seenMobs.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Seen s = entry.getValue();
            if (now.contains(entry.getKey()) || s.lost()) {
                continue;
            }
            Entity e = client.level.getEntity(entry.getKey());
            if (e != null) {
                if (!e.isAlive() || e.isRemoved()) {
                    it.remove();   // dead
                    mobDied(entry.getKey(), s);
                }
                continue;          // still there without its star tag: kept as last seen
            }
            if (s.dist() >= LOST_MIN_DISTANCE) {
                entry.setValue(new Seen(s.name(), s.pos(), s.dist(), true, s.room(), Vec3.ZERO, s.health(), s.tick()));
                LOGGER.info(String.format(Locale.US, "[AutoClear] %s of %s left the client's view alive at %s, %.1f blocks"
                                + " away - its spot remembered", s.name(), s.room(),
                        BlockPos.containing(s.pos()).toShortString(), s.dist()));
            } else {
                it.remove();       // gone this near: killed
                mobDied(entry.getKey(), s);
            }
        }
    }

    /** The room's mobs worth going to with nothing in sight: those lost from view there, then those skipped. */
    private static List<Known> knownMobs(Minecraft client, DungeonLayout layout, int room) {
        List<Known> out = new java.util.ArrayList<>();
        String name = layout.name(room);
        for (var entry : seenMobs.entrySet()) {
            if (entry.getValue().lost() && name.equals(entry.getValue().room())) {
                out.add(new Known(entry.getValue().name(), entry.getValue().pos(), entry.getKey(), "left the view"));
            }
        }
        for (int id : skippedMobs) {
            Entity e = client.level.getEntity(id);
            if (e != null && e.isAlive() && inRoom(layout, room, e)) {
                out.add(new Known(e.getName().getString(), e.position(), -1, "skipped as out of reach"));
            }
        }
        return out;
    }

    /**
     * Takes the next roam spot: of the room's spots (one per quadrant of each of its tiles,
     * {@link TeleportUtils#etherwarpableInTile}), the one farthest from everywhere he has looked from, by an Interactive
     * Map trip. Mobs skipped in this room as out of reach are tried again from there. Bounded by
     * {@link #ROAM_MAX_SPOTS} and {@link #ROAM_TIME_LIMIT_MS}; each spot and why is logged.
     * @return true while roaming owns this tick (a trip started or waiting to), false once it is done (roamDone set)
     */
    private static boolean roam(Minecraft client, LocalPlayer player, DungeonLayout layout, int room, List<Known> known) {
        long now = System.currentTimeMillis();
        if (roamStartMs == 0L) {
            roamStartMs = now;
            roamLookedFrom.add(player.position());
        }
        if (now - roamStartMs <= ROAM_TIME_LIMIT_MS && !known.isEmpty()) {
            if (!AutoClearUtils.canPath(layout)) {
                action = "waiting to land before going to a mob of " + roomName;
                return true;
            }
            if (roamToKnown(client, player, layout, room, known)) {
                return true;
            }
            // None worth a trip after all: the room gets its ordinary look before the spots across it.
            if (noMobTicks <= (roamSpotsTried == 0 && roamKnownTried == 0 ? ROAM_AFTER_TICKS : ROAM_LOOK_TICKS)) {
                action = "looking for starred mobs";
                return true;
            }
        }
        if (roamSpotsTried >= ROAM_MAX_SPOTS) {
            LOGGER.info("[AutoClear] roam: {} spot(s) in {} showed no starred mob - giving up on the room", roamSpotsTried,
                    roomName);
            roamDone = true;
            return false;
        }
        if (now - roamStartMs > ROAM_TIME_LIMIT_MS) {
            LOGGER.info("[AutoClear] roam: {} s in {} after {} spot(s) - the time limit, giving up on the room",
                    (now - roamStartMs) / 1000, roomName, roamSpotsTried);
            roamDone = true;
            return false;
        }
        if (!AutoClearUtils.canPath(layout)) {
            action = "waiting to land before roaming " + roomName;
            return true;
        }
        if (roamSpots == null) {
            roamSpots = roamCandidates(layout, room);
            LOGGER.info("[AutoClear] roam: {} candidate spot(s) in {}: {}", roamSpots.size(), roomName,
                    roamSpots.stream().map(BlockPos::toShortString).toList());
        }
        BlockPos best = null;
        double bestD = ROAM_MIN_SEPARATION;
        for (BlockPos s : roamSpots) {
            Vec3 feet = new Vec3(s.getX() + 0.5, s.getY() + 1.0, s.getZ() + 0.5);
            double nearest = Double.MAX_VALUE;
            for (Vec3 v : roamLookedFrom) {
                nearest = Math.min(nearest, feet.distanceTo(v));
            }
            if (nearest >= bestD) {
                bestD = nearest;
                best = s;
            }
        }
        if (best == null) {
            LOGGER.info("[AutoClear] roam: no spot in {} at least {} blocks from where it has looked ({} spot(s) tried) -"
                    + " giving up on the room", roomName, ROAM_MIN_SEPARATION, roamSpotsTried);
            roamDone = true;
            return false;
        }
        final BlockPos spot = best;
        final double separation = bestD;
        action = "roaming " + roomName + " (" + (roamSpotsTried + 1) + "/" + ROAM_MAX_SPOTS + ")";
        if (!walkInsteadOfTrip(client, player, spot, null, "roam spot " + (roamSpotsTried + 1) + " in " + roomName)) {
            startTrip(client, player, () -> {
                ClearExecutor.etherPathFast(spot, null);
                return true;
            });
        }
        if (phase != Phase.TRAVEL && phase != Phase.WALK) {
            return true;   // the body goes back first (Phase.RELEASE); this comes through again next tick
        }
        roamTrip = true;
        roamSpotsTried++;
        roamSpots.remove(spot);
        roamLookedFrom.add(new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5));
        int freed = freeSkipped(client, layout, room);
        LOGGER.info(String.format(Locale.US, "[AutoClear] roam %d/%d in %s: to %s, %.1f blocks away and %.1f from the"
                        + " nearest spot looked from - why: no starred mob in sight for %d ticks, the room not cleared"
                        + " (%d killed here so far, %d skipped as out of reach - tried again from there)", roamSpotsTried,
                ROAM_MAX_SPOTS, roomName, spot.toShortString(), player.position().distanceTo(new Vec3(spot.getX() + 0.5,
                        spot.getY() + 1.0, spot.getZ() + 0.5)), separation, noMobTicks, killsInRoom, freed));
        ModChat.log(CHAT, ModChat.text("Nothing in sight - looking from another spot "),
                ModChat.dim("(" + roamSpotsTried + "/" + ROAM_MAX_SPOTS + ")"));
        return true;
    }

    /** Out of reach from where he stood is not out of reach from the next spot: the room's skipped mobs are tried again. */
    private static int freeSkipped(Minecraft client, DungeonLayout layout, int room) {
        int freed = 0;
        for (java.util.Iterator<Integer> it = skippedMobs.iterator(); it.hasNext(); ) {
            Entity e = client.level.getEntity(it.next());
            if (e != null && e.isAlive() && inRoom(layout, room, e)) {
                it.remove();
                freed++;
            }
        }
        return freed;
    }

    /**
     * Goes to the nearest known mob of the room (lost from view, or skipped): an Interactive Map trip onto a block that
     * reaches where it was last seen ({@link WeaponReach#standSpot}), else the nearest block to stand on there. One that
     * should be in sight from where he stands is forgotten (it moved or died). Each trip is logged with the mob and why.
     * @return true when a trip started (or the body is going back first); false when no known mob is worth a trip
     */
    private static boolean roamToKnown(Minecraft client, LocalPlayer player, DungeonLayout layout, int room,
                                       List<Known> known) {
        List<Known> byDistance = new java.util.ArrayList<>(known);
        Vec3 feet = player.position();
        byDistance.sort(java.util.Comparator.comparingDouble(k -> k.pos().distanceToSqr(feet)));
        AutoClearConfig.Weapon weapon = AutoClearConfig.getInstance().getWeapon();
        for (Known k : byDistance) {
            Vec3 p = k.pos();
            AABB box = new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.95, p.z + 0.3);
            BlockPos stand = WeaponReach.standSpot(client.level, player, box, weapon);
            if (stand == null) {
                stand = TeleportUtils.nearestEtherwarpable(BlockPos.containing(p));
            }
            double d = stand == null ? 0.0
                    : feet.distanceTo(new Vec3(stand.getX() + 0.5, stand.getY() + 1.0, stand.getZ() + 0.5));
            if (stand == null || d < KNOWN_THERE || layout.roomAtWorld(stand.getX() + 0.5, stand.getZ() + 0.5) != room) {
                if (k.lostId() >= 0) {
                    seenMobs.remove(k.lostId());
                    LOGGER.info("[AutoClear] {} last seen at {} is not there ({}) - forgotten", k.name(),
                            BlockPos.containing(p).toShortString(), stand == null ? "no block to stand on near it"
                                    : d < KNOWN_THERE ? "standing by it and it is not in sight" : "that block is another room's");
                }
                continue;
            }
            final BlockPos spot = stand;
            action = "roaming " + roomName + " to " + k.name() + " (" + k.why() + ")";
            if (!walkInsteadOfTrip(client, player, spot, null, k.name() + " (" + k.why() + ")")) {
                startTrip(client, player, () -> {
                    ClearExecutor.etherPathFast(spot, null);
                    return true;
                });
            }
            if (phase != Phase.TRAVEL && phase != Phase.WALK) {
                return true;   // the body goes back first (Phase.RELEASE); this comes through again next tick
            }
            roamTrip = true;
            roamKnownTried++;
            if (k.lostId() >= 0) {
                seenMobs.remove(k.lostId());   // once: in view again it is seen afresh, not there it is not gone for again
            }
            roamLookedFrom.add(new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5));
            int freed = freeSkipped(client, layout, room);
            LOGGER.info(String.format(Locale.US, "[AutoClear] roam to a known mob %d/%d in %s: %s %s, last at %s - standing"
                            + " on %s, %.1f blocks away, after %d tick(s) with nothing in sight (%d known, %d skipped tried"
                            + " again)", roamKnownTried, ROAM_KNOWN_MAX, roomName, k.name(), k.why(),
                    BlockPos.containing(p).toShortString(), spot.toShortString(), d, noMobTicks, known.size(), freed));
            ModChat.log(CHAT, ModChat.text("Going to "), ModChat.value(k.name()),
                    ModChat.dim(" (" + k.why() + ")"));
            return true;
        }
        return false;
    }

    /** One standable block per quadrant of each of the room's tiles, distinct. */
    private static List<BlockPos> roamCandidates(DungeonLayout layout, int room) {
        List<BlockPos> out = new java.util.ArrayList<>();
        int[] tiles = layout.tiles(room);
        if (tiles == null) {
            return out;
        }
        int[][] quadrants = {{ROAM_QUADRANT, ROAM_QUADRANT}, {ROAM_QUADRANT, -ROAM_QUADRANT},
                {-ROAM_QUADRANT, ROAM_QUADRANT}, {-ROAM_QUADRANT, -ROAM_QUADRANT}};
        for (int t : tiles) {
            BlockPos c = DungeonLayout.cellCenter(t);
            for (int[] q : quadrants) {
                BlockPos s = TeleportUtils.etherwarpableInTile(c, new Vec3(c.getX() + 0.5 + q[0], c.getY(),
                        c.getZ() + 0.5 + q[1]));
                if (s != null && !out.contains(s) && layout.roomAtWorld(s.getX() + 0.5, s.getZ() + 0.5) == room) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static void roomDoneWithoutMap() {
        roomsCleared++;
        LOGGER.info("[AutoClear] room done without a map: {} ({} starred kill(s), none left in sight)", roomName,
                killsInRoom);
        if (singleRoom != null) {
            finish(roomName + " cleared (no starred mob left in sight)");
            return;
        }
        skippedRooms.add(roomName);   // the map will never say so; do not come back to it
        roomName = null;
        targetId = -1;
    }

    private static void giveUpRoom(String why) {
        say(ModChat.bad("Giving up on "), ModChat.value(String.valueOf(roomName)), ModChat.dim(" - " + why));
        LOGGER.info("[AutoClear] gave up on {}: {}", roomName, why);
        lastReason = roomName + ": " + why;
        lastReasonMs = System.currentTimeMillis();
        if (singleRoom != null) {
            stop(why);
            return;
        }
        if (roomName != null) {
            skippedRooms.add(roomName);
        }
        roomName = null;
        targetId = -1;
        releaseUse(Minecraft.getInstance());
    }

    // =========================================================================================== King Midas altar

    /** Superbooms on the altar that changed nothing before the room is given up. */
    static final int MIDAS_MAX_BOOMS = 3;
    /** The room the altar state below belongs to (its name), or null. */
    private static String midasFor = null;
    private static List<BlockPos> midasLid = null;
    private static boolean midasSaidNone = false;
    private static boolean midasOpened = false;
    private static int midasBooms = 0;
    /** Ticks since the last Superboom on the altar while its answer is awaited, else -1. */
    private static int midasWait = -1;
    private static long midasLag = 0L;

    /**
     * King Midas (killer560, 2026-10-19/20): its mob only spawns once the gold altar in the middle of the room is
     * superboomed ({@link MidasAltar}), so with no starred mob in sight and the altar still standing, the altar comes
     * first: a Superboom tap on its lid ({@link com.killer560.hub.autoroutes.RouteExecutor#sendBoomTap}, the boom node's
     * own packets) from where he stands, or an etherwarp to a block that reaches it first. Then it waits on the lid
     * breaking - the server's answer, no fixed delay - and from there the room is an ordinary mob room: the spawned mob is
     * found and killed by the normal clear, and the room is cleared when it dies.
     *
     * @return true while the altar step owns this tick; false once there is no standing altar (opened, or none found)
     */
    private static boolean midasAltar(Minecraft client, LocalPlayer player, DungeonLayout layout, int room) {
        if (!MidasAltar.ROOM.equals(roomName)) {
            return false;
        }
        if (!roomName.equals(midasFor)) {
            midasFor = roomName;
            midasLid = null;
            midasSaidNone = false;
            midasOpened = false;
            midasBooms = 0;
            midasWait = -1;
        }
        if (midasOpened) {
            return false;
        }
        if (midasLid == null) {
            for (int tile : layout.tiles(room)) {
                List<BlockPos> lid = MidasAltar.find(client.level, LiveMapFeature.roomWorldBounds(tile));
                if (lid != null && (midasLid == null || lid.size() > midasLid.size())) {
                    midasLid = lid;
                }
            }
            if (midasLid == null) {
                if (!midasSaidNone) {
                    midasSaidNone = true;
                    LOGGER.info("[AutoClear] King Midas: no altar standing (already opened?) - clearing it as a mob room");
                }
                return false;
            }
            BlockPos a = midasLid.get(0);
            LOGGER.info("[AutoClear] King Midas: altar lid of {} smooth stone slabs at y {} (around {}) - booming it first",
                    midasLid.size(), a.getY(), a.toShortString());
        }
        if (midasCheckOpened(client)) {
            return false;
        }
        if (midasWait >= 0) {
            if (midasWait++ == 0) {
                midasLag = com.killer560.hub.util.ServerLag.mark();
            }
            if (midasWait <= com.killer560.hub.autoroutes.RouteExecutor.boomSettleTicks()
                    + com.killer560.hub.util.ServerLag.ticksSince(midasLag)) {
                action = "waiting for the King Midas altar to break";
                return true;
            }
            LOGGER.info("[AutoClear] King Midas: Superboom {} changed nothing within {} tick(s)", midasBooms, midasWait);
            midasWait = -1;
            if (midasBooms >= MIDAS_MAX_BOOMS) {
                giveUpRoom("the King Midas altar did not break after " + midasBooms + " Superbooms");
                return true;
            }
        }
        int slot = com.killer560.hub.autoroutes.RouteExecutor.superboomSlot(player);
        if (slot < 0) {
            giveUpRoom("no Superboom in the hotbar to open the King Midas altar");
            return true;
        }
        double reach = player.blockInteractionRange();
        net.minecraft.world.phys.BlockHitResult hit = altarHit(client, player, player.getEyePosition(), reach);
        if (hit == null) {
            if (!player.onGround()) {
                action = "landing";
                return true;
            }
            BlockPos spot = altarStandSpot(client, player, reach - 0.5);
            if (spot == null) {
                giveUpRoom("no block to Superboom the King Midas altar from");
                return true;
            }
            action = "etherwarp to the King Midas altar";
            startTrip(client, player, () -> {
                ClearExecutor.etherPath(spot, null);
                return true;
            });
            if (phase == Phase.TRAVEL) {
                LOGGER.info("[AutoClear] King Midas: etherwarp to {} to reach the altar", spot.toShortString());
            }
            return true;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            select(client, player, slot);
            action = "Superboom in hand";
            return true;
        }
        Vec3 eye = player.getEyePosition();
        TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, hit.getLocation());
        float yaw = player.getYRot() + Mth.wrapDegrees(r.yaw() - player.getYRot());
        float pitch = Mth.clamp(r.pitch(), -90f, 90f);
        bodyWanted = true;
        if (!BODY.isHeld() || Math.abs(yaw - player.getYRot()) > 0.5f || Math.abs(pitch - player.getXRot()) > 0.5f) {
            // Turn first: the tap goes out a tick later, from a body a movement packet has already reported.
            BODY.turn(player, yaw, pitch);
            action = "turning to the King Midas altar";
            return true;
        }
        com.killer560.hub.autoroutes.RouteExecutor.sendBoomTap(client, player, hit.getBlockPos(), hit.getDirection());
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        midasBooms++;
        midasWait = 0;
        action = "Superboom on the King Midas altar";
        ModChat.log(CHAT, ModChat.text("Superboom on the King Midas altar"));
        LOGGER.info(String.format(Locale.US, "[AutoClear] King Midas: Superboom %d on the altar at %s face %s from %s (%.2f"
                        + " blocks)", midasBooms, hit.getBlockPos().toShortString(), hit.getDirection(), fmt(eye),
                Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, hit.getBlockPos()))));
        return true;
    }

    /**
     * Whether the altar this room's step found has opened: the structure breaking is the signal, and from then on the
     * room is an ordinary mob room. Said once. Also asked while the spawned mob is already in sight (it can be the very
     * next tick), so the log says the altar opened before it says the mob died.
     */
    private static boolean midasCheckOpened(Minecraft client) {
        if (midasOpened || midasLid == null || !String.valueOf(roomName).equals(midasFor)) {
            return midasOpened;
        }
        int standing = MidasAltar.standing(client.level, midasLid);
        if (standing == midasLid.size()) {
            return false;
        }
        midasOpened = true;
        LOGGER.info("[AutoClear] King Midas: altar opened ({} of {} lid slabs gone{}) - clearing the mob",
                midasLid.size() - standing, midasLid.size(), midasBooms > 0 ? ", " + midasWait
                        + " tick(s) after Superboom " + midasBooms : ", not by Auto Clear");
        midasWait = -1;
        ModChat.log(CHAT, ModChat.text("King Midas altar opened"), ModChat.dim(" - clearing the mob"));
        return true;
    }

    /**
     * A tap on the altar's lid from {@code eye}: a face of a lid slab that is exposed, faces the eye and is the first thing
     * the eye's ray meets, within {@code reach} of the slab's box (as the server measures). The nearest such slab, or null.
     */
    private static net.minecraft.world.phys.BlockHitResult altarHit(Minecraft client, LocalPlayer player, Vec3 eye,
                                                                    double reach) {
        net.minecraft.world.phys.BlockHitResult best = null;
        double bestSq = reach * reach;
        for (BlockPos p : midasLid) {
            double sq = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, p);
            if (sq > bestSq) {
                continue;
            }
            net.minecraft.world.phys.BlockHitResult face = com.killer560.hub.util.BlockHits.exposedSurface(client.level, p, eye);
            if (face == null || face.getLocation().distanceTo(eye) > reach) {
                continue;
            }
            Vec3 dir = face.getLocation().subtract(eye);
            HitResult seen = client.level.clip(new ClipContext(eye, eye.add(dir.normalize().scale(dir.length() + 0.05)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (!(seen instanceof net.minecraft.world.phys.BlockHitResult b) || !b.getBlockPos().equals(p)) {
                continue;   // something else is in the way
            }
            best = face;
            bestSq = sq;
        }
        return best;
    }

    /**
     * The nearest block to etherwarp onto from which {@link #altarHit} finds a lid face within {@code reach} - never on
     * the altar itself (booming the lid he stands on would drop him into the tomb).
     */
    private static BlockPos altarStandSpot(Minecraft client, LocalPlayer player, double reach) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int y = midasLid.get(0).getY();
        for (BlockPos p : midasLid) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = minX - 5; x <= maxX + 5; x++) {
            for (int z = minZ - 5; z <= maxZ + 5; z++) {
                if (x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1) {
                    continue;   // on or against the tomb
                }
                for (int dy = -4; dy <= 1; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    if (!TeleportUtils.landable(pos)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(x + 0.5, pos.getY() + 1.0 + com.killer560.hub.autopuzzles.AutoPuzzleUtil.EYE_STANDING,
                            z + 0.5);
                    if (altarHit(client, player, eye, reach) == null) {
                        continue;
                    }
                    double d = player.distanceToSqr(x + 0.5, pos.getY() + 1.0, z + 0.5);
                    if (d < bestD) {
                        bestD = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    // =========================================================================================== attacking

    private static void attack(Minecraft client, LocalPlayer player, Entity target, AutoClearConfig cfg) {
        AutoClearConfig.Weapon weapon = cfg.getWeapon();
        Vec3 feet = player.position();
        AABB box = target.getBoundingBox();
        long key = BlockPos.containing(feet).asLong() * 31 + target.blockPosition().asLong() * 17 + target.getId();
        if (key != planKey) {
            planKey = key;
            cachedAttack = weapon == AutoClearConfig.Weapon.HYPERION
                    ? (WeaponReach.hyperionHits(feet, box) ? WeaponReach.hyperionInPlace(player, feet) : null)
                    : leadAim(client, player, target, box);
            cachedHops = -2;   // computed only if a move is needed
            cachedBat = null;
            cachedBatTicks = -2;
            cachedPreferEther = null;
            cachedAttackFlight = -1;
        }
        if (cachedAttack != null) {
            // With the sceptre, a bat from here still has to FLY there: a far one can be slower than etherwarping onto
            // the plan's block and firing down. Never second-guessed once its use is held (no flip-flop mid-flight).
            boolean held = useHeld && phase == Phase.ATTACK;
            if (weapon != AutoClearConfig.Weapon.SPIRIT_SCEPTRE || held
                    || !sceptreSlowerThanEther(client, player, target, box, cfg)) {
                WeaponReach.Aim aim = weapon == AutoClearConfig.Weapon.HYPERION
                        ? WeaponReach.hyperionInPlace(player, feet) : cachedAttack;
                if (weapon == AutoClearConfig.Weapon.SPIRIT_SCEPTRE) {
                    if (cachedAttackFlight < 0) {
                        cachedAttackFlight = WeaponReach.batTicks(client.level, player, player.getEyePosition(), aim);
                        logBatDecision(target, box, aim, cachedAttackFlight, held ? "its use is already held"
                                : "a bat from here reaches it and no etherwarp is faster");
                    }
                    holdingBatFlight = cachedAttackFlight;
                    if (waitForBatInFlight(client)) {
                        return;
                    }
                    BatLog.expectTarget(targetId);
                }
                action = aim.what() + " -> " + targetName;
                aimAndHold(client, player, weapon.itemId(), weapon.label(), aim, Phase.ATTACK);
                return;
            }
        }
        // ---- a move is needed (or, with the Hyperion, a Spirit Sceptre bat from here) ----
        boolean canHop = cfg.isHyperionHops() && hopsOffFor != targetId
                && ItemIdentity.findHotbarSlot(player, AutoClearConfig.Weapon.HYPERION.itemId()) >= 0;
        if (cachedHops == -2) {
            cachedHops = canHop ? WeaponReach.hyperionHopsNeeded(client.level, player, feet, box, MAX_HOPS) : -1;
        }
        double dist = Math.sqrt(box.distanceToSqr(feet));
        int warps = Math.max(1, (int) Math.ceil(dist / ClearExecutor.hopRange()));
        int etherTicks = ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * warps;
        int hypeTicks = cachedHops > 0 ? 2 + USE_REPEAT_TICKS * (cachedHops - 1) : Integer.MAX_VALUE;
        if (weapon == AutoClearConfig.Weapon.HYPERION && hyperionBatWins(client, player, target, box, etherTicks,
                canHop ? hypeTicks : Integer.MAX_VALUE)) {
            holdingBatFlight = cachedBatTicks;
            if (waitForBatInFlight(client)) {
                return;
            }
            action = "Guided Bat -> " + targetName + " (~" + cachedBatTicks + " ticks vs etherwarp ~"
                    + (etherTicks + HYPERION_KILL_TICKS) + (canHop && cachedHops > 0 ? ", hops ~" + hypeTicks : "") + ")";
            BatLog.expectTarget(targetId);
            aimAndHold(client, player, AutoClearConfig.Weapon.SPIRIT_SCEPTRE.itemId(),
                    AutoClearConfig.Weapon.SPIRIT_SCEPTRE.label(), cachedBat, Phase.ATTACK);
            return;
        }
        releaseUse(client);
        if (!player.onGround()) {
            action = "landing";
            return;
        }
        if (canHop && hypeTicks <= etherTicks) {
            WeaponReach.Aim hop = WeaponReach.hyperionHop(client.level, player, feet, box);
            // A hop is a reposition too: where the plan's block for this mob reaches others as well, the hop must reach
            // every one of them from where it lands, or it would cost a reposition the plan does not (killer560,
            // 2026-10-15: "the least amount of total repositions in the room").
            if (hop != null && !hopKeepsPlan(client, hop, target)) {
                hop = null;
            }
            if (hop != null) {
                action = hop.what() + " (" + cachedHops + " hop(s) ~" + hypeTicks + " ticks vs etherwarp ~" + etherTicks + ")";
                if (aimAndHold(client, player, AutoClearConfig.Weapon.HYPERION.itemId(), "Hyperion", hop, Phase.HOP)) {
                    hopTicks = 0;
                    hopStart = feet;
                    hopLook = TeleportUtils.getLook(hop.yaw(), hop.pitch());
                    hopLanded = false;
                    LOGGER.info("[AutoClear] hop toward {} at {}: {} hop(s) ~{} ticks vs etherwarp ~{} ticks", targetName,
                            target.blockPosition().toShortString(), cachedHops, hypeTicks, etherTicks);
                }
                return;
            }
        }
        BlockPos planned = coverSpotFor(target);
        BlockPos spot = planned != null ? planned : WeaponReach.standSpot(client.level, player, box, cfg.getWeapon());
        if (planned != null && BlockPos.containing(feet).below().equals(planned)) {
            // The room's plan is rebuilt at most every COVER_REPLAN_TICKS while a mob walks, so on the tick he lands on
            // the planned block it can still name that block for a mob that has walked out of its reach. Skipping the mob
            // there cost Balcony 8 s on 2026-10-19 (22:14:39-46: skipped, 46 ticks idle, two roams, back to this block).
            // A block that reaches it NOW goes first; only with none is the mob skipped as before.
            BlockPos fresh = WeaponReach.standSpot(client.level, player, box, cfg.getWeapon());
            coverIds = null;   // the plan is stale: rebuilt on the next decision
            if (fresh != null && !fresh.equals(planned)) {
                LOGGER.info("[AutoClear] {} walked out of reach of the planned block {} - etherwarping to {} instead of"
                        + " skipping it", targetName, planned.toShortString(), fresh.toShortString());
                planned = null;
                spot = fresh;
            }
        }
        if (spot == null) {
            skippedMobs.add(targetId);
            ModChat.log(CHAT, ModChat.bad("Skipping "), ModChat.value(targetName),
                    ModChat.dim(" - no block to stand on within " + cfg.getWeapon().label() + " reach of it"));
            targetId = -1;
            return;
        }
        if (BlockPos.containing(feet).below().equals(spot)) {
            // Standing on the spot already and still out of reach (the mob moved): skip rather than loop.
            LOGGER.info("[AutoClear] skipped {} at {}: standing on {} and no aim from here reaches it", targetName,
                    target.blockPosition().toShortString(), spot.toShortString());
            skippedMobs.add(targetId);
            targetId = -1;
            return;
        }
        if (walkInsteadOfTrip(client, player, spot, feetAt -> reaches(cfg.getWeapon(), feetAt, box),
                targetName + " at " + target.blockPosition().toShortString())) {
            return;
        }
        final BlockPos goal = spot;
        action = "etherwarp toward " + targetName + " (~" + etherTicks + " ticks vs hops "
                + (cachedHops < 0 ? "none" : "~" + hypeTicks) + ")";
        startTrip(client, player, () -> {
            ClearExecutor.etherPathFast(goal, null);
            return true;
        });
        if (phase == Phase.TRAVEL) {
            // Said (and counted) once the trip has really started: a body still turned back first (Phase.RELEASE) comes
            // through here again next tick, and used to log the same etherwarp twice.
            repositions++;
            LOGGER.info("[AutoClear] etherwarp toward {} at {}: stand {} (~{} ticks; hops {}){}", targetName,
                    target.blockPosition().toShortString(), goal.toShortString(), etherTicks, cachedHops,
                    planned != null ? " - the plan's block reaching " + coverCountFor(target) + " mob(s)"
                            : " - nearest block to it");
            double toGoal = feet.distanceTo(new Vec3(goal.getX() + 0.5, goal.getY() + 1.0, goal.getZ() + 0.5));
            int goalWarps = Math.max(1, (int) Math.ceil(toGoal / ClearExecutor.hopRange()));
            String whyNoBat;
            if (weapon == AutoClearConfig.Weapon.SPIRIT_SCEPTRE) {
                whyNoBat = cachedAttack == null ? "no bat from here reaches it (no aim within "
                        + WeaponReach.SCEPTRE_PLAN_RANGE + " blocks whose 6-block blast covers it)"
                        : "etherwarping onto " + goal.toShortString() + " and firing down is faster than the bat from here"
                        + " (~" + WeaponReach.batTicks(client.level, player, player.getEyePosition(), cachedAttack)
                        + " ticks)";
            } else {
                whyNoBat = cachedBat == null ? (sceptreSlot(player) < 0 ? "no Spirit Sceptre in the hotbar"
                        : "no sceptre bat from here reaches it") : "the bat from here (~" + cachedBatTicks
                        + " ticks) is not faster than the etherwarp (~" + (etherTicks + HYPERION_KILL_TICKS) + ")";
            }
            BatLog.LOGGER.info(String.format(Locale.US, "[Bat] decision for %s id %d at %s: etherwarp%s onto %s (%.1f blocks,"
                            + " %d warp(s), ~%d ticks; weapon %s, %.1f blocks from it) - not a bat: %s", targetName, targetId,
                    target.blockPosition().toShortString(), goalWarps == 1 ? " (a one-tap warp)" : "", goal.toShortString(),
                    toGoal, goalWarps, ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * goalWarps, weapon,
                    Math.sqrt(box.distanceToSqr(player.getEyePosition())), whyNoBat));
        }
    }

    /** One {@code [Bat] decision} line for a bat Auto Clear chose: the mob, the aim, the distance and the flight. */
    private static void logBatDecision(Entity target, AABB box, WeaponReach.Aim aim, int flight, String why) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        TeleportUtils.Rotation direct = TeleportUtils.getDirection(eye, box.getCenter());
        BatLog.LOGGER.info(String.format(Locale.US, "[Bat] decision for %s id %d at %s: bat from here (%s), look %.2f/%.2f,"
                        + " off the mob by %+.1f/%+.1f deg, %.1f blocks, flight ~%d ticks at %.2f b/t - %s", targetName,
                target.getId(), target.blockPosition().toShortString(), aim.what(), aim.yaw(), aim.pitch(),
                Mth.wrapDegrees(aim.yaw() - direct.yaw()), aim.pitch() - direct.pitch(), eye.distanceTo(box.getCenter()),
                flight, WeaponReach.BAT_BLOCKS_PER_TICK, why));
    }

    // ---- bats when mana runs low (2026-10-19 log: the bar read NOT ENOUGH MANA 19 times between 22:15:21 and 22:15:56,
    // and two of Altar's etherwarp trips stalled on it - 22:15:26-28 and 22:15:35-37, each ending in "warp never landed"
    // and a second plan; the held sceptre use sends a bat every 4 ticks while the first is still ~20 ticks from the mob,
    // 168 mana each by that log's action bar) ----

    /** Below this share of his maximum mana, one bat at a time per mob: the next waits until the last should have hit. */
    static final double LOW_MANA_SHARE = 0.25;
    /** After a bat's predicted flight, this long for its hit to reach the client before another is sent. */
    static final int BAT_HIT_GRACE_TICKS = 2;

    /** Flight of the in-place sceptre aim for the current plan key, -1 not worked out. */
    private static int cachedAttackFlight = -1;
    /** Flight of the bat the use key is about to send (set before each sceptre hold). */
    private static int holdingBatFlight = -1;
    /** The last bat of ours: the ClearExecutor tick it left, its mob, its predicted flight. */
    private static long batSentTick = Long.MIN_VALUE;
    private static int batTarget = -1;
    private static int batFlight = 0;
    private static int itemUsesSeen = -1;
    private static int manaWaitLoggedFor = -1;

    /** Each running tick: a bare use that left while our sceptre use was held is our bat (the last tick sent it). */
    private static void noteBatSent(LocalPlayer player) {
        int uses = com.killer560.hub.util.RightClick.itemUsesSent();
        if (uses != itemUsesSeen) {
            if (itemUsesSeen >= 0 && useHeld && targetId >= 0
                    && ItemIdentity.matches(player.getMainHandItem(), AutoClearConfig.Weapon.SPIRIT_SCEPTRE.itemId())) {
                batSentTick = ClearExecutor.clientTick() - 1;
                batTarget = targetId;
                batFlight = Math.max(1, holdingBatFlight);
                BatLog b = batLogs.get(targetId);
                Seen s = seenMobs.get(targetId);
                batLogs.put(targetId, b == null ? new BatLog(1, batSentTick, batFlight, s == null ? -1 : s.health(),
                        com.killer560.hub.util.ServerLag.mark(), Long.MAX_VALUE) : b.plusOne(batSentTick, batFlight));
                if (b == null) {
                    afterFirstBat(player, s);
                }
            }
            itemUsesSeen = uses;
        }
    }

    // ---- one bat, one kill (killer560, 2026-10-20: "If the sceptre's damage ... is enough to kill a mob in one tap, Auto
    // Clear should etherwarp right after the first bat is sent, instead of waiting for the bat to land") ----

    /** After a doomed mob's bat is due, this long for its death to reach the client before it is called a survivor. */
    static final int DOOM_CONFIRM_TICKS = 10;
    /** The share of the Intelligence floor ({@link WeaponReach#batDamageFloor}) a mob's health must be under. The floor
     *  ignores every bonus, so it is already low; this is for what it cannot know (a mob that resists magic). */
    static final double ONE_TAP_FLOOR_SHARE = 0.8;

    /** Our bats at one mob: how many, the tick and flight of the first, its tag health then, a ServerLag mark, and the
     *  earliest tick the second could land ({@code Long.MAX_VALUE} with one). */
    private record BatLog(int count, long first, int flight, double health, long lag, long secondLands) {
        BatLog plusOne(long sent, int itsFlight) {
            return new BatLog(count + 1, first, flight, health, lag, count == 1 ? sent + itsFlight : secondLands);
        }
    }

    /** A mob whose one bat is trusted to kill it: when the bat left, its flight, when to move on, its health. */
    private record Flight(long sent, int flight, long moveOn, double health, long lag, String name) {
    }

    private static final java.util.Map<Integer, BatLog> batLogs = new java.util.HashMap<>();
    /** Mobs left to their bat: not targeted until they die or the bat should long have hit (then shot again). */
    private static final java.util.Map<Integer, Flight> doomed = new java.util.HashMap<>();
    private static final Set<Integer> oneTapLogged = new HashSet<>();
    /** A trusted bat did not kill it: never trusted again this run. */
    private static final Set<Integer> oneTapFailed = new HashSet<>();
    /**
     * Tag healths of mobs that died to exactly ONE of our bats, inside its flight: each proves a bat does at least that
     * much (to this mob kind, in this run's gear). Kept for the game session; a survivor drops every figure at or above
     * its health.
     */
    private static final List<Double> oneBatKills = new java.util.ArrayList<>();
    private static int oneTapMoves = 0;

    /** For the testkit: mobs left to a trusted bat (Auto Clear moved on before it landed), ever (only goes up). */
    public static int oneTapMoves() {
        return oneTapMoves;
    }

    /** The bat damage proven by kills: the second highest one-bat kill (one fluke - a teammate's hit landing with ours -
     *  cannot set it alone), -1 with fewer than two. */
    static double learnedBatFloor() {
        if (oneBatKills.size() < 2) {
            return -1;
        }
        List<Double> sorted = new java.util.ArrayList<>(oneBatKills);
        sorted.sort(null);
        return sorted.get(sorted.size() - 2);
    }

    private record OneTapCall(boolean sure, String why) {
    }

    /** Whether one bat kills a mob with {@code health}: under the kill-proven floor, or under the Intelligence floor. */
    private static OneTapCall oneTapCall(int id, double health) {
        double learned = learnedBatFloor();
        double intFloor = WeaponReach.batDamageFloor(com.killer560.hub.playerstats.PlayerStatsFeature.manaMaxNow());
        String figures = String.format(Locale.US, "tag health %s; kill-proven bat damage %s (%d one-bat kill(s));"
                        + " Intelligence floor %s (x%.1f)", health < 0 ? "none" : String.format(Locale.US, "%.0f", health),
                learned < 0 ? "none" : String.format(Locale.US, "%.0f", learned), oneBatKills.size(),
                intFloor < 0 ? "none (no mana figure)" : String.format(Locale.US, "%.0f", intFloor), ONE_TAP_FLOOR_SHARE);
        if (oneTapFailed.contains(id)) {
            return new OneTapCall(false, "a trusted bat already failed to kill it - " + figures);
        }
        if (health <= 0) {
            return new OneTapCall(false, figures);
        }
        if (learned > 0 && health <= learned) {
            return new OneTapCall(true, figures);
        }
        if (intFloor > 0 && health <= intFloor * ONE_TAP_FLOOR_SHARE) {
            return new OneTapCall(true, figures);
        }
        return new OneTapCall(false, figures);
    }

    /** The first bat at the target just left: decide (and log, once per mob) whether to leave the mob to it. */
    private static void afterFirstBat(LocalPlayer player, Seen s) {
        AutoClearConfig.OneTap mode = AutoClearConfig.getInstance().getOneTap();
        if (mode == AutoClearConfig.OneTap.OFF) {
            return;
        }
        OneTapCall call = oneTapCall(targetId, s == null ? -1 : s.health());
        if (oneTapLogged.add(targetId)) {
            LOGGER.info("[AutoClear] one-tap: {} at {} - {} ({})", targetName, s == null ? "?" : fmt(s.pos()),
                    call.sure() ? (mode == AutoClearConfig.OneTap.AT_ONCE ? "trusting the bat, moving on now"
                            : "trusting the bat, moving on when it lands (~" + batFlight + " ticks)")
                            : "not sure, staying on it", call.why());
        }
        if (!call.sure()) {
            return;
        }
        long moveOn = mode == AutoClearConfig.OneTap.AT_ONCE ? batSentTick : batSentTick + batFlight;
        doomed.put(targetId, new Flight(batSentTick, batFlight, moveOn, s == null ? -1 : s.health(),
                com.killer560.hub.util.ServerLag.mark(), targetName));
        if (mode == AutoClearConfig.OneTap.AT_ONCE) {
            leaveToBat(Minecraft.getInstance());
        }
    }

    /** The target is left to its bat: the use goes up and the next decision is a fresh one, for another mob. */
    private static void leaveToBat(Minecraft client) {
        oneTapMoves++;
        releaseUse(client);
        targetId = -1;
        targetName = "";
        planKey = Long.MIN_VALUE;
        if (phase == Phase.ATTACK) {
            phase = Phase.IDLE;
        }
    }

    /**
     * A tick of the one-tap bookkeeping, before a target is picked: a doomed mob whose bat should long have hit and that
     * is still alive is shot again (never trusted again); with After Impact, the target's bat still in flight keeps the
     * body on the mob (the Guided Bat follows the aim - wiki) and nothing else is done.
     * @return true while the bat in flight owns this tick
     */
    private static boolean oneTapStep(Minecraft client, LocalPlayer player) {
        long now = ClearExecutor.clientTick();
        for (var it = doomed.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Flight f = entry.getValue();
            Entity e = client.level.getEntity(entry.getKey());
            if (e == null || !e.isAlive()) {
                it.remove();   // died (counted by mobDied) or left the view (remembered by observeMobs)
                continue;
            }
            long due = f.sent() + f.flight() + BAT_HIT_GRACE_TICKS + DOOM_CONFIRM_TICKS
                    + com.killer560.hub.util.ServerLag.ticksSince(f.lag());
            if (now > due) {
                it.remove();
                oneTapFailed.add(entry.getKey());
                if (f.health() > 0) {
                    oneBatKills.removeIf(v -> v >= f.health());
                }
                LOGGER.info("[AutoClear] one-tap: {} is still alive {} ticks after its bat (flight ~{}) - shooting it"
                        + " again, and no bat damage at or over {} is trusted any more", f.name(), now - f.sent(),
                        f.flight(), f.health() < 0 ? "?" : String.format(Locale.US, "%.0f", f.health()));
            }
        }
        Flight mine = targetId < 0 ? null : doomed.get(targetId);
        if (mine == null) {
            return false;
        }
        if (now >= mine.moveOn()) {
            LOGGER.info("[AutoClear] one-tap: {}'s bat is due ({} of ~{} ticks) - moving on", targetName,
                    now - mine.sent(), mine.flight());
            leaveToBat(client);
            return false;
        }
        Entity e = client.level.getEntity(targetId);
        releaseUse(client);
        bodyWanted = true;
        action = String.format(Locale.US, "bat in flight at %s (%d of ~%d ticks, one tap)", targetName,
                now - mine.sent(), mine.flight());
        if (e != null) {
            Vec3 eye = player.getEyePosition();
            WeaponReach.Aim aim = WeaponReach.sceptreAim(client.level, player, eye, batBox(e, eye));
            if (aim != null) {
                BODY.turn(player, player.getYRot() + Mth.wrapDegrees(aim.yaw() - player.getYRot()),
                        Mth.clamp(aim.pitch(), -90f, 90f));
            }
        }
        return true;
    }

    /**
     * A mob seen dying (observeMobs): a death after exactly one of our bats, inside its flight, proves the bat's damage
     * (its tag health then); a doomed mob's death is counted here, since the target had already moved on from it.
     */
    private static void mobDied(int id, Seen s) {
        BatLog b = batLogs.remove(id);
        Flight f = doomed.remove(id);
        long now = ClearExecutor.clientTick();
        // One bat did it: the only one sent, or a death before the second could have landed (the held use repeats every
        // 4 ticks, so a second bat is usually in the air when the first hits).
        if (b != null && (b.count() == 1 || now < b.secondLands()) && b.health() > 0 && now >= b.first()
                && now <= b.first() + b.flight() + BAT_HIT_GRACE_TICKS + DOOM_CONFIRM_TICKS
                + com.killer560.hub.util.ServerLag.ticksSince(b.lag())) {
            oneBatKills.add(b.health());
            LOGGER.info(String.format(Locale.US, "[AutoClear] one-tap: %s died %d ticks after our one bat - a bat does at"
                            + " least %.0f; trusted from %s (%d one-bat kill(s) seen)", s.name(), now - b.first(),
                    b.health(), learnedBatFloor() < 0 ? "the second such kill"
                            : String.format(Locale.US, "%.0f", learnedBatFloor()), oneBatKills.size()));
        }
        if (f != null && id != targetId) {
            mobsKilled++;
            if (s.room() != null && s.room().equals(roomName)) {
                killsInRoom++;
            }
            LOGGER.info("[AutoClear] one-tap: {} died {} ticks after its bat (flight ~{}) ({} starred kill(s) this run)",
                    f.name(), now - f.sent(), f.flight(), mobsKilled);
        }
    }

    /** Doomed mobs of {@code room} still alive (the room's clear is waiting on their bats). */
    private static String doomedIn(Minecraft client, DungeonLayout layout, int room) {
        for (var entry : doomed.entrySet()) {
            Entity e = client.level.getEntity(entry.getKey());
            if (e != null && e.isAlive() && inRoom(layout, room, e)) {
                return entry.getValue().name();
            }
        }
        return null;
    }

    // ---- leading a moving mob ----

    private static final Set<Integer> leadLogged = new HashSet<>();

    /**
     * The box a bat is aimed at: with Predictive Bats, the mob's box moved to where its smoothed velocity takes it while
     * the bat flies there ({@link WeaponReach#leadOffset}); else, or for a mob standing still, its box as it is.
     */
    private static AABB batBox(Entity target, Vec3 eye) {
        AABB box = target.getBoundingBox();
        if (!AutoClearConfig.getInstance().isPredictiveBats()) {
            return box;
        }
        Seen s = seenMobs.get(target.getId());
        if (s == null || s.vel() == null) {
            return box;
        }
        Vec3 off = WeaponReach.leadOffset(eye, box.getCenter(), s.vel());
        if (off.lengthSqr() == 0.0) {
            return box;
        }
        if (leadLogged.add(target.getId())) {
            LOGGER.info(String.format(Locale.US, "[AutoClear] leading the bat at %s: moving %.2f b/t, aiming %.1f blocks"
                    + " ahead of it", target.getName().getString(), s.vel().horizontalDistance(), off.length()));
        }
        return box.move(off);
    }

    /** {@link WeaponReach#sceptreAim} at the led box, else (nothing reaches the spot ahead) at the mob as it stands. */
    private static WeaponReach.Aim leadAim(Minecraft client, LocalPlayer player, Entity target, AABB box) {
        Vec3 eye = player.getEyePosition();
        AABB led = batBox(target, eye);
        WeaponReach.Aim aim = led.equals(box) ? null : WeaponReach.sceptreAim(client.level, player, eye, led);
        return aim != null ? aim : WeaponReach.sceptreAim(client.level, player, eye, box);
    }

    /**
     * With mana under {@link #LOW_MANA_SHARE} of his maximum (the action bar, {@link
     * com.killer560.hub.playerstats.PlayerStatsFeature#manaNow}), a bat is not sent at a mob while the last one sent at it
     * is still due to land: the use key goes up, the body stays on the aim, and the next bat goes once the first should
     * have hit. Above it, or with no mana figure seen, the held use repeats every 4 ticks as always.
     * @return true while it waits this tick
     */
    private static boolean waitForBatInFlight(Minecraft client) {
        long mana = com.killer560.hub.playerstats.PlayerStatsFeature.manaNow();
        long max = com.killer560.hub.playerstats.PlayerStatsFeature.manaMaxNow();
        if (mana < 0 || max <= 0 || mana >= max * LOW_MANA_SHARE || batTarget != targetId) {
            return false;
        }
        long since = ClearExecutor.clientTick() - batSentTick;
        if (since > batFlight + BAT_HIT_GRACE_TICKS) {
            return false;
        }
        releaseUse(client);
        bodyWanted = true;
        action = String.format(Locale.US, "waiting for the bat at %s (%d of ~%d ticks; mana %d/%d is low)", targetName,
                since, batFlight, mana, max);
        if (manaWaitLoggedFor != targetId) {
            manaWaitLoggedFor = targetId;
            LOGGER.info(String.format(Locale.US, "[AutoClear] mana %d/%d is under %.0f%%: one bat at a time at %s - the next"
                    + " goes once this one should have hit (~%d ticks)", mana, max, LOW_MANA_SHARE * 100, targetName,
                    batFlight + BAT_HIT_GRACE_TICKS));
        }
        return true;
    }

    // ---- bat travel against etherwarping (killer560, 2026-10-19) ----

    /** After an etherwarp chain with the Hyperion, the in-place Wither Impact still needs its turn tick before the use. */
    static final int HYPERION_KILL_TICKS = 1;

    /** The sceptre bat from where he stands for the current plan key: its aim, and its cost in ticks (-2 not worked
     *  out yet, -1 none: no sceptre in the hotbar or no bat from here reaches). */
    private static WeaponReach.Aim cachedBat = null;
    private static int cachedBatTicks = -2;
    /** Sceptre weapon: whether etherwarping onto the mob's block and firing down beats the bat from here (null: not
     *  worked out for this plan key). */
    private static Boolean cachedPreferEther = null;

    /** The hotbar slot of a Spirit Sceptre (any star level), quietly: -1 when there is none. */
    private static int sceptreSlot(LocalPlayer player) {
        for (int slot = 0; slot <= 8; slot++) {
            if (ItemIdentity.matches(player.getInventory().getItem(slot), AutoClearConfig.Weapon.SPIRIT_SCEPTRE.itemId())) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Hyperion as the weapon, the mob out of the blast: whether a Spirit Sceptre bat from where he stands kills it
     * sooner than the move would. The bat costs a swap tick when the sceptre is not in hand, its launch tick and its
     * flight ({@link WeaponReach#batTicks}, {@link WeaponReach#BAT_BLOCKS_PER_TICK}); the etherwarp costs the chain
     * ({@code etherTicks}) plus {@link #HYPERION_KILL_TICKS}; hops cost {@code hypeTicks} (a hop landing in reach kills).
     * Strictly cheaper only, so a tie keeps the Hyperion. Worked out once per plan key.
     */
    private static boolean hyperionBatWins(Minecraft client, LocalPlayer player, Entity target, AABB box, int etherTicks,
                                           int hypeTicks) {
        if (cachedBatTicks == -2) {
            int slot = sceptreSlot(player);
            cachedBat = slot < 0 ? null : leadAim(client, player, target, box);
            cachedBatTicks = cachedBat == null ? -1
                    : WeaponReach.batTicks(client.level, player, player.getEyePosition(), cachedBat)
                    + (player.getInventory().getSelectedSlot() == slot ? 0 : 1);
            if (cachedBat != null) {
                boolean wins = cachedBatTicks < etherTicks + HYPERION_KILL_TICKS && cachedBatTicks < hypeTicks;
                LOGGER.info("[AutoClear] cost for {} at {}: bat from here ~{} ticks ({} b/t), etherwarp ~{} ticks, hops {}"
                                + " - {}", targetName, target.blockPosition().toShortString(), cachedBatTicks,
                        WeaponReach.BAT_BLOCKS_PER_TICK, etherTicks + HYPERION_KILL_TICKS,
                        hypeTicks == Integer.MAX_VALUE ? "none" : "~" + hypeTicks,
                        wins ? "the Spirit Sceptre" : "the Hyperion");
                BatLog.LOGGER.info(String.format(Locale.US, "[Bat] decision for %s id %d at %s (Hyperion weapon): %s - bat"
                                + " from here ~%d ticks (swap %d, launch %d, %.1f blocks at %.2f b/t) vs etherwarp ~%d, hops %s",
                        targetName, target.getId(), target.blockPosition().toShortString(),
                        wins ? "a sceptre bat" : "no bat", cachedBatTicks,
                        player.getInventory().getSelectedSlot() == slot ? 0 : 1, WeaponReach.BAT_LAUNCH_TICKS,
                        Math.sqrt(box.distanceToSqr(player.getEyePosition())), WeaponReach.BAT_BLOCKS_PER_TICK,
                        etherTicks + HYPERION_KILL_TICKS, hypeTicks == Integer.MAX_VALUE ? "none" : "~" + hypeTicks));
            }
        }
        return cachedBat != null && cachedBatTicks < etherTicks + HYPERION_KILL_TICKS && cachedBatTicks < hypeTicks;
    }

    /**
     * Spirit Sceptre as the weapon, a bat from here reaching the mob: whether etherwarping onto the plan's block for it
     * (else {@link WeaponReach#standSpot}) and firing down ({@link WeaponReach#batDownTicks}) is strictly faster than the
     * bat's flight from here. Worked out once per plan key; only a bat longer than the cheapest possible one-warp trip
     * is ever looked at, so a near mob never costs the block search.
     */
    private static boolean sceptreSlowerThanEther(Minecraft client, LocalPlayer player, Entity target, AABB box,
                                                  AutoClearConfig cfg) {
        if (cachedPreferEther != null) {
            return cachedPreferEther;
        }
        cachedPreferEther = false;
        Vec3 eye = player.getEyePosition();
        int batTicks = WeaponReach.batTicks(client.level, player, eye, cachedAttack);
        int down = WeaponReach.batDownTicks(player);
        if (batTicks <= ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP + down) {
            return false;
        }
        BlockPos planned = coverSpotFor(target);
        BlockPos spot = planned != null ? planned : WeaponReach.standSpot(client.level, player, box, cfg.getWeapon());
        if (spot == null || BlockPos.containing(player.position()).below().equals(spot)) {
            return false;
        }
        double d = player.position().distanceTo(new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5));
        int warps = Math.max(1, (int) Math.ceil(d / ClearExecutor.hopRange()));
        int ether = ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * warps + down;
        cachedPreferEther = ether < batTicks;
        LOGGER.info("[AutoClear] cost for {} at {}: bat from here ~{} ticks ({} b/t), etherwarp onto {} and fire down ~{}"
                        + " ticks - {}", targetName, target.blockPosition().toShortString(), batTicks,
                WeaponReach.BAT_BLOCKS_PER_TICK, spot.toShortString(), ether,
                cachedPreferEther ? "etherwarp" : "the bat from here");
        BatLog.LOGGER.info(String.format(Locale.US, "[Bat] decision for %s id %d at %s (sceptre weapon): %s - bat from here"
                        + " ~%d ticks (%.1f blocks at %.2f b/t) vs etherwarp%s onto %s (%.1f blocks, %d warp(s)) and fire down"
                        + " ~%d ticks (fixed %d + %d a warp + down %d)", targetName, target.getId(),
                target.blockPosition().toShortString(), cachedPreferEther ? "etherwarp" : "bat", batTicks,
                Math.sqrt(box.distanceToSqr(eye)), WeaponReach.BAT_BLOCKS_PER_TICK, warps == 1 ? " (one-tap warp)" : "",
                spot.toShortString(), d, warps, ether, ETHER_FIXED_TICKS, ETHER_TICKS_PER_WARP, down));
        return cachedPreferEther;
    }

    // ---- walking against etherwarping (killer560, 2026-10-19: "make sure auto clear knows it can run and jump and
    // stuff if needed if that is somehow faster") ----

    /** A goal further than this in a straight line is never walked (an etherwarp chain is always faster there). */
    static final double WALK_MAX_BLOCKS = 20.0;

    /** The walk running (Phase.WALK): for a mob (ends the moment the weapon reaches it) or to a roam spot. */
    private static boolean walkForMob = false;
    private static String walkWhat = "";
    /** A walk toward this mob got stuck: etherwarp to it from now on. */
    private static int walkOffFor = -1;
    /** A roam walk in this room got stuck: roam by etherwarp for the rest of the room. */
    private static boolean walkOffRoam = false;
    /** The last walk-or-etherwarp decision, so it is worked out (and logged) once per plan: start block, goal, what. */
    private static String walkDecisionKey = null;
    private static ShortWalk.Plan walkDecisionPlan = null;
    private static int walks = 0;

    /** For the testkit: walks Auto Clear chose over an etherwarp, ever (only goes up). */
    public static int walks() {
        return walks;
    }

    /**
     * Whether walking to {@code spot} (the block to stand ON) beats the Interactive Map's etherwarp chain there, and if so
     * starts the walk (Phase.WALK). Both estimates are in ticks in the style of the others: the chain is
     * {@link #ETHER_FIXED_TICKS} + {@link #ETHER_TICKS_PER_WARP} a warp (straight distance over the hop range, at least
     * one); the walk is {@link ShortWalk#plan}'s - {@link ShortWalk#START_TICKS}, the way's length up to the first
     * point from which {@code doneAt} accepts his feet (the whole way when null) at his real sprinting top speed, and
     * {@link ShortWalk#JUMP_TICKS} per step up. Strictly faster only, so a tie etherwarps as before. Worked out and logged
     * once per plan (his block, the goal and what it is for).
     * @return true when a walk was started
     */
    private static boolean walkInsteadOfTrip(Minecraft client, LocalPlayer player, BlockPos spot,
                                             java.util.function.Predicate<Vec3> doneAt, String what) {
        return walkInsteadOfTrip(client, player, spot, doneAt, what, false);
    }

    /** As above; {@code forDoor}: the walk is to a wither door, ending where {@code doneAt} says the door is in reach
     *  (since 2026-10-20), and counted as a trip to it (tripEnded) when it arrives. */
    private static boolean walkInsteadOfTrip(Minecraft client, LocalPlayer player, BlockPos spot,
                                             java.util.function.Predicate<Vec3> doneAt, String what, boolean forDoor) {
        AutoClearConfig cfg = AutoClearConfig.getInstance();
        if (!cfg.isWalkWhenFaster() || !player.onGround() || !ShortWalk.mixinApplied()
                || (forDoor ? walkOffDoor : doneAt != null ? walkOffFor == targetId : walkOffRoam)) {
            return false;
        }
        Vec3 feet = player.position();
        if (doneAt != null && doneAt.test(feet)) {
            return false;   // already where a walk would end: walking cannot help (the aim from here is what failed)
        }
        Vec3 goalFeet = new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
        double straight = feet.distanceTo(goalFeet);
        int warps = Math.max(1, (int) Math.ceil(straight / ClearExecutor.hopRange()));
        int etherTicks = ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * warps;
        String key = BlockPos.containing(feet).asLong() + "|" + spot.asLong() + "|" + what;
        if (!key.equals(walkDecisionKey)) {
            walkDecisionKey = key;
            walkDecisionPlan = null;
            if (straight > WALK_MAX_BLOCKS) {
                LOGGER.info(String.format(Locale.US, "[AutoClear] move to %s: etherwarp ~%d ticks (%d warp(s)); walk not"
                        + " planned - %.1f blocks away, over %.0f", what, etherTicks, warps, straight, WALK_MAX_BLOCKS));
            } else {
                long t0 = System.nanoTime();
                ShortWalk.Plan plan = ShortWalk.plan(client.level, player, spot.above(), doneAt);
                double ms = (System.nanoTime() - t0) / 1e6;
                boolean walk = plan != null && plan.ticks() < etherTicks;
                walkDecisionPlan = walk ? plan : null;
                LOGGER.info(String.format(Locale.US, "[AutoClear] move to %s: walk %s vs etherwarp ~%d ticks (%d warp(s),"
                                + " %.1f blocks straight) - %s (%.1f ms)", what,
                        plan == null ? "none (no way on foot)" : "~" + plan.ticks() + " ticks (" + plan.describe() + ")",
                        etherTicks, warps, straight, walk ? "walking" : "etherwarp", ms));
            }
        }
        ShortWalk.Plan plan = walkDecisionPlan;
        if (plan == null) {
            return false;
        }
        walkDecisionKey = null;   // taken: the next decision is a fresh one
        walkDecisionPlan = null;
        walkForMob = doneAt != null && !forDoor;
        walkForDoor = forDoor;
        walkWhat = what;
        walkDoneAt = doneAt;
        ShortWalk.start(plan, "Auto Clear");
        phase = Phase.WALK;
        bodyWanted = true;   // the body is turned along the way from the next tick on, the camera held
        travelStarted = true;
        walks++;
        action = "walking to " + what + " (~" + plan.ticks() + " ticks vs etherwarp ~" + etherTicks + ")";
        if (walkForMob || walkForDoor) {
            stepWalk(client, player);   // the first keys go in this same tick's input, not the next
        }
        return true;
    }

    private static java.util.function.Predicate<Vec3> walkDoneAt = null;
    /** The walk running is to a wither door (it ends the tick the door is in reach). */
    private static boolean walkForDoor = false;

    /**
     * One tick of Phase.WALK, driven by his real position ({@link ShortWalk#tick}): the body turned toward the way with
     * the camera held (as for every other aim), the keys through {@code PathWalkInputMixin}. A walk for a mob ends the
     * tick he stands where the weapon reaches it (the attack goes the same tick); a walk that gets stuck or knocked off
     * its way ends and the same goal is etherwarped to instead.
     * @return true while the walk owns this tick
     */
    private static boolean stepWalk(Minecraft client, LocalPlayer player) {
        if (!ShortWalk.isActive()) {
            phase = Phase.IDLE;
            planKey = Long.MIN_VALUE;
            return false;
        }
        if (walkForMob) {
            Entity target = targetId < 0 ? null : client.level.getEntity(targetId);
            if (target == null || !target.isAlive()) {
                endWalk("the mob is gone");
                phase = Phase.IDLE;
                planKey = Long.MIN_VALUE;
                travelStarted = false;
                return false;
            }
            if (player.onGround() && walkDoneAt != null && walkDoneAt.test(player.position())) {
                endWalk("in reach of " + targetName);
                phase = Phase.IDLE;
                planKey = Long.MIN_VALUE;
                travelStarted = false;
                return false;
            }
        }
        if (walkForDoor && walkDoneAt != null && player.onGround() && walkDoneAt.test(player.position())) {
            // The door is in reach: the walk has done what a trip to it does (doorTripDone), and the click goes now.
            endWalk("in reach of the wither door");
            phase = Phase.IDLE;
            planKey = Long.MIN_VALUE;
            walkForDoor = false;
            tripEnded(player, false);
            return false;
        }
        bodyWanted = true;
        float pitch = Mth.clamp(player.getXRot(), -90f, 90f);
        ShortWalk.Status status = ShortWalk.tick(client.level, player, yaw -> BODY.turn(player, yaw, pitch));
        if (status == ShortWalk.Status.WALKING) {
            return true;
        }
        boolean failed = status != ShortWalk.Status.ARRIVED;
        endWalk(status.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        phase = Phase.IDLE;
        planKey = Long.MIN_VALUE;
        if (failed) {
            if (walkForDoor) {
                walkOffDoor = true;
            } else if (walkForMob) {
                walkOffFor = targetId;
            } else {
                walkOffRoam = true;
            }
            LOGGER.info("[AutoClear] the walk to {} ended {} at {} - etherwarping instead", walkWhat, status, fmt(player.position()));
        }
        if (walkForMob) {
            travelStarted = false;
            return false;
        }
        tripEnded(player, failed);
        return false;
    }

    private static void endWalk(String why) {
        if (ShortWalk.isActive()) {
            ShortWalk.stop(why);
        }
    }

    /** Something else needs him (a screen, a route, a puzzle, a correction): the walk ends, the plan starts over. */
    private static void abandonWalk(String why) {
        if (phase != Phase.WALK) {
            return;
        }
        endWalk(why);
        phase = Phase.IDLE;
        planKey = Long.MIN_VALUE;
        roamTrip = false;   // its spot was counted when it was taken
        travelStarted = false;
    }

    private static void travelToRoom(Minecraft client, LocalPlayer player, DungeonLayout layout, int room) {
        releaseUse(client);
        if (!AutoClearUtils.canPath(layout)) {
            action = "waiting to land before the trip to " + roomName;
            return;
        }
        // A mob of the room last seen somewhere (it left the view while he was elsewhere): go where it was, not to the
        // room's nearest tile (killer560, 2026-10-20: "goes to the last known spot of a mob instead of searching the
        // whole room"). Not there on arrival, the roam forgets it (roamToKnown) and looks on as before.
        Known lastSeen = null;
        for (Known k : knownMobs(client, layout, room)) {
            if (k.lostId() >= 0 && (lastSeen == null || k.pos().distanceToSqr(player.position())
                    < lastSeen.pos().distanceToSqr(player.position()))) {
                lastSeen = k;
            }
        }
        if (lastSeen != null) {
            Vec3 p = lastSeen.pos();
            AABB box = new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.95, p.z + 0.3);
            BlockPos stand = WeaponReach.standSpot(client.level, player, box, AutoClearConfig.getInstance().getWeapon());
            if (stand == null) {
                stand = TeleportUtils.nearestEtherwarpable(BlockPos.containing(p));
            }
            if (stand != null && layout.roomAtWorld(stand.getX() + 0.5, stand.getZ() + 0.5) == room) {
                final BlockPos goal = stand;
                final Known k = lastSeen;
                action = "etherwarp to " + roomName + " (" + k.name() + " last seen there)";
                startTrip(client, player, () -> {
                    ClearExecutor.etherPathFast(goal, null);
                    return true;
                });
                if (phase == Phase.TRAVEL) {
                    LOGGER.info(String.format(Locale.US, "[AutoClear] trip to room %s: to %s's last known spot %s, standing"
                                    + " on %s (%.1f blocks away)", roomName, k.name(), BlockPos.containing(p).toShortString(),
                            goal.toShortString(), player.position().distanceTo(Vec3.atCenterOf(goal))));
                }
                return;
            }
        }
        int[] tiles = layout.tiles(room);
        int tile = tiles[0];
        double best = Double.MAX_VALUE;
        for (int t : tiles) {
            BlockPos c = DungeonLayout.cellCenter(t);
            double d = player.distanceToSqr(c.getX() + 0.5, player.getY(), c.getZ() + 0.5);
            if (d < best) {
                best = d;
                tile = t;
            }
        }
        final int chosen = tile;
        action = "etherwarp to " + roomName;
        LOGGER.info("[AutoClear] trip to room {} (tile {})", roomName, chosen);
        startTrip(client, player, () -> AutoClearUtils.pathToRoom(layout, room, chosen, 0));
    }

    /** Gives the body back first (a tick), then starts an Interactive Map trip. */
    private static void startTrip(Minecraft client, LocalPlayer player, java.util.function.BooleanSupplier trip) {
        if (BODY.isHeld()) {
            phase = Phase.RELEASE;
            return;
        }
        ClearExecutor.setExternalOwner(true);
        boolean started = trip.getAsBoolean();
        if (!started) {
            if (++pathFailures >= MAX_PATH_FAILURES) {
                giveUpRoom("could not start a trip there");
            }
            return;
        }
        travelStarted = true;
        phase = Phase.TRAVEL;
    }

    /**
     * Holds the weapon's use key along {@code aim}: the right item first (one swap a tick), then the body turned with the
     * camera held - and only on a LATER tick the use, so the movement packet has reported the rotation the use carries.
     * @return true once the use key is held
     */
    private static boolean aimAndHold(Minecraft client, LocalPlayer player, String itemId, String label,
                                      WeaponReach.Aim aim, Phase next) {
        int slot = ItemIdentity.findHotbarSlot(player, itemId);
        if (slot < 0) {
            stop("no " + label + " in the hotbar");
            return false;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            releaseUse(client);
            select(client, player, slot);
            return false;
        }
        bodyWanted = true;
        float yaw = player.getYRot() + Mth.wrapDegrees(aim.yaw() - player.getYRot());
        float pitch = Mth.clamp(aim.pitch(), -90f, 90f);
        if (!BODY.isHeld() || Math.abs(yaw - player.getYRot()) > 0.5f || Math.abs(pitch - player.getXRot()) > 0.5f) {
            releaseUse(client);
            BODY.turn(player, yaw, pitch);
            phase = Phase.IDLE;
            return false;
        }
        phase = next;
        holdUse(client);
        return true;
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

    /** {@code RouteExecutor.select}'s swap: through vanilla's own carried-item send, so it never goes out twice. */
    private static void select(Minecraft client, LocalPlayer player, int slot) {
        // stopQuietly restores his slot from whatever stopped it, END handlers included: util/HotbarSelect.
        com.killer560.hub.util.HotbarSelect.select(client, player, slot);
    }

    /**
     * While the use key is held with the body turned and the camera held, the block vanilla's held use acts on: the one
     * along the BODY's look - {@code RouteExecutor.heldUsePick}'s rule, from {@code autoroutes/mixin/HeldUsePickMixin}.
     * A secret block (chest, trapped chest, lever, skull) on that look - or under the crosshair while the body is not
     * held - is never the use's block: the pick becomes a miss there, so the weapon is used in the air as it would be
     * with nothing in the way, and the secret is left for the route (killer560, 2026-10-20: Auto Clear's held Hyperion
     * opened a chest beside a mob, and Auto Routes then waited for it in that room).
     */
    public static HitResult heldUsePick(Minecraft client) {
        if (!useHeld || !running || client.player == null || client.level == null) {
            return null;
        }
        LocalPlayer player = client.player;
        HitResult hit;
        if (BODY.isHeld()) {
            Vec3 eye = player.getEyePosition();
            Vec3 look = TeleportUtils.getLook(player.getYRot(), player.getXRot()).scale(player.blockInteractionRange());
            hit = client.level.clip(new ClipContext(eye, eye.add(look), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                    player));
        } else {
            hit = client.hitResult;
        }
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK
                && isSecretBlock(client.level.getBlockState(bh.getBlockPos()).getBlock())) {
            if (!bh.getBlockPos().equals(lastSecretMissed)) {
                lastSecretMissed = bh.getBlockPos().immutable();
                LOGGER.info("[AutoClear] held use: the look meets the secret {} at {} - used in the air instead, the "
                                + "secret is left for the route", client.level.getBlockState(bh.getBlockPos()).getBlock(),
                        bh.getBlockPos().toShortString());
            }
            return net.minecraft.world.phys.BlockHitResult.miss(hit.getLocation(), bh.getDirection(), bh.getBlockPos());
        }
        return BODY.isHeld() ? hit : null;
    }

    /** The last secret block a held use was turned away from (logged once per block). */
    private static BlockPos lastSecretMissed = null;

    /** A block a right click would take as a secret: chest, trapped chest (the mimic's), lever, skull. */
    static boolean isSecretBlock(net.minecraft.world.level.block.Block b) {
        return b == net.minecraft.world.level.block.Blocks.CHEST || b == net.minecraft.world.level.block.Blocks.TRAPPED_CHEST
                || b == net.minecraft.world.level.block.Blocks.LEVER
                || b == net.minecraft.world.level.block.Blocks.PLAYER_HEAD
                || b == net.minecraft.world.level.block.Blocks.PLAYER_WALL_HEAD;
    }

    /**
     * Whether a run of Auto Clear holds back every automatic secret click (Secret Aura, the Secret Triggerbot): a run is
     * going and no Auto Routes route is running (killer560, 2026-10-20: "Secret clicks are for Auto Routes running a
     * route, or for Auto Secret"). A secret taken while it kills is one the route through that room then has to find
     * gone; under a route, {@code RouteExecutor.secretAuraMayClick} decides as always.
     */
    public static boolean holdsSecretClicks() {
        return running && !com.killer560.hub.autoroutes.RouteExecutor.isRunning();
    }

    // =========================================================================================== corrections

    /** From {@code LiveMapPacketListenerMixin}, on the client thread, before vanilla applies the position. */
    public static void onServerPositionPacket() {
        Minecraft client = Minecraft.getInstance();
        posBeforePacket = client.player == null ? null : client.player.position();
        positionPackets++;
    }

    /**
     * A position packet he did not cause by the hop in flight is a server correction: say so, sound the alarm, and
     * plan again from wherever it put him. A trip's own packets are the Interactive Map runner's business (it checks
     * every landing itself); a packet that leaves him where he was is a rotation-only answer.
     */
    private static void checkPositionPackets(Minecraft client, LocalPlayer player) {
        // A correction during a trip is the Interactive Map runner's to judge: it reports it (chat line + alarm) and
        // plans the trip again from where he was put. Counted here too, so the run's "corrections N" is every one -
        // until 2026-10-07 these were missing from it (testkit 131-sim-auto-clear "correction" case).
        int executorCorrections = ClearExecutor.serverCorrections();
        if (executorCorrections != executorCorrectionsSeen) {
            int n = executorCorrections - executorCorrectionsSeen;
            executorCorrectionsSeen = executorCorrections;
            corrections += n;
            LOGGER.info("[AutoClear] server correction #{} during the trip ({}): the Interactive Map reported it and"
                    + " plans again from {} - carrying on", corrections, action, fmt(player.position()));
        }
        int packets = positionPackets;
        if (packets == positionPacketsSeen) {
            return;
        }
        positionPacketsSeen = packets;
        if (ClearExecutor.isBusy() || ClearExecutor.lastFastArrivalTick() == ClearExecutor.clientTick()) {
            // The trip's own landings, or a correction it judges itself (above). A trip that ended on its confirmed
            // landing THIS tick (finishArrivalEarly, asked by Auto Routes before this ran) is not busy any more, but the
            // packet just counted is still that landing, not a correction.
            return;
        }
        Vec3 at = player.position();
        Vec3 before = posBeforePacket;
        if (before != null && at.distanceTo(before) < 0.3) {
            return;
        }
        if (phase == Phase.HOP && hopStart != null && hopLook != null && onRay(hopStart, hopLook, at)) {
            hopLanded = true;
            return;
        }
        corrections++;
        say(ModChat.bad("Server correction"), ModChat.dim(String.format(Locale.US,
                " (moved %.1f blocks) - carrying on from here", before == null ? 0.0 : at.distanceTo(before))));
        ModSounds.playCorrectionAlarm();
        LOGGER.info("[AutoClear] server correction #{}: {} -> {} during {} - replanning", corrections,
                before == null ? "?" : fmt(before), fmt(at), phase);
        releaseUse(client);
        abandonWalk("server correction - planning again from where it put him");
        phase = Phase.IDLE;
        planKey = Long.MIN_VALUE;
    }

    /** Within 1.6 blocks of the dash line from {@code start} (two dashes long, for a second held use before landing). */
    private static boolean onRay(Vec3 start, Vec3 look, Vec3 at) {
        Vec3 rel = at.subtract(start);
        double along = rel.dot(look);
        if (along < -0.5 || along > WeaponReach.HYPERION_TELEPORT * 2 + 1.0) {
            return false;
        }
        Vec3 closest = start.add(look.scale(Math.max(0.0, along)));
        // Horizontal distance only: a dash settles at the floor or ceiling height it fits at.
        double dx = at.x - closest.x;
        double dz = at.z - closest.z;
        return dx * dx + dz * dz <= 1.6 * 1.6;
    }

    // =========================================================================================== helpers

    private static List<Entity> starredIn(Minecraft client, DungeonLayout layout, int room) {
        List<Entity> out = new java.util.ArrayList<>();
        for (Entity mob : MobEspFeature.starredMobs(client)) {
            if (skippedMobs.contains(mob.getId()) || doomed.containsKey(mob.getId())) {
                continue;
            }
            if (inRoom(layout, room, mob)) {
                out.add(mob);
            }
        }
        return out;
    }

    // =========================================================================================== fewest repositions

    /** The room's plan: the standing blocks to take, in order, and which mob ids each reaches. */
    private static String coverKey = null;
    private static String coverIds = null;
    private static int coverAge = 0;
    private static final int COVER_REPLAN_TICKS = 10;
    private static List<BlockPos> coverSpots = List.of();
    private static List<Set<Integer>> coverMobs = List.of();
    /** Etherwarps toward mobs, ever (each is a reposition). */
    private static int repositions = 0;

    /** For the testkit: etherwarps toward mobs so far (only goes up). */
    public static int repositions() {
        return repositions;
    }

    /**
     * killer560, 2026-10-15: "For rooms that need repositioning make sure that it always chooses the repositioning path
     * that will require the least amount of total repositions in the room." The standing blocks around the room's starred
     * mobs, each with the mobs its weapon reaches from there, and the fewest of them that reach every mob
     * ({@link RepositionPlanner}). Planned again whenever a mob dies or moves a block.
     */
    private static void planRepositions(Minecraft client, LocalPlayer player, List<Entity> mobs,
                                        AutoClearConfig.Weapon weapon) {
        List<Entity> list = mobs.size() > 63 ? mobs.subList(0, 63) : mobs;
        StringBuilder ids = new StringBuilder(weapon.name());
        StringBuilder key = new StringBuilder();
        for (Entity e : list) {
            ids.append('|').append(e.getId());
            key.append('|').append(e.blockPosition().asLong());
        }
        coverAge++;
        boolean sameMobs = ids.toString().equals(coverIds);
        // The same mobs where they were: nothing to plan. The same mobs moved: again at most every few ticks (walking
        // mobs move a block every few ticks, and the plan is a few thousand block reads).
        if (sameMobs && (key.toString().equals(coverKey) || coverAge < COVER_REPLAN_TICKS)) {
            return;
        }
        coverIds = ids.toString();
        coverKey = key.toString();
        coverAge = 0;
        long t0 = System.nanoTime();
        Cover cover = cover(list, weapon);
        List<Long> keys = cover.keys();
        long[] covers = cover.covers();
        double[][] spots = cover.spots();
        double[] from = {player.getX(), player.getY(), player.getZ()};
        double[][] mobAt = new double[list.size()][];
        for (int j = 0; j < list.size(); j++) {
            mobAt[j] = new double[]{list.get(j).getX(), list.get(j).getY(), list.get(j).getZ()};
        }
        long[] trimmed = cover.trimmed();
        int n = cover.reachable();
        RepositionPlanner.Plan plan = RepositionPlanner.plan(trimmed, n, spots, from, 20_000_000L);
        List<BlockPos> order = new java.util.ArrayList<>();
        List<Set<Integer>> reach = new java.util.ArrayList<>();
        if (plan != null) {
            for (int i : plan.spots()) {
                order.add(BlockPos.of(keys.get(i)));
                Set<Integer> mobIds = new HashSet<>();
                for (int j = 0; j < list.size(); j++) {
                    if ((covers[i] & (1L << j)) != 0) {
                        mobIds.add(list.get(j).getId());
                    }
                }
                reach.add(mobIds);
            }
        }
        coverSpots = order;
        coverMobs = reach;
        int old = RepositionPlanner.nearestMobFirst(covers, list.size(), spots, mobAt, from);
        LOGGER.info(String.format(Locale.US, "[AutoClear] reposition plan for %s: %d block(s) reach all %d reachable starred"
                        + " mob(s)%s (nearest mob first would take %d); %d candidate block(s), %.1f ms: %s", roomName,
                order.size(), n, plan != null && plan.exact() ? ", fewest proved" : "", old, covers.length,
                (System.nanoTime() - t0) / 1e6, describePlan(order, reach)));
    }

    /**
     * The standing blocks round {@code list}'s mobs that the weapon reaches at least one of them from, each with the mobs
     * it reaches ({@code covers}, bit j = mob j), their feet positions, and the masks renumbered over only the mobs some
     * block reaches ({@code trimmed}, {@code reachable} of them) - what {@link RepositionPlanner} plans over.
     */
    private record Cover(List<Long> keys, long[] covers, double[][] spots, long[] trimmed, int reachable) {
    }

    private static Cover cover(List<Entity> list, AutoClearConfig.Weapon weapon) {
        java.util.Map<Long, Long> masks = new java.util.LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            AABB box = list.get(i).getBoundingBox();
            BlockPos base = BlockPos.containing(box.getCenter());
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dy = -5; dy <= 3; dy++) {
                        BlockPos pos = base.offset(dx, dy, dz);
                        long k = pos.asLong();
                        if (masks.containsKey(k)) {
                            continue;
                        }
                        Vec3 feet = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                        if (!reaches(weapon, feet, box)) {
                            continue;
                        }
                        if (!TeleportUtils.etherwarpable(pos)) {
                            masks.put(k, 0L);
                            continue;
                        }
                        long m = 0;
                        for (int j = 0; j < list.size(); j++) {
                            if (reaches(weapon, feet, list.get(j).getBoundingBox())) {
                                m |= 1L << j;
                            }
                        }
                        masks.put(k, m);
                    }
                }
            }
        }
        List<Long> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Long, Long> e : masks.entrySet()) {
            if (e.getValue() != 0) {
                keys.add(e.getKey());
            }
        }
        long[] covers = new long[keys.size()];
        double[][] spots = new double[keys.size()][];
        for (int i = 0; i < keys.size(); i++) {
            BlockPos p = BlockPos.of(keys.get(i));
            covers[i] = masks.get(keys.get(i));
            spots[i] = new double[]{p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5};
        }
        // Only the mobs some block reaches are planned for; the rest keep the per-mob search (and its skip).
        long reached = 0;
        for (long c : covers) {
            reached |= c;
        }
        int n = list.size();
        long all = n >= 63 ? -1L >>> 1 : (1L << n) - 1;
        long[] trimmed = covers.clone();
        if ((reached & all) != all) {
            int[] map = new int[n];
            int k = 0;
            for (int j = 0; j < n; j++) {
                map[j] = (reached & (1L << j)) != 0 ? k++ : -1;
            }
            for (int i = 0; i < covers.length; i++) {
                long m = 0;
                for (int j = 0; j < n; j++) {
                    if ((covers[i] & (1L << j)) != 0 && map[j] >= 0) {
                        m |= 1L << map[j];
                    }
                }
                trimmed[i] = m;
            }
            n = k;
        }
        return new Cover(keys, covers, spots, trimmed, n);
    }

    /** Ticks a held use takes to kill once he stands where it reaches (the turn tick, then the use). */
    static final int ESTIMATE_USE_TICKS = 2;

    /**
     * Auto Secret's Fastest Total plan (killer560, 2026-10-19: "It doesn't need to clear a room first or secret it first;
     * it can do either"): about how long a clear of {@code room}'s starred mobs IN SIGHT takes from {@code from} - the
     * fewest standing blocks that reach them all (the cover the clear itself plans), nearest first, each an etherwarp
     * priced as this class prices one ({@link #ETHER_FIXED_TICKS} + {@link #ETHER_TICKS_PER_WARP} a warp) and a use; one
     * use and no move when {@code from} already reaches every one. Mobs no block reaches are left out, as the clear's own
     * plan leaves them to the per-mob search.
     *
     * @return {ticks, end x, end y, end z, mobs}, or null when none of the room's starred mobs is in sight
     */
    public static double[] estimateClear(Vec3 from, String room) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || from == null || room == null) {
            return null;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int id = AutoClearTargets.roomByName(layout, room);
        if (id < 0) {
            return null;
        }
        List<Entity> mobs = new java.util.ArrayList<>();
        for (Entity mob : MobEspFeature.starredMobs(client)) {
            if (layout.roomAtWorld(mob.getX(), mob.getZ()) == id && mob.isAlive()) {
                mobs.add(mob);
            }
        }
        if (mobs.isEmpty()) {
            return null;
        }
        List<Entity> list = mobs.size() > 63 ? mobs.subList(0, 63) : mobs;
        AutoClearConfig.Weapon weapon = AutoClearConfig.getInstance().getWeapon();
        boolean all = true;
        for (Entity e : list) {
            all &= reaches(weapon, from, e.getBoundingBox());
        }
        if (all) {
            return new double[]{ESTIMATE_USE_TICKS, from.x, from.y, from.z, list.size()};
        }
        Cover cover = cover(list, weapon);
        RepositionPlanner.Plan plan = cover.reachable() == 0 ? null : RepositionPlanner.plan(cover.trimmed(),
                cover.reachable(), cover.spots(), new double[]{from.x, from.y, from.z}, 3_000_000L);
        if (plan == null || plan.spots().length == 0) {
            return null;
        }
        double hop = Math.max(1.0, ClearExecutor.hopRange());
        double ticks = 0;
        double[] at = {from.x, from.y, from.z};
        for (int i : plan.spots()) {
            double[] s = cover.spots()[i];
            double d = Math.sqrt((s[0] - at[0]) * (s[0] - at[0]) + (s[1] - at[1]) * (s[1] - at[1])
                    + (s[2] - at[2]) * (s[2] - at[2]));
            ticks += ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * Math.max(1, Math.ceil(d / hop)) + ESTIMATE_USE_TICKS;
            at = s;
        }
        return new double[]{ticks, at[0], at[1], at[2], list.size()};
    }

    /** An etherwarp trip's ticks between two points as {@link #estimateClear} prices one (0 within a block and a half). */
    public static double etherTicks(Vec3 a, Vec3 b) {
        double d = a.distanceTo(b);
        if (d < 1.5) {
            return 0;
        }
        return ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * Math.max(1, Math.ceil(d / Math.max(1.0, ClearExecutor.hopRange())));
    }

    private static String describePlan(List<BlockPos> order, List<Set<Integer>> reach) {
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            parts.add(order.get(i).toShortString() + " x" + reach.get(i).size());
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static boolean reaches(AutoClearConfig.Weapon weapon, Vec3 feet, AABB mob) {
        return weapon == AutoClearConfig.Weapon.HYPERION ? WeaponReach.hyperionHits(feet, mob)
                : WeaponReach.sceptreHits(feet, mob);
    }

    /** The plan's first block that reaches {@code mob}, or null. */
    private static BlockPos coverSpotFor(Entity mob) {
        for (int i = 0; i < coverSpots.size(); i++) {
            if (coverMobs.get(i).contains(mob.getId())) {
                return coverSpots.get(i);
            }
        }
        return null;
    }

    /**
     * Whether one Hyperion hop does what the plan's block for {@code target} does: a single hop (no chain) whose landing
     * reaches, with the blast, every live mob that block reaches. Always true when that block reaches the target alone.
     */
    private static boolean hopKeepsPlan(Minecraft client, WeaponReach.Aim hop, Entity target) {
        for (int i = 0; i < coverSpots.size(); i++) {
            Set<Integer> ids = coverMobs.get(i);
            if (!ids.contains(target.getId())) {
                continue;
            }
            if (ids.size() <= 1) {
                return true;
            }
            if (cachedHops != 1 || hop.expectFeet() == null) {
                return false;
            }
            for (int id : ids) {
                Entity e = client.level.getEntity(id);
                if (e != null && e.isAlive() && !WeaponReach.hyperionHits(hop.expectFeet(), e.getBoundingBox())) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    /** How many mobs the plan's block for {@code mob} reaches (0 when it has none). */
    private static int coverCountFor(Entity mob) {
        for (int i = 0; i < coverSpots.size(); i++) {
            if (coverMobs.get(i).contains(mob.getId())) {
                return coverMobs.get(i).size();
            }
        }
        return 0;
    }

    /**
     * The mob to go for: one the Hyperion's blast reaches from where he stands (no move at all), else one the plan's
     * first block reaches (that block is the next reposition), else the nearest.
     */
    private static Entity pickTarget(LocalPlayer player, List<Entity> mobs, AutoClearConfig.Weapon weapon) {
        if (weapon == AutoClearConfig.Weapon.HYPERION) {
            List<Entity> now = new java.util.ArrayList<>();
            for (Entity e : mobs) {
                if (WeaponReach.hyperionHits(player.position(), e.getBoundingBox())) {
                    now.add(e);
                }
            }
            if (!now.isEmpty()) {
                return nearest(player, now);
            }
        }
        if (!coverSpots.isEmpty()) {
            List<Entity> reached = new java.util.ArrayList<>();
            for (Entity e : mobs) {
                if (coverMobs.get(0).contains(e.getId())) {
                    reached.add(e);
                }
            }
            if (!reached.isEmpty()) {
                return nearest(player, reached);
            }
        }
        return nearest(player, mobs);
    }

    private static Entity nearest(LocalPlayer player, List<Entity> mobs) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mobs) {
            double d = e.distanceToSqr(player);
            if (e.getId() == targetId) {
                d *= 0.5;   // stick with the current target unless another is much closer
            }
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static final String[] KEY_NAMES = {"forward", "back", "left", "right", "jump", "attack"};

    private static KeyMapping[] watchedKeys(Minecraft client) {
        var o = client.options;
        return new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyAttack};
    }

    /** The name of a movement/jump/attack key pressed since the last tick (not one held since the start), or null. */
    private static String userInput(Minecraft client) {
        KeyMapping[] keys = watchedKeys(client);
        String pressed = null;
        boolean screen = McCompat.screen(client) != null;
        for (int i = 0; i < keys.length; i++) {
            boolean down = keys[i].isDown();
            if (down && !wasDown[i] && !screen && pressed == null) {
                pressed = KEY_NAMES[i];
            }
            wasDown[i] = down;
        }
        return pressed;
    }

    private static void pollToggleKey(Minecraft client) {
        int key = AutoClearConfig.getInstance().getKeybind();
        boolean down = key != KeyUtil.NONE && McCompat.screen(client) == null && client.getWindow() != null
                && KeyUtil.isBindDown(client.getWindow(), key);
        if (down && !toggleKeyWasDown && client.player != null) {
            toggle();
        }
        toggleKeyWasDown = down;
    }

    private static void say(net.minecraft.network.chat.Component... parts) {
        ModChat.send(CHAT, parts);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    // =========================================================================================== status (tests, HUD)

    /** One line of what it is doing, for the HUD and the testkit. */
    public static String status() {
        if (!running) {
            return lastReason == null ? "idle" : "idle - last: " + lastReason;
        }
        return (singleRoom != null ? "room " + singleRoom : AutoClearConfig.getInstance().getMode().label())
                + " | " + (roomName == null ? "-" : roomName)
                + (targetName.isEmpty() ? "" : " | " + targetName) + " | " + action;
    }

    /**
     * The floor as Auto Clear reads it, one line a room: {@code name|type|mob|cleared|rush|x|z} (mob/cleared/rush are 1
     * or 0; rush = on the Blood Rush Split path; x/z the centre of the room's first tile). For the testkit and for
     * checking the mode's reading of a floor by hand. Client thread.
     */
    public static List<String> floorReport() {
        DungeonLayout layout = DungeonLayout.capture();
        List<Integer> rush = AutoClearTargets.bloodRushRooms(layout);
        List<String> out = new java.util.ArrayList<>();
        for (int r = 0; r < layout.roomCount(); r++) {
            var entry = layout.entry(r);
            BlockPos c = DungeonLayout.cellCenter(layout.tiles(r)[0]);
            out.add(layout.name(r) + "|" + (entry == null ? "?" : entry.type) + "|"
                    + (AutoClearTargets.isMobRoom(layout, r) ? 1 : 0) + "|" + (AutoClearTargets.isCleared(layout, r) ? 1 : 0)
                    + "|" + (rush != null && rush.contains(r) ? 1 : 0) + "|" + c.getX() + "|" + c.getZ());
        }
        return out;
    }

    public static int roomsClearedThisRun() {
        return roomsCleared;
    }

    public static int correctionsThisRun() {
        return corrections;
    }

    public static Set<String> skippedRoomsThisRun() {
        return Set.copyOf(skippedRooms);
    }

    // ---- read by the shared status HUD (autosecret.AutoStatusHud, killer560 2026-10-15: "make it the same hud for all
    // the auto clears") - Auto Clear has no HUD of its own any more ----

    /** The room it is clearing or heading for, or null. */
    public static String hudRoom() {
        return running ? (roomName != null ? roomName : singleRoom) : null;
    }

    /** What it is doing, as the long line the log and the testkit read ({@link #status()} has the same words). */
    public static String hudAction() {
        return action;
    }

    /** Trips to the current room that failed so far (a retry is under way when this is above 0). */
    public static int hudPathFailures() {
        return pathFailures;
    }

    /** True while a run started by him (the key, /autoclear) owns it, rather than one room handed over by Auto Secret. */
    public static boolean isStandaloneRun() {
        return running && singleRoom == null;
    }

    /** Why the last run he started himself ended, while that was under 6 s ago; else null. */
    public static String hudLastEnd() {
        return lastReason != null && lastReasonStandalone && System.currentTimeMillis() - lastReasonMs < 6000L
                ? lastReason : null;
    }

    /** {@link System#currentTimeMillis()} of the last run end, so a reader can tell a new end from an old one. */
    public static long hudLastEndMs() {
        return lastReasonMs;
    }

    /** Mob rooms on the map that are not cleared yet (the Clear column's "rooms left"). Client thread; not per frame. */
    public static int mobRoomsLeft() {
        DungeonLayout layout = DungeonLayout.capture();
        int left = 0;
        for (int r = 0; r < layout.roomCount(); r++) {
            if (AutoClearTargets.isMobRoom(layout, r) && !AutoClearTargets.isCleared(layout, r)) {
                left++;
            }
        }
        return left;
    }
}
