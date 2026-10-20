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
        IDLE, RELEASE, TRAVEL, HOP, ATTACK
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
        travelStarted = false;
        skippedRooms.clear();
        skippedMobs.clear();
        doorTarget = -1;
        keyId = -1;
        keyTrip = false;
        keysTried.clear();
        resetRoam();
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
            action = "waiting (" + com.killer560.hub.autopuzzles.AutoPuzzleActivity.what() + " is working)";
            positionPacketsSeen = positionPackets;
            return;
        }
        checkPositionPackets(client, player);
        if (!running) {
            return;
        }
        try {
            step(client, player);
        } catch (RuntimeException e) {
            LOGGER.warn("[AutoClear] tick failed", e);
            stop("internal error: " + e);
        }
    }

    private static void step(Minecraft client, LocalPlayer player) {
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
            if (keyTrip) {
                // A trip to a dropped key is the key's business: its failure is not the room's (pathFailures).
                keyTrip = false;
                if (travelStarted && ClearExecutor.lastPathFailed() && keyId >= 0) {
                    LOGGER.info("[AutoClear] key: the trip to the Wither Key #{} failed - leaving it", keyId);
                    keysTried.add(keyId);
                    keyId = -1;
                }
                travelStarted = false;
            }
            if (roamTrip) {
                // A roam trip's failure costs its spot (already counted), not one of the room's tries.
                roamTrip = false;
                if (travelStarted && ClearExecutor.lastPathFailed()) {
                    LOGGER.info("[AutoClear] roam: the trip to spot {} in {} failed - looking from here", roamSpotsTried,
                            roomName);
                } else {
                    LOGGER.info("[AutoClear] roam: at spot {} in {} ({}) - looking for starred mobs for {} ticks",
                            roamSpotsTried, roomName, fmt(player.position()), ROAM_LOOK_TICKS);
                }
                noMobTicks = 0;
                travelStarted = false;
            }
            if (travelStarted && ClearExecutor.lastPathFailed()) {
                pathFailures++;
                LOGGER.info("[AutoClear] trip {} failed ({} of {})", action, pathFailures, MAX_PATH_FAILURES);
                if (pathFailures >= MAX_PATH_FAILURES) {
                    giveUpRoom("no etherwarp path (" + pathFailures + " tries)");
                    return;
                }
            }
            if (travelStarted && doorTarget >= 0 && !ClearExecutor.lastPathFailed()) {
                doorTripDone = doorTarget;   // the map took him as close to the door as it goes
            }
            travelStarted = false;
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
            LOGGER.info("[AutoClear] room cleared: {}", roomName);
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
        List<Entity> mobs = starredIn(client, layout, room);
        if (mobs.isEmpty()) {
            targetId = -1;
            targetName = "";
            if (layout.currentRoom() == room) {
                releaseUse(client);
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
    private static int doorCooldown = 0;
    private static boolean saidNoKey = false;

    /**
     * Gets through a shut wither door: the Interactive Map's door pathing to its approach spot (Auto Blood Rush's
     * pathToDoor), then a right-click on the door with the Wither Key. No key: it waits there and says so once.
     * Called each decision while the door is the only way on; it notices the door open (the block goes) and moves on.
     */
    private static void openDoor(DungeonLayout layout, int door, String behind) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        releaseUse(client);
        if (door != doorTarget) {
            doorTarget = door;
            doorTripDone = -1;
            saidNoKey = false;
            doorCooldown = 0;
            LOGGER.info("[AutoClear] only a wither door ({}) stands before {} - going to it", door, behind);
        }
        int cur = layout.currentRoom();
        BlockPos approach = cur < 0 ? null : DungeonMapPathfinder.getDoorPos(layout, cur, door);
        if (approach == null) {
            action = "no way to the wither door";
            return;
        }
        double dx = player.getX() - (approach.getX() + 0.5);
        double dz = player.getZ() - (approach.getZ() + 0.5);
        // Once a trip there has finished he is as close as the map takes him: its planner calls anything within five
        // blocks of the approach "already there", so asking again from 2.5-5 blocks off asked every tick for ever
        // (2026-10-07, testkit 131: "trip to wither door" and "Already there" on every tick, never a click).
        boolean arrived = doorTripDone == door;
        if (!arrived && (dx * dx + dz * dz > 2.5 * 2.5 || Math.abs(player.getY() - (approach.getY() + 1)) > 2.0)) {
            if (!AutoClearUtils.canPath(layout)) {
                action = "waiting to land before the trip to the wither door";
                return;
            }
            action = "etherwarp to the wither door";
            LOGGER.info("[AutoClear] trip to wither door {} (approach {})", door, approach.toShortString());
            startTrip(client, player, () -> AutoClearUtils.pathToDoor(layout, door, false));
            return;
        }
        // The team's key (DungeonKeys: sidebar, else the pickup / door chat lines - Hypixel keys are the team's).
        boolean key = com.killer560.hub.doorkeys.DungeonKeys.witherKeys() > 0
                || (com.killer560.hub.roomsim.SimState.isActive()
                ? com.killer560.hub.roomsim.SimDoors.keysHeld() > 0
                : com.killer560.hub.witherdoors.WitherDoorsFeature.isWitherKeyHeld());
        if (!key) {
            action = "waiting at the wither door - no Wither Key";
            if (!saidNoKey) {
                saidNoKey = true;
                say(ModChat.bad("Waiting at the wither door"), ModChat.dim(" - nobody has the Wither Key yet"));
                LOGGER.info("[AutoClear] at wither door {} with no key - waiting", door);
            }
            return;
        }
        if (doorCooldown > 0) {
            doorCooldown--;
            action = "opening the wither door";
            return;
        }
        int keySlot = ItemIdentity.findHotbarSlotById(player, "WITHER_KEY");
        if (keySlot >= 0 && player.getInventory().getSelectedSlot() != keySlot) {
            select(client, player, keySlot);
            return;
        }
        BlockPos lock = DungeonLayout.doorBlock(door);
        BlockPos target = null;
        for (BlockPos p : new BlockPos[]{lock.above(), lock, lock.above(2)}) {
            if (!client.level.getBlockState(p).getShape(client.level, p).isEmpty()) {
                target = p;
                break;
            }
        }
        if (target == null) {
            action = "waiting for the wither door to finish opening";
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aimAt = Vec3.atCenterOf(target);
        double reach = player.blockInteractionRange();
        if (Math.sqrt(new net.minecraft.world.phys.AABB(target).distanceToSqr(eye)) > reach) {
            // Measured to the block's box, as the server does. Out of reach from where the map leaves him: say so and
            // stop, rather than clicking at a door the server will never let him reach.
            stop(String.format(Locale.US, "the wither door is %.1f blocks away, out of reach from where the map put you",
                    Math.sqrt(new net.minecraft.world.phys.AABB(target).distanceToSqr(eye))));
            return;
        }
        TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, aimAt);
        float yaw = player.getYRot() + Mth.wrapDegrees(r.yaw() - player.getYRot());
        float pitch = Mth.clamp(r.pitch(), -90f, 90f);
        bodyWanted = true;
        if (!BODY.isHeld() || Math.abs(yaw - player.getYRot()) > 0.5f || Math.abs(pitch - player.getXRot()) > 0.5f) {
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
        client.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        doorCooldown = 20;
        action = "opening the wither door";
        LOGGER.info("[AutoClear] clicked wither door {} at {} with the key", door, target.toShortString());
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
        seenMobs.clear();
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

    private record Seen(String name, Vec3 pos, double dist, boolean lost) {
    }

    /** The current room's starred mobs as last seen, by entity id; {@code lost} once one left the view alive. */
    private static final java.util.Map<Integer, Seen> seenMobs = new java.util.HashMap<>();
    private static int roamKnownTried = 0;

    /** A mob to roam to: last seen at {@code pos}; {@code lostId} >= 0 for a lost one (forgotten once gone for). */
    private record Known(String name, Vec3 pos, int lostId, String why) {
    }

    /** Every running tick (trips included, so a mob dying under a bat in flight is seen dying, not lost). */
    private static void observeMobs(Minecraft client, LocalPlayer player) {
        if (roomName == null || client.level == null) {
            seenMobs.clear();
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int room = AutoClearTargets.roomByName(layout, roomName);
        if (room < 0) {
            seenMobs.clear();
            return;
        }
        Set<Integer> now = new HashSet<>();
        for (Entity mob : MobEspFeature.starredMobs(client)) {
            if (layout.roomAtWorld(mob.getX(), mob.getZ()) != room) {
                continue;
            }
            now.add(mob.getId());
            Seen before = seenMobs.get(mob.getId());
            if (before != null && before.lost()) {
                LOGGER.info(String.format(Locale.US, "[AutoClear] %s is in view again at %s, %.1f blocks away",
                        before.name(), mob.blockPosition().toShortString(), mob.distanceTo(player)));
            }
            seenMobs.put(mob.getId(), new Seen(mob.getName().getString(), mob.position(), mob.distanceTo(player), false));
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
                }
                continue;          // still there without its star tag: kept as last seen
            }
            if (s.dist() >= LOST_MIN_DISTANCE) {
                entry.setValue(new Seen(s.name(), s.pos(), s.dist(), true));
                LOGGER.info(String.format(Locale.US, "[AutoClear] %s left the client's view alive at %s, %.1f blocks away"
                        + " - remembered for the roam", s.name(), BlockPos.containing(s.pos()).toShortString(), s.dist()));
            } else {
                it.remove();       // gone this near: killed
            }
        }
    }

    /** The current room's mobs worth going to with nothing in sight: those lost from view, then those skipped. */
    private static List<Known> knownMobs(Minecraft client, DungeonLayout layout, int room) {
        List<Known> out = new java.util.ArrayList<>();
        for (var entry : seenMobs.entrySet()) {
            if (entry.getValue().lost()) {
                out.add(new Known(entry.getValue().name(), entry.getValue().pos(), entry.getKey(), "left the view"));
            }
        }
        for (int id : skippedMobs) {
            Entity e = client.level.getEntity(id);
            if (e != null && e.isAlive() && layout.roomAtWorld(e.getX(), e.getZ()) == room) {
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
        startTrip(client, player, () -> {
            ClearExecutor.etherPathFast(spot, null);
            return true;
        });
        if (phase != Phase.TRAVEL) {
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
            if (e != null && e.isAlive() && layout.roomAtWorld(e.getX(), e.getZ()) == room) {
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
            startTrip(client, player, () -> {
                ClearExecutor.etherPathFast(spot, null);
                return true;
            });
            if (phase != Phase.TRAVEL) {
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
                    : WeaponReach.sceptreAim(client.level, player, player.getEyePosition(), box);
            cachedHops = -2;   // computed only if a move is needed
            cachedBat = null;
            cachedBatTicks = -2;
            cachedPreferEther = null;
        }
        if (cachedAttack != null) {
            // With the sceptre, a bat from here still has to FLY there: a far one can be slower than etherwarping onto
            // the plan's block and firing down. Never second-guessed once its use is held (no flip-flop mid-flight).
            boolean held = useHeld && phase == Phase.ATTACK;
            if (weapon != AutoClearConfig.Weapon.SPIRIT_SCEPTRE || held
                    || !sceptreSlowerThanEther(client, player, target, box, cfg)) {
                WeaponReach.Aim aim = weapon == AutoClearConfig.Weapon.HYPERION
                        ? WeaponReach.hyperionInPlace(player, feet) : cachedAttack;
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
            action = "Guided Bat -> " + targetName + " (~" + cachedBatTicks + " ticks vs etherwarp ~"
                    + (etherTicks + HYPERION_KILL_TICKS) + (canHop && cachedHops > 0 ? ", hops ~" + hypeTicks : "") + ")";
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
        }
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
            cachedBat = slot < 0 ? null : WeaponReach.sceptreAim(client.level, player, player.getEyePosition(), box);
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
        return cachedPreferEther;
    }

    private static void travelToRoom(Minecraft client, LocalPlayer player, DungeonLayout layout, int room) {
        releaseUse(client);
        if (!AutoClearUtils.canPath(layout)) {
            action = "waiting to land before the trip to " + roomName;
            return;
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
            if (skippedMobs.contains(mob.getId())) {
                continue;
            }
            if (layout.roomAtWorld(mob.getX(), mob.getZ()) == room) {
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
        double[] from = {player.getX(), player.getY(), player.getZ()};
        double[][] mobAt = new double[list.size()][];
        for (int j = 0; j < list.size(); j++) {
            mobAt[j] = new double[]{list.get(j).getX(), list.get(j).getY(), list.get(j).getZ()};
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
