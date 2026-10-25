package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
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
 * <p><b>Shooting.</b> At the start of the tick he first presses a movement key (Shoot When You Move), or when Charge is
 * switched off, the client runs the banked ticks of his own movement - the vanilla player tick, driven by the keys he is
 * really holding - one after another, each followed by its tick-end, before this tick's own. The server receives them
 * together and moves him that far in one go, and so does his screen. Nothing writes position or velocity: every one of
 * those ticks is the game's own physics on real key presses.
 *
 * <p><b>Never runs out.</b> The bank is empty after a shot and fills again whenever he stands still. A server teleport
 * (an etherwarp, a leap) goes out live and leaves the bank as it is.
 */
public final class BlinkCharge {

    private static final Logger LOGGER = ModLog.get("killer560smod-blink");

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
                    stash = null;
                    banked++;
                    bankedTotal++;
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
            if (packet instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
                    && !answers.isEmpty()) {
                // A server teleport (an etherwarp, a leap) is confirmed. GrimAC counts a teleport as reached by the
                // client only once the ping sent with it is answered; with the answers still a second late it took the
                // confirm for an unsent one and dropped every move after it - the next shot never reached the server
                // (testkit 1025-blink-charge-warp / -far, 2026-10-25). So the waiting answers go first, in order; the
                // bank starts again here and refills in a second.
                int n = releaseAnswersLocked();
                int lost = banked;
                banked = 0;
                LOGGER.info("[Blink] charge: server teleport - {} waiting ping answer(s) sent first; {} banked tick(s) start "
                        + "again", n, lost);
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
        while (!answers.isEmpty() && now - answers.peekFirst().madeMs() >= ANSWER_DELAY_MS) {
            sendLocked(answers.pollFirst().held());
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
     * Blink's START_CLIENT_TICK, when Charge is the style. {@code want} is the key's (or a caller's) wish. Returns true when
     * charging goes on although the key said stop: in Toggle, a press with ticks banked is the shot, not "off" (killer560,
     * 2026-10-25: charge, run about, fire when he likes); a press with nothing banked switches Charge off.
     */
    static boolean tick(Minecraft client, boolean want, ClientPacketListener listener) {
        if (!want && active && banked > 0 && BlinkConfig.getInstance().getMode() == BlinkConfig.Mode.TOGGLE) {
            shoot(client, client.player, "key");
            return true;
        }
        return tickInner(client, want, listener);
    }

    private static boolean tickInner(Minecraft client, boolean want, ClientPacketListener listener) {
        LocalPlayer player = client.player;
        Connection conn = listener.getConnection();
        if (want && !active) {
            synchronized (LOCK) {
                connection = conn;
                banked = 0;
                stash = null;
                busy = false;
                active = true;
            }
            LOGGER.info("[Blink] charge on (bank up to {} ticks + the firing tick, shoot when you move {})",
                    cap(), BlinkConfig.getInstance().isShootOnMove());
            if (BlinkConfig.getInstance().isChatMessages()) {
                ModChat.send("Blink", ModChat.text("charging"));
            }
            return false;
        }
        if (!active) {
            return false;
        }
        if (conn != connection) {
            reset("new connection");
            return false;
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
            shoot(client, player, shotLeftWhy + ", continued");
        }
        boolean moveKey = movementKeyDown(client);
        boolean shootNow = banked > 0 && (!want || (BlinkConfig.getInstance().isShootOnMove() && moveKey
                && McCompat.screen(client) == null));
        if (shootNow) {
            shoot(client, player, want ? "you moved" : "charge off");
        }
        if (!want) {
            reset("charge off");
        }
        return false;
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
            return;
        }
        net.minecraft.world.phys.Vec3 from = player.position();
        long t0 = System.nanoTime();
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
                runShotTickHooks(client);
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
        LOGGER.info(String.format(java.util.Locale.ROOT, "[Blink] charge shot (%s): %d banked tick(s) run in %.1f ms, moved "
                        + "%.2f blocks from %s to %s; shot #%d%s", why, ran, (System.nanoTime() - t0) / 1e6, blocks,
                fmt(from), fmt(player.position()), shots, shotLeft > 0 ? "; " + shotLeft + " tick(s) left for next tick "
                        + "(the server takes at most 10 blocks a tick)" : ""));
        if (BlinkConfig.getInstance().isChatMessages()) {
            ModChat.send("Blink", ModChat.text(String.format(java.util.Locale.ROOT, "shot %d tick(s), %.1f blocks", ran, blocks)));
        }
        if (BlinkConfig.getInstance().isHoldAfterShot() && ran > 0) {
            // The shot brought him level with real time again, so a full blink fits straight after it.
            BlinkFeature.startFollowUp();
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
        LOGGER.info("[Blink] charge: {} waiting ping answer(s) sent ({})", n, why);
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
            LOGGER.info("[Blink] charge off ({}), {} banked tick(s) left unused", why, left);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The most ticks Charge banks: one under Max Ticks, because the tick a shot fires in sends its own movement with the
     * burst - a full Max Ticks of movement at once. Banking Max Ticks itself left GrimAC 20 ms of TimerLimit headroom and
     * flagged once in a run (testkit 1025-blink-charge-actions, 2026-10-25); one fewer leaves 70 ms.
     */
    public static int cap() {
        return Math.max(1, BlinkConfig.getInstance().getMaxTicks() - 1);
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
