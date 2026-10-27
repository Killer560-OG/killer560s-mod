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
                    oldestAnswerAgeMs(), teleportInPlace ? "in place (re-sync), bank kept" : "moved, bank starts again");
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
            if (!answer) {
                if (!passing && !tickOpen) {
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
                boolean bank = !busy && banked < cap() && stillThisTick();
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
            if (accept && !teleportInPlace && !answers.isEmpty()) {
                // A server teleport (an etherwarp, a leap) is confirmed. GrimAC counts a teleport as reached by the
                // client only once the ping sent with it is answered; with the answers still a second late it took the
                // confirm for an unsent one and dropped every move after it - the next shot never reached the server
                // (testkit 1025-blink-charge-warp / -far, 2026-10-25). So the waiting answers go first, in order; the
                // bank starts again here and refills in a second.
                int n = releaseAnswersLocked();
                int lost = banked;
                banked = 0;
                bankEmptySinceMs = System.currentTimeMillis();
                BlinkLog.event("charge: moving teleport confirmed - %d waiting ping answer(s) sent first; %d banked tick(s) "
                        + "dropped, the bank starts again", n, lost);
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
        if (!allowed) {
            if (pressed) {
                BlinkLog.event("Charge key pressed but not used: Skyblock Only is on and this is not Skyblock");
            }
            reset("not allowed here");
            return;
        }
        if (cfg.getChargeMode() == BlinkConfig.Mode.TOGGLE) {
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
            synchronized (LOCK) {
                banked += shotLeft;
                shotLeft = 0;
            }
            shoot(client, client.player, shotLeftWhy + ", continued");
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
        if (code == com.killer560.hub.util.KeyUtil.NONE || !BlinkFeature.keysReadUnder(client) || client.getWindow() == null) {
            return false;
        }
        return com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), code);
    }

    private static boolean movementKeyDown(Minecraft client) {
        return client.options.keyUp.isDown() || client.options.keyDown.isDown() || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown() || client.options.keyJump.isDown();
    }

    /** Runs the banked ticks of his own movement now, each with its tick-end, before this tick's own. Render thread. */
    private static void shoot(Minecraft client, LocalPlayer player, String why) {
        int n;
        synchronized (LOCK) {
            n = banked;
            banked = 0;
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
        double lastStep = 0;
        passing = true;
        try {
            for (int i = 0; i < n; i++) {
                if (player.isRemoved() || client.level == null) {
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
            }
            connection.flushChannel();
        } finally {
            passing = false;
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
        bankEmptySinceMs = System.currentTimeMillis();
        BlinkLog.event("charge SHOT (%s): %d banked tick(s) run in %.1f ms, moved %.2f blocks from %s to %s, %d action "
                        + "packet(s) inside it; %d answer(s) waiting (oldest %d ms); shot #%d%s", why, ran,
                (System.nanoTime() - t0) / 1e6, blocks, fmt(from), fmt(player.position()), shotPacketsSent - shotPacketsAtStart,
                answersHeld(), oldestAnswerAgeMs(), shots, shotLeft > 0 ? "; SPLIT: " + shotLeft + " tick(s) left for next "
                        + "tick (the server takes at most 10 blocks a tick)" : "");
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
        int n;
        int lost;
        boolean wasArmed;
        synchronized (LOCK) {
            n = releaseAnswersLocked();
            lost = banked;
            banked = 0;
            shotLeft = 0;
            wasArmed = armed;
            armed = false;
            bankEmptySinceMs = System.currentTimeMillis();
        }
        return String.format(java.util.Locale.ROOT, "; charging: %d waiting answer(s) sent first, %d banked tick(s) dropped%s",
                n, lost, wasArmed ? ", the armed shot dropped" : "");
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
        if ("world change".equals(why) || "disconnect".equals(why)) {
            // Hold mode: a key still down from the old world waits for it to come up, as a hold does.
            latched = keyWasDown;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The most ticks Charge banks: Charge Ticks, 1-20 (killer560, 2026-10-25: set to 20 it must bank 20, and past 20
     * Hypixel sets the shot back). The firing tick goes out with the burst, so a full shot is 21 ticks of movement: 1050 ms,
     * inside GrimAC's 1120 (22 banked + 1 flagged, testkit 1025-blink-charge-sweep).
     */
    public static int cap() {
        return Math.max(1, BlinkConfig.getInstance().getChargeTicks());
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
