package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModChat;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.slf4j.Logger;

import java.util.ArrayDeque;

/**
 * Blink's Charge style (killer560, 2026-10-25: "I can stand still and do a terminal then once it is done I start walking
 * and release it so I shoot 22 packets forward the instant it finishes"; "During a blink going off I should be able to
 * flick levers mine blocks and things like that").
 *
 * <p><b>Charging.</b> While Charge is on, a client tick in which he did not move and did nothing the server checks against
 * his look (no use, block click, hit, dig edge or input change) is banked: its tick-end, and the move packet a turn of the
 * camera made, are not sent. The server and GrimAC simply see a client that has fallen a little behind real time - which
 * GrimAC allows up to 1120 ms (22 ticks, see {@link BlinkConfig#GRIM_MAX_TICKS}). Every other packet goes out live, so
 * terminal clicks, levers, mining and abilities all work while charging; a tick with such an action goes out whole (its
 * move carries the look the action is checked against) and simply is not banked.
 *
 * <p><b>Shooting.</b> Only when he says so (killer560, 2026-10-25): the Charge key's release (a second press in Toggle,
 * letting go in Hold). If no movement key is down at that moment the shot is armed and fires the first tick one is, so a
 * shot never goes off standing still and moves him nowhere (his 2026-10-25 log: a dozen 0.00-block shots). The client runs the banked ticks of his own movement - the vanilla player tick, driven by the keys he is
 * really holding - one after another, each followed by its tick-end, before this tick's own. The server receives them
 * together and moves him that far in one go, and so does his screen. Nothing writes position or velocity: every one of
 * those ticks is the game's own physics on real key presses.
 *
 * <p><b>Never runs out.</b> The bank is empty after a shot and fills again whenever he stands still. A full bank stays
 * full until he fires it. A server teleport that does not move him (Hypixel re-syncs a charging player about once a
 * second - his 2026-10-25 log, 89 of them - and each one emptied the bank) keeps the bank. One that moves him (an
 * etherwarp, a leap, a pearl) starts it again: GrimAC only takes the teleport once the ping sent with it is answered, and
 * answering it brings the lag clock to the present (see {@link #ANSWER_DELAY_MS}).
 */
public final class BlinkCharge {

    private static final Logger LOGGER = BlinkLog.LOGGER;

    private record Held(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    private static final Object LOCK = new Object();
    private static volatile boolean active;
    private static Connection connection;
    private static int banked;
    /** This tick's move, kept until the tick-end says whether the tick is banked or sent. */
    private static Held stash;
    /** This tick sent something checked against his look or position: it goes out whole. */
    private static boolean busy;
    /** Re-entry from our own sends (the stash, the shot's ticks) passes straight through. */
    private static boolean passing;
    /** A teleport was just confirmed: the move that answers it goes out at once, never kept for the tick-end. */
    private static boolean teleportMoveNext;
    /**
     * Something has gone out since the last tick-end. A waiting ping answer may only go first in a tick: GrimAC's Post
     * check flags an item use followed by a ping answer before the next movement (testkit 62-argrim-1025-chargechain:
     * Auto Routes sent its use at the start of the tick, then Blink's tick sent the due answers behind it).
     */
    private static boolean tickOpen;
    /** Banked ticks a shot still has to run (it stopped at {@link #SHOT_BLOCKS_PER_TICK}); they run next tick. */
    private static int shotLeft;
    private static String shotLeftWhy = "";
    /**
     * The most a shot moves him inside one tick. The vanilla server takes more than five move packets in one of its ticks
     * as one, and sets back a player moved more than 10 blocks in it ("moved too quickly"): a Speed IV shot of 10.5 blocks
     * was set back, an 11-block one passed only when the burst happened to straddle two server ticks (testkit
     * 1025-blink-charge-far, 2026-10-25). Past this the rest of the shot runs the next tick.
     */
    private static final double SHOT_BLOCKS_PER_TICK = 9.5;
    /**
     * The answers to the server's pings, each sent {@link #ANSWER_DELAY_MS} after it was made while Charge is on (killer560,
     * 2026-10-25: the charge must never be dropped for time). GrimAC measures how far behind real time he may be from the
     * last ping he answered when a tick next arrives (Timer / TimerLimit lastMovementPlayerClock, javap 2.3.74): answered
     * live, every tick sent while charging - a lever's, a dig's, or a normal one once the bank is full - brought that clock
     * to the present and the bank was gone (testkit 1025-blink-charge, first run: every shot flagged Timer). With every
     * answer a steady second late, that clock is always a second old, so TimerLimit's own floor (real time - 1000 ms
     * ping-abuse-limit - 120 ms drift) is what binds and a full bank is always allowed; every answer still goes within a
     * second, far inside Grim's 60 s max-transaction-time. To Grim he is a player with a second of ping. In order, always.
     */
    private record Answer(Held held, long madeMs) {
    }

    private static final ArrayDeque<Answer> answers = new ArrayDeque<>();
    static final long ANSWER_DELAY_MS = 1050L;

    /**
     * The delay actually used: just what a full bank needs, so the answers are no later than they must be. GrimAC clamps the
     * timer balance up to (that answer's ping time - 120 ms drift), and a full bank of B ticks needs the balance 50 B + 50 ms
     * behind: D >= 50 B - 70. Twenty ticks: 930 ms; 60 ms of headroom on top.
     */
    static long answerDelayMs() {
        return Math.min(ANSWER_DELAY_MS, Math.max(0L, 50L * cap() - 10L));
    }

    /**
     * Always false since 2026-10-27: every server teleport keeps the bank and nothing pauses banking after one (killer560, 2026-10-27: the blink should always be saved through leaps and teleports; it should never drop blink if possible except if i release it).
     * From 2026-10-26 to then, banking paused for the answer delay + 150 ms after a moving teleport (GrimAC Simulation on a
     * shot fired the instant the bank refilled); GrimAC flagging the next shot is accepted by his decision.
     */
    public static boolean settling() {
        return false;
    }

    /** The last server teleport did not move him (an in-place re-sync): its confirm leaves the bank alone. */
    private static volatile boolean teleportInPlace;
    private static long inPlaceTeleports;
    private static long movingTeleports;

    /** Blink's server-position hook, before vanilla answers it: how far the teleport moves him. Render thread. */
    static void noteServerTeleport(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to, double dist) {
        teleportInPlace = dist < 0.1;
        if (active) {
            BlinkLog.event("charge: server teleport %.2f blocks, client at %s, server put him at %s, bank %d, %d answer(s) "
                    + "waiting (oldest %d ms) - %s", dist, BlinkLog.fmt(from), BlinkLog.fmt(to), banked, answers.size(),
                    oldestAnswerAgeMs(), (teleportInPlace ? "in place (re-sync)" : "moved") + ", bank kept (" + banked + ")");
        }
        // A teleport soon after a shot that puts him back where the shot started is the server refusing the shot.
        if (ticksSinceShot <= 40 && lastShotFrom != null && to != null && dist > 0.5 && to.distanceTo(lastShotFrom) < 0.75) {
            shotsSetBack++;
            BlinkLog.event("SHOT SET BACK: %d tick(s) after shot #%d the server put him at %s (where the shot started), the "
                    + "client was at %s (the shot had ended at %s); setback #%d", ticksSinceShot, shots, BlinkLog.fmt(to),
                    BlinkLog.fmt(from), BlinkLog.fmt(lastShotTo), shotsSetBack);
            com.killer560.hub.util.ServerCorrections.report("Blink", "the server set back a charge shot - carrying on", dist);
        } else if (ticksSinceShot <= 40 && dist > 0.5) {
            BlinkLog.event("server teleport %d tick(s) after shot #%d: %.2f blocks, client at %s, server put him at %s - not "
                    + "back to the shot's start", ticksSinceShot, shots, dist, BlinkLog.fmt(from), BlinkLog.fmt(to));
        }
    }

    /** Ticks since the last shot (capped), the shot's start and end: a setback after a shot is told apart from a warp. */
    private static int ticksSinceShot = Integer.MAX_VALUE;
    private static net.minecraft.world.phys.Vec3 lastShotFrom;
    private static net.minecraft.world.phys.Vec3 lastShotTo;
    private static long shotsSetBack;

    public static long shotsSetBack() {
        return shotsSetBack;
    }

    /** How long the oldest waiting ping answer has waited, ms (0 when none). */
    public static long oldestAnswerAgeMs() {
        synchronized (LOCK) {
            Answer a = answers.peekFirst();
            return a == null ? 0 : System.currentTimeMillis() - a.madeMs();
        }
    }

    /** The longest any answer has waited when it was sent, ms, and the most waiting at once (for the status line). */
    private static long maxAnswerWaitMs;
    private static int maxAnswersWaiting;

    public static long maxAnswerWaitMs() {
        return maxAnswerWaitMs;
    }

    public static int maxAnswersWaiting() {
        return maxAnswersWaiting;
    }

    public static long inPlaceTeleports() {
        return inPlaceTeleports;
    }

    public static long movingTeleports() {
        return movingTeleports;
    }

    /** A shot is waiting for a movement key (the release came while he stood still). */
    private static boolean armed;
    /** The armed shot ends charging when it fires (Hold mode's release). */
    private static boolean offAfterShot;
    private static boolean keyWasDown;

    public static boolean isArmed() {
        return armed;
    }
    private static long answersHeldTotal;

    /**
     * What else runs in each of a shot's ticks, before his movement (killer560, 2026-10-25: a blink that runs him into
     * breaker blocks should mine them, and one past a lever should still flick it). A shot's ticks all run inside one
     * real tick, where the auras would get one turn between them; these give each shot tick the turn a real tick gives
     * it, in a real tick's order: ActionGate's new tick, then the auras' START work, then his movement and the tick-end.
     */
    private static final java.util.List<java.util.function.Consumer<Minecraft>> SHOT_TICK_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A feature that acts on what he passes (an aura) adds its START tick here. Cheat build only matters. */
    public static void addShotTickHook(java.util.function.Consumer<Minecraft> hook) {
        SHOT_TICK_HOOKS.add(hook);
    }

    /**
     * Run at the END of each shot tick, after its movement and tick-end (2026-10-27): a feature that steers him tick by
     * tick (AP3) does its END work here, so the next shot tick moves on the keys and rotation it chose for that tick, not
     * on one set frozen for the whole shot. Before this a 20-tick AP3 release replayed the run's keys straight off a
     * platform in his S2 (Mod Only Test log 00:15:34).
     */
    private static final java.util.List<java.util.function.Consumer<Minecraft>> SHOT_TICK_END_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Asked before each shot tick: a reason ends the shot there, and the rest stays banked. Null = go on. */
    private static final java.util.List<java.util.function.Function<Minecraft, String>> SHOT_GUARDS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The shot loop is running (a driver's END work is inside it). */
    private static volatile boolean inShotLoop;
    /** An endFor that came while the shot loop ran: done once the loop is out. */
    private static volatile String pendingEnd;
    private static volatile String lastShotStoppedBy;

    public static void addShotTickEndHook(java.util.function.Consumer<Minecraft> hook) {
        SHOT_TICK_END_HOOKS.add(hook);
    }

    public static void addShotGuard(java.util.function.Function<Minecraft, String> guard) {
        SHOT_GUARDS.add(guard);
    }

    /** Why the last shot ended before its count (a guard), or null when it ran out. */
    public static String lastShotStoppedBy() {
        return lastShotStoppedBy;
    }

    /** The shot loop is running now (a hook asking from inside it). */
    public static boolean inShotLoop() {
        return inShotLoop;
    }

    /** Counts shot loops started (a feature's note about "this shot" goes stale with the next). */
    public static long shotSerial() {
        return shotSerial;
    }

    private static volatile long shotSerial;

    private static void runShotTickEndHooks(Minecraft client) {
        for (java.util.function.Consumer<Minecraft> hook : SHOT_TICK_END_HOOKS) {
            try {
                hook.accept(client);
            } catch (RuntimeException e) {
                LOGGER.error("[Blink] a shot-tick end hook threw", e);
            }
        }
    }

    private static String shotGuardReason(Minecraft client) {
        for (java.util.function.Function<Minecraft, String> guard : SHOT_GUARDS) {
            try {
                String r = guard.apply(client);
                if (r != null) {
                    return r;
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Blink] a shot guard threw", e);
                return "a shot guard threw";
            }
        }
        return null;
    }

    private static int shotTickActions;
    private static int shotPacketsSent;
    /** True while a shot's ticks are running (Breaker Aura predicts its breaks then: see {@link #inShot}). */
    private static volatile boolean shooting;
    /** Milliseconds the shots have run ahead of the wall clock: every shot tick is 50 ms to the server. */
    private static volatile long shotMsAhead;

    /**
     * A clock for features that time things in milliseconds (Secret Aura's 150 ms in-reach delay): the wall clock plus
     * 50 ms for every shot tick so far, so a shot's ticks count as the time they are to the server. Without it a lever
     * a shot passed was only clicked on the real tick after the shot (testkit 1025-blink-shot-lever, 2026-10-25). Never
     * goes backwards.
     */
    public static long nowMs() {
        return System.currentTimeMillis() + shotMsAhead;
    }

    /** Set for one tick by a driver that skipped his physics this tick (AP3's momentum-keeping charge). */
    private static volatile boolean frozenTick;
    private static long frozenTicksSent;

    /** A driver skipped his physics this tick: its tick-end banks wherever he is (mid-air too). Render thread. */
    public static void noteFrozenTick() {
        frozenTick = true;
    }

    /** Frozen ticks that could not be banked and went out (a mismatch the server may see), for the log and the testkit. */
    public static long frozenTicksSent() {
        return frozenTicksSent;
    }

    /** This tick's tick-end would bank (charging, no shot pending, room in the bank). */
    public static boolean canBankNow() {
        return active && !shotPending() && banked < cap()
                && connection != null && connection.isConnected();
    }

    /** A shot's ticks are running now (render thread). */
    public static boolean inShot() {
        return shooting;
    }

    /** Shot ticks in which an aura sent something (a dig, a lever click), for the testkit and the log. */
    public static int shotTickActions() {
        return shotTickActions;
    }

    private static void runShotTickHooks(Minecraft client) {
        com.killer560.hub.util.ActionGate.onClientTick(client);
        for (java.util.function.Consumer<Minecraft> hook : SHOT_TICK_HOOKS) {
            try {
                hook.accept(client);
            } catch (RuntimeException e) {
                LOGGER.error("[Blink] a shot-tick hook threw", e);
            }
        }
    }

    private static long shots;
    private static long shotTicks;
    private static int lastShotTicks;
    private static double lastShotBlocks;
    private static long bankedTotal;

    private BlinkCharge() {
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Blink's send hook, before its own hold. True when the packet is not to be sent now. Any thread. */
    static boolean onSend(Connection c, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (!active) {
            return false;
        }
        synchronized (LOCK) {
            return onSendLocked(c, packet, listener, flush);
        }
    }

    /**
     * Blink's send hook, first of all (Charge or a hold): a move that goes out while a banked tick's look is owed is sent
     * with that look instead. True when the move was re-sent (the original is not to be sent).
     */
    static boolean carryOwedLook(Connection c, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (rotationOwed && packet instanceof ServerboundMovePlayerPacket move) {
            // A banked tick took a turn of the camera with it (its move was not sent), and vanilla, having "sent" it,
            // will not report that look again until he turns once more. The next move that goes out carries it, or
            // the server keeps the old look and every tick of the next shot runs the wrong way for GrimAC
            // (Simulation 0.13-0.34 on each of 20 ticks after a 180 turn, testkit 1025-blink-charge 2026-10-25).
            rotationOwed = false;
            if (!move.hasRotation()) {
                Packet<?> withLook = move.hasPosition()
                        ? new ServerboundMovePlayerPacket.PosRot(move.getX(0), move.getY(0), move.getZ(0), owedYaw,
                        owedPitch, move.isOnGround(), move.horizontalCollision())
                        : new ServerboundMovePlayerPacket.Rot(owedYaw, owedPitch, move.isOnGround(),
                        move.horizontalCollision());
                rotationsCarried++;
                c.send(withLook, listener, flush);
                return true;
            }
        }
        return false;
    }

    private static boolean onSendLocked(Connection c, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        {
            if (!active || c != connection) {
                return false;
            }
            boolean answer = packet instanceof net.minecraft.network.protocol.common.ServerboundPongPacket;
            if (passing && !answer && !(packet instanceof ServerboundMovePlayerPacket)
                    && !(packet instanceof ServerboundClientTickEndPacket)) {
                shotPacketsSent++;
            }
            if (!answer && !passing) {
                // Only the client's own sends open and close a tick. Before 2026-10-26 our own re-sends counted too: a
                // tick that was not banked sends its kept move from inside the tick-end's hook, and that move re-opened
                // the tick, so while he moved with Charge on no answer was ever sent at a tick start - 18 waiting, the
                // oldest 1660 ms, at a 140 ms delay (testkit 1026-ap3-blink-3); walking long enough would reach GrimAC's
                // 60 s transaction limit.
                if (!tickOpen) {
                    releaseDueAnswersLocked(System.currentTimeMillis());
                }
                tickOpen = !(packet instanceof ServerboundClientTickEndPacket);
            }
            if (passing) {
                return false;
            }
            if (answer) {
                answers.addLast(new Answer(new Held(packet, listener, flush), System.currentTimeMillis()));
                answersHeldTotal++;
                maxAnswersWaiting = Math.max(maxAnswersWaiting, answers.size());
                return true;
            }
            if (packet instanceof ServerboundMovePlayerPacket && teleportMoveNext) {
                // The answer to a teleport goes right behind its confirm. Kept for the tick-end, the next landing's confirm
                // overtook it when a Charged Chain's landings came back together, and GrimAC matched neither (BadPacketsN,
                // TickTimer, Simulation 6.0 - testkit 62-argrim-1025-chargechain, 2026-10-25).
                teleportMoveNext = false;
                return false;
            }
            if (packet instanceof ServerboundMovePlayerPacket) {
                if (stash != null) {
                    // A second move in one tick (a teleport accept's answer): the first goes out as it was.
                    sendLocked(stash);
                }
                stash = new Held(packet, listener, flush);
                return true;
            }
            if (packet instanceof ServerboundClientTickEndPacket) {
                boolean frozen = frozenTick;
                frozenTick = false;
                // A tick whose physics a driver skipped (AP3's momentum-keeping charge) banks in mid-air too: he did not
                // move because no game time passed for him, which is exactly what a banked tick tells the server.
                // (No settling pause after a teleport since 2026-10-27: the bank is kept through every teleport.)
                boolean bank = !busy && banked < cap() && (frozen || stillThisTick());
                if (frozen && !bank) {
                    frozenTicksSent++;
                    BlinkLog.event("charge: a tick with his physics skipped could not be banked (%s) - sent as it was",
                            busy ? "an action went out in it" : "the bank is full");
                }
                busy = false;
                if (bank) {
                    if (stash != null && stash.packet() instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
                        rotationOwed = true;
                        owedYaw = m.getYRot(0f);
                        owedPitch = m.getXRot(0f);
                    }
                    stash = null;
                    banked++;
                    bankedTotal++;
                    if (banked >= cap()) {
                        BlinkLog.event("charge: bank full (%d tick(s)) after %d ms since it was last empty; %d answer(s) "
                                + "waiting (oldest %d ms)", banked, System.currentTimeMillis() - bankEmptySinceMs,
                                answers.size(), oldestAnswerAgeMs());
                    }
                    return true;
                }
                if (stash != null) {
                    sendLocked(stash);
                    stash = null;
                }
                return false;
            }
            if (packet instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket) {
                teleportMoveNext = true;
                if (stash != null) {
                    sendLocked(stash); // a move made before this confirm goes before it
                    stash = null;
                }
            }
            boolean accept = packet instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
            if (accept && teleportInPlace) {
                inPlaceTeleports++;
            } else if (accept) {
                movingTeleports++;
            }
            if (accept && !teleportInPlace) {
                // Every server teleport keeps the bank (killer560, 2026-10-27: 'the blink should always be saved through leaps and teleports'; 'it should never drop blink if possible except if i release it'):
                // the answers stay on their steady delay, as for an in-place re-sync. GrimAC then takes the confirm before
                // the ping sent with the teleport is answered and flags the next shot (BadPacketsN, Simulation, a setback
                // - testkit 1025-blink-charge-warp / -inplace); he accepts that. Until 2026-10-27 the answers were flushed
                // here and the bank dropped.
                BlinkLog.event("charge: moving teleport confirmed - bank kept (%d), %d answer(s) still waiting their delay",
                        banked, answers.size());
            }
            if (checkedAgainstLook(packet)) {
                busy = true;
                if (stash != null) {
                    sendLocked(stash);
                    stash = null;
                }
            }
            return false;
        }
    }

    /** Packets the server judges against his look or position this tick, so their tick's move must go with them. */
    private static boolean checkedAgainstLook(Packet<?> p) {
        return p instanceof ServerboundUseItemPacket || p instanceof ServerboundUseItemOnPacket
                || p instanceof ServerboundAttackPacket || p instanceof ServerboundInteractPacket
                || p instanceof ServerboundPlayerActionPacket || p instanceof ServerboundPlayerInputPacket
                || p instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
    }

    /** A banked tick's move carried a new look that has not reached the server yet; the next move sent carries it. */
    private static volatile boolean rotationOwed;
    private static float owedYaw;
    private static float owedPitch;
    private static long rotationsCarried;

    /** Moves that had a banked tick's look added to them (testkit and log). */
    public static long rotationsCarried() {
        return rotationsCarried;
    }

    /** He did not move this tick (render thread: the tick-end is sent at the end of the client tick). */
    private static boolean stillThisTick() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !p.onGround() || p.isPassenger()) {
            return false;
        }
        double dx = p.getX() - p.xo;
        double dy = p.getY() - p.yo;
        double dz = p.getZ() - p.zo;
        return dx * dx + dy * dy + dz * dz < 1.0E-8;
    }

    /** Sends every held ping answer, in order (Charge switching off). */
    private static int releaseAnswersLocked() {
        int n = 0;
        if (connection != null && connection.isConnected()) {
            while (!answers.isEmpty()) {
                sendLocked(answers.pollFirst().held());
                n++;
            }
        }
        answers.clear();
        return n;
    }

    /** Sends the answers that have waited their second, in order. Render thread, each tick start. */
    private static void releaseDueAnswersLocked(long now) {
        if (connection == null || !connection.isConnected()) {
            return;
        }
        boolean any = false;
        long delay = answerDelayMs();
        while (!answers.isEmpty() && now - answers.peekFirst().madeMs() >= delay) {
            Answer a = answers.pollFirst();
            maxAnswerWaitMs = Math.max(maxAnswerWaitMs, now - a.madeMs());
            sendLocked(a.held());
            any = true;
        }
        if (any) {
            connection.flushChannel();
        }
    }

    private static void sendLocked(Held h) {
        passing = true;
        try {
            connection.send(h.packet(), h.listener(), h.flush());
        } finally {
            passing = false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Blink's START_CLIENT_TICK. Reads the Charge key (Toggle: a press starts charging, the next fires the bank, a press with
     * nothing banked or a shot already waiting switches Charge off; Hold: charging while the key is down, letting go fires
     * and ends it) and runs what is due. {@code allowed} is the Skyblock gate.
     */
    static void tick(Minecraft client, boolean allowed, ClientPacketListener listener) {
        BlinkConfig cfg = BlinkConfig.getInstance();
        boolean down = chargeKeyDown(client, cfg);
        boolean pressed = down && !keyWasDown;
        boolean letGo = !down && keyWasDown;
        keyWasDown = down;
        if (ticksSinceShot < Integer.MAX_VALUE) {
            ticksSinceShot++;
        }
        if (!down) {
            latched = false;
        }
        tickStamp++;
        if (!allowed && !active) {
            // Skyblock Only stops a charge from STARTING; one already running keeps its bank through the gate flipping
            // (a scoreboard gap on a server switch) - killer560 2026-10-27, never drop a blink he did not release.
            if (pressed) {
                BlinkLog.event("Charge key pressed but not used: Skyblock Only is on and this is not Skyblock");
            }
            return;
        }
        if (owner == null && handedBackCap > 0 && banked == 0 && !shotPending()) {
            handedBackCap = 0; // the handed-back bank is spent: back to Charge Ticks
        }
        if (owner != null) {
            // A feature drives Charge (AP3's Blink nodes): his Charge key neither starts, fires nor ends it meanwhile.
            if (pressed || letGo) {
                BlinkLog.event("Charge key %s ignored: %s is driving Charge (bank %d/%d)", pressed ? "press" : "release",
                        owner, banked, cap());
            }
        } else if (cfg.getChargeMode() == BlinkConfig.Mode.TOGGLE) {
            if (pressed) {
                if (!active) {
                    BlinkLog.event("Charge key pressed (Toggle): charge on");
                    turnOn(listener.getConnection());
                } else if (banked > 0 && !armed) {
                    BlinkLog.event("Charge key pressed (Toggle): fire %d banked tick(s)", banked);
                    fire(client, false);
                } else {
                    BlinkLog.event("Charge key pressed (Toggle): charge off (%s)", armed ? "a shot of " + banked
                            + " tick(s) was armed - not fired" : "nothing banked");
                    reset("key");
                }
            }
        } else {
            if (down && !active && !latched) {
                BlinkLog.event("Charge key down (Hold): charge on");
                turnOn(listener.getConnection());
            } else if (letGo && active) {
                if (banked > 0) {
                    BlinkLog.event("Charge key up (Hold): fire %d banked tick(s), then charge off", banked);
                    fire(client, true);
                } else {
                    BlinkLog.event("Charge key up (Hold): nothing banked, charge off");
                    reset("key up");
                }
            }
        }
        if (!active) {
            return;
        }
        if (++statusTicks >= STATUS_EVERY_TICKS) {
            statusTicks = 0;
            status();
        }
        if (listener.getConnection() != connection) {
            reset("new connection");
            return;
        }
        synchronized (LOCK) {
            if (!tickOpen) {
                releaseDueAnswersLocked(System.currentTimeMillis());
            }
        }
        if (shotLeft > 0) {
            int c;
            synchronized (LOCK) {
                c = shotLeft;
                banked += c;
                shotLeft = 0;
            }
            shoot(client, client.player, shotLeftWhy + ", continued", c);
        } else if (shotWanted > 0) {
            // A feature's release (shootFor): this many of the bank, the rest stays banked.
            int c = shotWanted;
            shotWanted = 0;
            requestFrom = client.player == null ? null : client.player.position();
            requestTicks = 0;
            shoot(client, client.player, "released by " + shotWantedBy, c);
        }
        if (armed && movementKeyDown(client) && McCompat.screen(client) == null) {
            armed = false;
            shoot(client, client.player, "key, fired as he moved");
        }
        if (offAfterShot && !armed && shotLeft == 0) {
            offAfterShot = false;
            reset("fired");
        }
    }

    /** The release: now if a movement key is down, else as soon as one is. */
    private static void fire(Minecraft client, boolean thenOff) {
        offAfterShot = thenOff;
        if (movementKeyDown(client) && McCompat.screen(client) == null) {
            armed = false;
            shoot(client, client.player, "key");
            return;
        }
        armed = true;
        BlinkLog.event("charge: ARMED - fired %s, the shot of %d tick(s) waits for a movement key%s",
                McCompat.screen(client) == null ? "standing still" : "with a screen open", banked,
                McCompat.screen(client) == null ? "" : " and the screen to close");
    }

    /** Hold mode: the key was down across a world change; charging waits for it to come up (as a hold does). */
    private static boolean latched;
    private static final int STATUS_EVERY_TICKS = 200;
    private static int statusTicks;
    private static long bankEmptySinceMs;
    private static long statusInPlace;
    private static long statusMoving;
    private static long statusBanked;

    /** Every 10 s while charging: the bank, the answers waiting and how late, what the server did meanwhile. */
    private static void status() {
        BlinkLog.event("charge status: bank %d/%d%s, %d answer(s) waiting (oldest %d ms, delay %d ms, longest wait so far %d "
                        + "ms, most waiting %d), last 10 s: %d tick(s) banked, %d in-place re-sync(s), %d moving teleport(s); "
                        + "%d shot(s) so far, %d set back", banked, cap(), armed ? " ARMED" : "", answersHeld(),
                oldestAnswerAgeMs(), answerDelayMs(), maxAnswerWaitMs, maxAnswersWaiting, bankedTotal - statusBanked,
                inPlaceTeleports - statusInPlace, movingTeleports - statusMoving, shots, shotsSetBack);
        statusBanked = bankedTotal;
        statusInPlace = inPlaceTeleports;
        statusMoving = movingTeleports;
    }

    private static void turnOn(Connection conn) {
        synchronized (LOCK) {
            connection = conn;
            banked = 0;
            stash = null;
            busy = false;
            armed = false;
            offAfterShot = false;
            active = true;
        }
        bankEmptySinceMs = System.currentTimeMillis();
        statusTicks = 0;
        statusBanked = bankedTotal;
        statusInPlace = inPlaceTeleports;
        statusMoving = movingTeleports;
        BlinkLog.event("charge on: bank up to %d tick(s) + the firing tick, answers %d ms late; %s", cap(), answerDelayMs(),
                BlinkLog.settings());
        if (BlinkConfig.getInstance().isChatMessages()) {
            ModChat.send("Blink", ModChat.text("charging"));
        }
    }

    /** Test hook: stands in for the Charge key. Null in play. */
    public static volatile Boolean keyHeldForTest;

    private static boolean chargeKeyDown(Minecraft client, BlinkConfig cfg) {
        Boolean forced = keyHeldForTest;
        if (forced != null) {
            return forced;
        }
        int code = cfg.getChargeKeyCode();
        if (code == com.killer560.hub.util.KeyUtil.NONE || client.getWindow() == null) {
            return false;
        }
        if (!BlinkFeature.keysReadUnder(client)) {
            // Chat, pause, the death screen, a mod screen: the key keeps the state it had (never fires, never starts).
            return keyWasDown;
        }
        return com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), code);
    }

    private static boolean movementKeyDown(Minecraft client) {
        return client.options.keyUp.isDown() || client.options.keyDown.isDown() || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown() || client.options.keyJump.isDown();
    }

    /** Runs the banked ticks of his own movement now, each with its tick-end, before this tick's own. Render thread. */
    private static void shoot(Minecraft client, LocalPlayer player, String why) {
        shoot(client, player, why, -1);
    }

    /**
     * Runs {@code count} banked ticks (all of them when negative or more than are banked); the rest stays banked, so a
     * route can split one bank across several releases (AP3's Blink Release nodes, killer560 2026-10-26).
     */
    private static void shoot(Minecraft client, LocalPlayer player, String why, int count) {
        int n;
        synchronized (LOCK) {
            n = count < 0 ? banked : Math.min(count, banked);
            banked -= n;
            if (stash != null) {
                // Cannot happen at a tick's start (the tick-end settles it), but never leave a move behind.
                sendLocked(stash);
                stash = null;
            }
        }
        if (n <= 0 || player == null || connection == null || !connection.isConnected()) {
            BlinkLog.event("charge: shot (%s) skipped - %s", why, n <= 0 ? "nothing banked" : player == null ? "no player"
                    : "no connection");
            return;
        }
        net.minecraft.world.phys.Vec3 from = player.position();
        long t0 = System.nanoTime();
        int shotPacketsAtStart = shotPacketsSent;
        int ran = 0;
        int kept = 0;
        String stoppedBy = null;
        double lastStep = 0;
        passing = true;
        try {
            shotSerial++;
            inShotLoop = true;
            for (int i = 0; i < n; i++) {
                if (player.isRemoved() || client.level == null) {
                    break;
                }
                // A driver whose next tick needs the server or the real clock ends the shot here; the rest stays banked.
                String stop = pendingEnd != null ? pendingEnd : shotGuardReason(client);
                if (stop != null) {
                    kept = n - i;
                    synchronized (LOCK) {
                        banked += kept;
                    }
                    stoppedBy = stop;
                    break;
                }
                double sofar = from.distanceTo(player.position());
                if (i > 0 && sofar + lastStep > SHOT_BLOCKS_PER_TICK) {
                    shotLeft = n - i;
                    shotLeftWhy = why;
                    break;
                }
                net.minecraft.world.phys.Vec3 before = player.position();
                int sentBefore = shotPacketsSent;
                shotMsAhead += 50L;
                shooting = true;
                try {
                    runShotTickHooks(client);
                } finally {
                    shooting = false;
                }
                if (shotPacketsSent > sentBefore) {
                    shotTickActions++;
                }
                // What ClientLevel.tickNonPassenger does for an entity each tick (javap 26.1.2), then the tick-end
                // Minecraft.tick sends after it: one whole client tick of his own movement, physics and keys.
                player.setOldPosAndRot();
                player.tickCount++;
                player.tick();
                connection.send(ServerboundClientTickEndPacket.INSTANCE);
                lastStep = before.distanceTo(player.position());
                ran++;
                // The END of this shot tick: a driver's own per-tick work (AP3 steers the next shot tick from here,
                // exactly as its END_CLIENT_TICK does between real ticks).
                runShotTickEndHooks(client);
            }
            connection.flushChannel();
        } finally {
            passing = false;
            inShotLoop = false;
        }
        if (ran == 0 && stoppedBy != null) {
            lastShotStoppedBy = stoppedBy;
            BlinkLog.event("charge: shot (%s) not started - %s; %d tick(s) kept banked", why, stoppedBy, kept);
            if (pendingEnd != null) {
                String why2 = pendingEnd;
                pendingEnd = null;
                reset(why2);
            }
            return;
        }
        double blocks = from.distanceTo(player.position());
        shots++;
        shotTicks += ran;
        lastShotTicks = ran;
        lastShotBlocks = blocks;
        ticksSinceShot = 0;
        if (!why.endsWith(", continued")) {
            lastShotFrom = from;
        }
        lastShotTo = player.position();
        lastShotTickStamp = tickStamp;
        if (banked == 0) {
            bankEmptySinceMs = System.currentTimeMillis();
        }
        requestTicks += ran;
        BlinkLog.event("charge SHOT (%s): %d banked tick(s) run in %.1f ms, moved %.2f blocks from %s to %s, %d action "
                        + "packet(s) inside it; %d answer(s) waiting (oldest %d ms); shot #%d%s; %d banked tick(s) left", why, ran,
                (System.nanoTime() - t0) / 1e6, blocks, fmt(from), fmt(player.position()), shotPacketsSent - shotPacketsAtStart,
                answersHeld(), oldestAnswerAgeMs(), shots, shotLeft > 0 ? "; SPLIT: " + shotLeft + " tick(s) left for next "
                        + "tick (the server takes at most 10 blocks a tick)" : "", banked);
        lastShotStoppedBy = stoppedBy;
        if (stoppedBy != null) {
            BlinkLog.event("charge SHOT ended early after %d of %d tick(s): %s - %d tick(s) kept banked", ran, n, stoppedBy,
                    kept);
        }
        if (pendingEnd != null) {
            String why2 = pendingEnd;
            pendingEnd = null;
            reset(why2);
        }
        if (BlinkConfig.getInstance().isChatMessages()) {
            ModChat.send("Blink", ModChat.text(String.format(java.util.Locale.ROOT, "shot %d tick(s), %.1f blocks", ran, blocks)));
        }
    }

    /**
     * Spends one banked tick now, for a caller that needs a whole tick of its own inside this one (Auto Routes' Charged
     * Chain: one etherwarp per tick, BadPacketsJ). Runs {@code first} (the caller's tick - its use goes out here), then his
     * movement tick and its tick-end, exactly as a client tick orders them. Render thread, at a tick's start.
     *
     * @return false when nothing is banked (nothing was run)
     */
    public static boolean spendTick(Runnable first) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        synchronized (LOCK) {
            if (!active || banked <= 0 || player == null || connection == null || !connection.isConnected()) {
                return false;
            }
            banked--;
            if (stash != null) {
                sendLocked(stash);
                stash = null;
            }
        }
        passing = true;
        try {
            first.run();
            player.setOldPosAndRot();
            player.tickCount++;
            player.tick();
            connection.send(ServerboundClientTickEndPacket.INSTANCE);
        } finally {
            passing = false;
        }
        spentTicks++;
        return true;
    }

    private static long spentTicks;

    /** After a run of {@link #spendTick}. What is left of the bank stays: the answers keep their steady delay. */
    public static void endSpend() {
    }

    /**
     * Sends every waiting ping answer now, in order (Blink's own hold is starting: it holds what comes next, so these must
     * go first or the server sees answers out of order - GrimAC TransactionOrder, testkit 1025-blink-charge-double). The
     * bank starts again.
     */
    public static void flushAnswers(String why) {
        int n;
        synchronized (LOCK) {
            if (answers.isEmpty()) {
                return;
            }
            n = releaseAnswersLocked();
            banked = 0;
        }
        BlinkLog.event("charge: %d waiting ping answer(s) sent (%s)", n, why);
    }

    /**
     * A hold is starting: the waiting answers go first, in order (TransactionOrder, testkit 1025-blink-charge-double), and
     * the bank is dropped even when none were waiting (before 2026-10-26 it was kept then, so a shot after the hold sent
     * the bank's ticks on top of the hold's). Returns a note for the hold's log line ("" when not charging).
     */
    static String dropForHold() {
        if (!active) {
            return "";
        }
        // The waiting answers go first (in order, ahead of the hold: TransactionOrder); the bank is KEPT (killer560
        // 2026-10-27: never drop a blink he did not release). Until then a hold dropped it.
        int n;
        synchronized (LOCK) {
            n = releaseAnswersLocked();
        }
        return String.format(java.util.Locale.ROOT, "; charging: %d waiting answer(s) sent first, bank kept (%d)", n, banked);
    }

    public static long answersHeldTotal() {
        return answersHeldTotal;
    }

    public static int answersHeld() {
        synchronized (LOCK) {
            return answers.size();
        }
    }

    public static long spentTicks() {
        return spentTicks;
    }

    /**
     * A login or respawn packet (a respawn, a dimension change) on the same connection: charging carries on and the bank
     * carries over (killer560 2026-10-27). What belonged to the old world goes: a move kept for its tick-end, an owed look,
     * the second half of a split shot (its ticks go back into the bank). Render thread.
     */
    static void onWorldChange() {
        if (!active) {
            return;
        }
        synchronized (LOCK) {
            if (stash != null && connection != null && connection.isConnected()) {
                sendLocked(stash);
            }
            stash = null;
            busy = false;
            tickOpen = false;
            teleportMoveNext = false;
            banked += shotLeft;
            shotLeft = 0;
        }
        rotationOwed = false;
        ticksSinceShot = Integer.MAX_VALUE;
        lastShotFrom = null;
        BlinkLog.event("charge: world change - still charging, bank kept (%d), %d answer(s) waiting", banked, answersHeld());
    }

    /**
     * The server starts a configuration phase: the waiting answers are play packets, so they go now; charging carries on
     * with the bank kept for when play resumes (same connection).
     */
    static void onConfiguration() {
        if (!active) {
            return;
        }
        int n;
        synchronized (LOCK) {
            if (stash != null && connection != null && connection.isConnected()) {
                sendLocked(stash);
            }
            stash = null;
            n = releaseAnswersLocked();
        }
        BlinkLog.event("charge: configuration phase - %d answer(s) sent first, bank kept (%d)", n, banked);
    }

    /** The cap an owner had when it handed the charge back to the Charge key (kept until the bank is spent or reset). */
    private static int handedBackCap;

    /**
     * {@code who} stops driving Charge without ending it: the charge and its bank stay, and his Charge key drives it from
     * now on (it fires it), with the cap {@code who} had (killer560 2026-10-27: AP3 stopping for a leap must not drop the
     * bank; a later Blink Release node, his key, or Charge switching off releases it). A later {@link #beginFor} by
     * {@code who} takes it back, bank kept. No-op for another owner.
     */
    public static void handBack(String who, String why) {
        if (who == null || !who.equals(owner)) {
            return;
        }
        handedBackCap = ownerCap;
        owner = null;
        BlinkLog.event("charge handed back to the Charge key by %s (%s): bank kept (%d of %d)", who, why, banked,
                handedBackCap);
    }

    /** Ends charging. What is banked is simply not owed: the server only saw a client a little behind. */
    static void reset(String why) {
        boolean was;
        int left;
        synchronized (LOCK) {
            was = active;
            left = banked;
            active = false;
            banked = 0;
            shotLeft = 0;
            shotWanted = 0;
            owner = null;
            handedBackCap = 0;
            lastShotTickStamp = -1;
            armed = false;
            offAfterShot = false;
            tickOpen = false;
            busy = false;
            if (stash != null && connection != null && connection.isConnected()) {
                sendLocked(stash);
            }
            stash = null;
            releaseAnswersLocked();
            connection = null;
        }
        if (was) {
            BlinkLog.event("charge off (%s), %d banked tick(s) left unused; %d shot(s), %d set back, %d tick(s) banked in all",
                    why, left, shots, shotsSetBack, bankedTotal);
        }
        if ("disconnect".equals(why)) {
            // Hold mode: a key still down from the old session waits for it to come up, as a hold does.
            // (A world change or a configuration phase no longer ends Charge at all: onWorldChange / onConfiguration.)
            if (keyWasDown) {
                latched = true;
            }
            // A teleport into the next world is not a setback of a shot made in the last one.
            ticksSinceShot = Integer.MAX_VALUE;
            lastShotFrom = null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The most ticks Charge banks: Charge Ticks, 1-20 (killer560, 2026-10-25: set to 20 it must bank 20, and past 20
     * Hypixel sets the shot back). The firing tick goes out with the burst, so a full shot is 21 ticks of movement: 1050 ms,
     * inside GrimAC's 1120 (22 banked + 1 flagged, testkit 1025-blink-charge-sweep).
     */
    public static int cap() {
        String o = owner;
        return o != null ? ownerCap : handedBackCap > 0 ? handedBackCap : settingsCap();
    }

    /** The Charge Ticks setting (1-20): the bank a charge with no number of its own fills. */
    public static int settingsCap() {
        return Math.max(1, Math.min(MAX_TICKS, BlinkConfig.getInstance().getChargeTicks()));
    }

    /** The most a charge banks whoever drives it (Hypixel sets a longer shot back, killer560 2026-10-25). */
    public static final int MAX_TICKS = 20;

    // ---------------------------------------------------------------------------------------------------------------
    // A feature driving Charge instead of the key (AP3's Blink Charge / Blink Release nodes, killer560 2026-10-26: "if i
    // do /ap3 add blinkcharge 3 it should charge 3 packets while frozen then /blinkrelease 3 would release 3. if no number
    // have it go to the max")

    /** Who drives Charge instead of the Charge key, or null (the key). While set the key does nothing to Charge. */
    private static volatile String owner;
    /** The bank {@link #owner}'s charge fills (1-20). */
    private static int ownerCap;
    /** Ticks a caller asked to fire at the next tick start ({@link #shootFor}), 0 = none. */
    private static volatile int shotWanted;
    private static String shotWantedBy = "";
    /** One count per Charge tick: a shot remembers the tick it ran in ({@link #shotThisTick}). */
    private static long tickStamp;
    private static long lastShotTickStamp = -1;
    /** The last {@link #shootFor} release as a whole (a split shot is two shots): where it started and the ticks it ran. */
    private static net.minecraft.world.phys.Vec3 requestFrom;
    private static int requestTicks;

    /**
     * {@code who} drives Charge from now on: charging starts if it was off (his Charge key's charge is taken over if it was
     * on, bank kept) and banks up to {@code ticks} (1-20; 0 or less = the Charge Ticks setting). His Charge key does
     * nothing to it until {@link #endFor}. Render thread.
     *
     * @return null when charging, else why not (Blink is off, not Skyblock, no connection)
     */
    public static String beginFor(String who, int ticks) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return "not in this build";
        }
        if (!BlinkConfig.getInstance().isEnabled()) {
            return "Blink is off";
        }
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            return "Skyblock Only is on and this is not Skyblock";
        }
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener listener = client.getConnection();
        if (listener == null || client.player == null || !listener.getConnection().isConnected()) {
            return "no connection";
        }
        int cap = ticks <= 0 ? settingsCap() : Math.min(MAX_TICKS, ticks);
        handedBackCap = 0;
        String was = owner;
        boolean wasActive = active;
        ownerCap = cap;
        owner = who;
        if (!wasActive || connection != listener.getConnection()) {
            turnOn(listener.getConnection());
            BlinkLog.event("charge started for %s: banks up to %d tick(s)", who, cap);
        } else {
            BlinkLog.event("charge %s %s: banks up to %d tick(s), %d already banked", was == null ? "taken over from the "
                    + "Charge key by" : "now driven by", who, cap, banked);
        }
        return null;
    }

    /** {@code who} is done with Charge: charging ends (whatever is still banked is not owed). No-op for another owner. */
    public static void endFor(String who, String why) {
        if (who == null || !who.equals(owner)) {
            return;
        }
        if (inShotLoop) {
            pendingEnd = who + ": " + why; // never reset under the shot loop: it ends the shot, then charging
            return;
        }
        reset(who + ": " + why);
    }

    /**
     * Fires {@code n} banked ticks (all of them when {@code n <= 0}) at the next tick start, along whatever movement is
     * being made then; the rest stays banked. The shot keeps every rule a key shot has (9.5 blocks a tick, the shot-tick
     * aura hooks). Render thread.
     *
     * @return the ticks that will fire (0: nothing banked, or not charging - nothing is fired)
     */
    public static int shootFor(String who, int n) {
        synchronized (LOCK) {
            if (!active || banked <= 0) {
                BlinkLog.event("release asked for %s by %s: nothing fired - %s", n <= 0 ? "everything" : n + " tick(s)",
                        who, !active ? "not charging" : "nothing banked");
                return 0;
            }
            int k = n <= 0 ? banked : Math.min(n, banked);
            shotWanted = k;
            shotWantedBy = who;
            BlinkLog.event("release asked for %s by %s: %d of %d banked tick(s) fire at the next tick start%s",
                    n <= 0 ? "everything" : n + " tick(s)", who, k, banked, n > banked ? " (fewer banked than asked)" : "");
            return k;
        }
    }

    /** A {@link #shootFor} release has not run to its end yet (asked, or split over two ticks). */
    public static boolean shotPending() {
        return shotWanted > 0 || shotLeft > 0 || inShotLoop;
    }

    /** A shot ran in the current tick (a feature that reads an 8-block move as a teleport must not here). */
    public static boolean shotThisTick() {
        return lastShotTickStamp == tickStamp;
    }

    /** Ticks the last {@link #shootFor} release ran in all, and how far it moved him from where it started. */
    public static int lastReleaseTicks() {
        return requestTicks;
    }

    public static double lastReleaseBlocks() {
        LocalPlayer p = Minecraft.getInstance().player;
        net.minecraft.world.phys.Vec3 from = requestFrom;
        net.minecraft.world.phys.Vec3 to = lastShotTo;
        return from == null || to == null || p == null ? 0 : from.distanceTo(to);
    }

    public static net.minecraft.world.phys.Vec3 lastReleaseFrom() {
        return requestFrom;
    }

    public static net.minecraft.world.phys.Vec3 lastReleaseTo() {
        return lastShotTo;
    }

    /** Who drives Charge now, or null (his key). */
    public static String owner() {
        return owner;
    }

    /**
     * Where a shot would put him if he fired now and kept doing what he is doing: his last tick's movement repeated for
     * every banked tick, stopping at the first block in the way. An estimate (no jump arcs, no speed changes); null when
     * nothing is banked or he is standing still.
     */
    public static net.minecraft.world.phys.Vec3 landingEstimate() {
        LocalPlayer p = Minecraft.getInstance().player;
        int n = banked + shotLeft;
        if (!active || n <= 0 || p == null) {
            return null;
        }
        net.minecraft.world.phys.Vec3 step = new net.minecraft.world.phys.Vec3(p.getX() - p.xo, 0, p.getZ() - p.zo);
        if (step.lengthSqr() < 1.0E-4) {
            return null;
        }
        net.minecraft.world.phys.AABB box = p.getBoundingBox();
        for (int i = 0; i < n; i++) {
            net.minecraft.world.phys.AABB next = box.move(step);
            if (!p.level().noCollision(p, next)) {
                break;
            }
            box = next;
        }
        return new net.minecraft.world.phys.Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
    }

    public static boolean isActive() {
        return active;
    }

    public static int banked() {
        return banked;
    }

    public static long shots() {
        return shots;
    }

    public static long shotTicks() {
        return shotTicks;
    }

    public static int lastShotTicks() {
        return lastShotTicks;
    }

    public static double lastShotBlocks() {
        return lastShotBlocks;
    }

    public static long bankedTotal() {
        return bankedTotal;
    }

    private static String fmt(net.minecraft.world.phys.Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
