package com.killer560.hub.autoclear;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
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
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
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
 *       overhead (search, sneak, the 9-tick arrival settle - {@link #ETHER_FIXED_TICKS}) plus about two ticks a warp.</li>
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
     * the first warp waits for the sneak to reach the server, and after the last landing {@code ClearExecutor.onTickEnd}
     * waits for the server's answer and then 9 more ticks before it calls the trip done.
     */
    static final int ETHER_FIXED_TICKS = 14;
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
        roomsCleared = 0;
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
            if (ClearExecutor.isBusy()) {
                return;
            }
            phase = Phase.IDLE;
            planKey = Long.MIN_VALUE;
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

        // ---- the room ----
        int room = AutoClearTargets.roomByName(layout, roomName);
        if (room >= 0 && AutoClearTargets.isCleared(layout, room)) {
            roomsCleared++;
            say(ModChat.good("Cleared "), ModChat.value(roomName));
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
            say(ModChat.text("Room: "), ModChat.value(roomName));
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
                if (noMobTicks > NO_MOB_TICKS + com.killer560.hub.util.ServerLag.ticksSince(noMobLag)) {
                    if (killsInRoom > 0 && !LiveMapFeature.hasRoomStates()) {
                        // No dungeon map to say so (nothing ever reads cleared without one): every starred mob in
                        // sight is dead, which is all a clear is.
                        roomDoneWithoutMap();
                    } else {
                        giveUpRoom("no starred mob is visible but the room is not cleared");
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
            say(ModChat.bad("Skipping "), ModChat.value(targetName),
                    ModChat.dim(String.format(Locale.US, " - not dead after %.1f s", mobTicks / 20.0)));
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
        names.add(layout.name(picked));
        for (int r : order) {
            if (r != picked) {
                names.add(layout.name(r));
            }
        }
        com.killer560.hub.autoroutes.RunPlan.publish(com.killer560.hub.autoroutes.RunPlan.Source.AUTO_CLEAR, names,
                roomsCleared);
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
            say(ModChat.text("Wither door in the way of "), ModChat.value(behind), ModChat.dim(" - going to open it"));
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

    private static void roomDoneWithoutMap() {
        roomsCleared++;
        say(ModChat.good("Cleared "), ModChat.value(roomName), ModChat.dim(" (no starred mob left in sight; no dungeon map"
                + " to confirm)"));
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
        }
        if (cachedAttack != null) {
            WeaponReach.Aim aim = weapon == AutoClearConfig.Weapon.HYPERION
                    ? WeaponReach.hyperionInPlace(player, feet) : cachedAttack;
            action = aim.what() + " -> " + targetName;
            aimAndHold(client, player, weapon.itemId(), weapon.label(), aim, Phase.ATTACK);
            return;
        }
        // ---- a move is needed ----
        releaseUse(client);
        if (!player.onGround()) {
            action = "landing";
            return;
        }
        boolean canHop = cfg.isHyperionHops() && hopsOffFor != targetId
                && ItemIdentity.findHotbarSlot(player, AutoClearConfig.Weapon.HYPERION.itemId()) >= 0;
        if (cachedHops == -2) {
            cachedHops = canHop ? WeaponReach.hyperionHopsNeeded(client.level, player, feet, box, MAX_HOPS) : -1;
        }
        double dist = Math.sqrt(box.distanceToSqr(feet));
        int warps = Math.max(1, (int) Math.ceil(dist / ClearExecutor.hopRange()));
        int etherTicks = ETHER_FIXED_TICKS + ETHER_TICKS_PER_WARP * warps;
        int hypeTicks = cachedHops > 0 ? 2 + USE_REPEAT_TICKS * (cachedHops - 1) : Integer.MAX_VALUE;
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
        if (spot == null) {
            skippedMobs.add(targetId);
            say(ModChat.bad("Skipping "), ModChat.value(targetName),
                    ModChat.dim(" - no block to stand on within " + cfg.getWeapon().label() + " reach of it"));
            targetId = -1;
            return;
        }
        if (BlockPos.containing(feet).below().equals(spot)) {
            // Standing on the spot already and still out of reach (the mob moved): skip rather than loop.
            skippedMobs.add(targetId);
            targetId = -1;
            return;
        }
        action = "etherwarp toward " + targetName + " (~" + etherTicks + " ticks vs hops "
                + (cachedHops < 0 ? "none" : "~" + hypeTicks) + ")";
        startTrip(client, player, () -> {
            ClearExecutor.etherPath(spot, null);
            return true;
        });
        if (phase == Phase.TRAVEL) {
            // Said (and counted) once the trip has really started: a body still turned back first (Phase.RELEASE) comes
            // through here again next tick, and used to log the same etherwarp twice.
            repositions++;
            LOGGER.info("[AutoClear] etherwarp toward {} at {}: stand {} (~{} ticks; hops {}){}", targetName,
                    target.blockPosition().toShortString(), spot.toShortString(), etherTicks, cachedHops,
                    planned != null ? " - the plan's block reaching " + coverCountFor(target) + " mob(s)"
                            : " - nearest block to it");
        }
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
        if (player.getInventory().getSelectedSlot() == slot) {
            return;
        }
        player.getInventory().setSelectedSlot(slot);
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        } else {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
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
        if (ClearExecutor.isBusy()) {
            return;   // the trip's own landings, or a correction it judges itself (above)
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
