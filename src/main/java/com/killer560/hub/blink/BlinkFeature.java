package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCorrections;
import com.killer560.hub.util.SkyblockGate;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Blink (cheat build only; killer560 put it on the list 2026-10-11 and asked for it to be built 2026-10-25): while a hold
 * is on, every packet this client sends to the server is kept back instead of sent, in the order it was sent, and on
 * release all of them go out together in that same order. To the server the player stands still (no movement arrives)
 * and then catches up in one burst - exactly what a lag spike looks like, which is the point: nothing is reordered,
 * nothing is invented, and every keep-alive, pong and tick-end goes out in its place among the moves.
 *
 * <p><b>Where.</b> {@code mixin/BlinkConnectionMixin} sits at the head of {@code Connection.send(Packet, listener, flush)},
 * the one public door every outgoing packet goes through, below every feature's own send tap - so Auto Routes, Smooth
 * Teleport and the rest still see their packets the tick they were made, and only the wire is late. Only the client's
 * own serverbound connection is held; the integrated server's connections pass untouched.
 *
 * <p><b>How long.</b> A hold is counted in client ticks: one {@code ServerboundClientTickEndPacket} held is one tick. At
 * {@link BlinkConfig#getHoldTicks()} the hold is released at the next tick boundary and the key has to come up before the
 * next one. Holds start and end only at START_CLIENT_TICK, so every burst is made of whole ticks.
 * A hold that somehow sends no tick-ends is still released after {@link #MAX_HOLD_MS}, and one that grows past
 * {@link #MAX_HELD_PACKETS} is released at once.
 *
 * <p><b>The server's word is final.</b> A server teleport while holding (an etherwarp landing, a leap, a pearl) is held
 * through: its accept goes in the queue in its place. One that puts him back where the hold started is the server
 * refusing the blink: what is held is released at once (all of it, in order, ahead of the setback's accept), reported
 * through {@link ServerCorrections}, and nothing stops. A world change (login or respawn packet) releases everything held,
 * in order, before the new world's packets; a configuration switch releases everything; a
 * disconnect throws the queue away (there is nothing to send it on). After a world change or a disconnect the blink is
 * off until pressed again.
 *
 * <p><b>Nothing is written.</b> His position, velocity, rotation and input are never touched; the client moves exactly as
 * it would without Blink. Other features can drive a hold through {@link #begin} / {@link #release}.
 */
public final class BlinkFeature {


    /** A hold that sends no tick-end at all (the game frozen, a world loading) is released after this long. */
    public static final long MAX_HOLD_MS = 15_000L;
    /** A hold is released at once when it grows past this many packets. */
    public static final int MAX_HELD_PACKETS = 8192;
    /** A position packet this many ticks after a release, moving him more than {@link #RUBBERBAND_BLOCKS}, is reported. */
    private static final int RUBBERBAND_WATCH_TICKS = 40;
    private static final double RUBBERBAND_BLOCKS = 0.5;

    /** Test hook: stands in for the Blink key (a harness key press never reaches the GLFW poll). Null in play. */
    public static volatile Boolean keyHeldForTest;

    private record Held(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Held> queue = new ArrayDeque<>();
    /** The connection the current hold belongs to; only its packets are held. */
    private static Connection holdConnection;
    private static volatile boolean holding;
    private static int heldTicks;
    private static int heldMoves;
    private static long holdStartMs;
    private static String holdOwner = "";

    /** What the key (or a caller of {@link #begin}) asks for; the tick turns it into holds. */
    private static boolean wanted;
    /** Who asked: "key" for the player, or the feature name passed to {@link #begin}. */
    private static String wantedBy = "key";
    /** Hold mode only: the limit released the hold while the key was still down; wait for the key to come up. */
    private static boolean holdLatched;
    private static boolean keyWasDown;

    /** Where the server last heard he was (the last move packet that actually left), for the box. */
    private static volatile Vec3 serverPos;
    /** Where the server had him when the current (or last) hold started: a teleport back here is the server refusing it. */
    private static volatile Vec3 holdStartServerPos;
    /** Teleports held through, and holds the server refused (set back to where they started), for the testkit and log. */
    private static long teleportsHeld;
    private static long refused;
    /** Frames the HUD line and the server box were actually drawn, and why the box last was not (diagnostics). */
    static volatile long hudFrames;
    static volatile long boxFrames;
    /** Frames the charge landing box was drawn (diagnostics). */
    static volatile long landingFrames;
    static volatile String boxSkip = "never asked";
    private static int ticksSinceRelease = Integer.MAX_VALUE;

    /** Totals since the game started, for the testkit and the log. */
    private static long releasedHolds;
    private static long releasedPackets;
    private static int longestHoldTicks;
    private static int lastHoldTicks;
    private static int lastHoldPackets;

    private BlinkFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("BlinkFeature.tick", BlinkFeature::tick));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
        com.killer560.hub.hud.HudElementRegistry.register(new BlinkHud());
        BlinkRender.register();
        BlinkCommands.register();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The send hook

    /**
     * {@code mixin/BlinkConnectionMixin}, at the head of {@code Connection.send(Packet, listener, flush)} on any thread.
     * Returns true when the packet was kept back (the mixin then cancels the send).
     */
    public static boolean onSend(Connection connection, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || packet == null) {
            return false;
        }
        // A look a banked Charge tick took with it rides on the next move, held or not: before 2026-10-26 a hold started
        // right after a banked turn held its moves without it (the hold skipped Charge's hook), so the server ran every
        // held tick on the old look.
        if (BlinkCharge.carryOwedLook(connection, packet, listener, flush)) {
            return true;
        }
        if (!holding) {
            // A released burst is sent as it was held: before 2026-10-26 it went through Charge's hook when Charge was on,
            // which banked its tick-ends (dropping their moves) and kept its ping answers back out of order.
            if (releasingThread != Thread.currentThread() && BlinkCharge.onSend(connection, packet, listener, flush)) {
                return true;
            }
            noteSent(connection, packet);
            return false;
        }
        synchronized (LOCK) {
            if (!holding || connection != holdConnection) {
                noteSent(connection, packet);
                return false;
            }
            // Nothing releases a hold but the key (killer560, 2026-10-25: "it should only release when you choose to
            // release it"); hits, digs and item uses are held with everything else. Menu clicks are the one exception:
            // a click (a terminal's) does not depend on where he stands, so it goes out now and the hold carries on.
            if (isMenuClick(packet) && !clicksJoinHold) {
                clicksLive++;
                return false;
            }
            queue.addLast(new Held(packet, listener, flush));
            if (packet instanceof ServerboundClientTickEndPacket) {
                heldTicks++;
            } else if (packet instanceof ServerboundMovePlayerPacket) {
                heldMoves++;
            }
            if (queue.size() > peakQueue) {
                peakQueue = queue.size();
            }
            if (queue.size() >= MAX_HELD_PACKETS) {
                // Released right here, this packet included: the queue is in order, and the send below re-enters with
                // holding off. Hold mode waits for the key to come up before starting again.
                releaseLocked("packet limit", false);
                holdLatched = true;
                wanted = false;
            }
            return true;
        }
    }

    private static long clicksLive;
    /** The thread sending a released burst right now: its sends skip Charge's hook. */
    private static volatile Thread releasingThread;
    private static long clicksAtStart;
    /** The largest the held queue has been (the testkit's leak check and {@code /blink debug}). */
    private static int peakQueue;
    /** Where the client was when the current (or last) hold started, for the release line's distance. */
    private static Vec3 holdStartClientPos;
    /** A hold was wanted and the Skyblock gate said no; logged once until the key comes up. */
    private static boolean gateRefusalLogged;

    public static int peakQueue() {
        return peakQueue;
    }

    /**
     * Inventory Walk's held clicks, sent while a hold runs and the server has not yet seen his still tick (it is in the
     * hold): they join the hold behind it instead of going out live, or the server would take them with the moving input
     * it had before the hold (GrimAC MultiActionsC, testkit 1027-iw-interop). Render thread, only around that send.
     */
    public static boolean clicksJoinHold;

    private static boolean isMenuClick(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
    }

    public static long clicksLive() {
        return clicksLive;
    }

    /** A packet that really left: a move updates where the server thinks he is. */
    private static void noteSent(Connection connection, Packet<?> packet) {
        com.killer560.hub.invwalk.InventoryWalkFeature.noteLeft(packet);
        if (!(packet instanceof ServerboundMovePlayerPacket move) || connection.getSending() != PacketFlow.SERVERBOUND) {
            return;
        }
        Vec3 was = serverPos;
        if (move.hasPosition()) {
            double bx = was == null ? 0 : was.x;
            double by = was == null ? 0 : was.y;
            double bz = was == null ? 0 : was.z;
            serverPos = new Vec3(move.getX(bx), move.getY(by), move.getZ(bz));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The tick

    static void tick(Minecraft client) {
        if (ticksSinceRelease < Integer.MAX_VALUE) {
            ticksSinceRelease++;
        }
        BlinkConfig cfg = BlinkConfig.getInstance();
        ClientPacketListener listener = client.getConnection();
        if (client.player != null && client.level != null) {
            // Counted whether Blink is on or not (ship3-1027): counted only while it was on, the settle after a world change
            // stayed pending with Blink off, and switching it on later made the first hold wait for him to land - a hold
            // pressed mid-jump began only on landing (testkit 1025-blink-jump: 2.4 of 6.9 blocks held).
            tickSpawnWait(client);
        }
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || !cfg.isEnabled() || listener == null) {
            // Blink switched off, or no connection: the only ends besides his own release (killer560 2026-10-27).
            wanted = false;
            // A Hold key that was down when the connection went waits for it to come up on the next one: a key still down
            // after a rejoin started a hold during the join (2026-10-26).
            if (keyWasDown) {
                holdLatched = true;
            }
            keyWasDown = false;
            if (holding) {
                release(cfg.isEnabled() ? "no connection" : "switched off");
            }
            BlinkCharge.reset(cfg.isEnabled() ? "disconnect" : "switched off");
            return;
        }
        if (client.player == null || client.level == null) {
            // Connected with no world (loading, a configuration phase): a hold cannot go on (its packets belong to the old
            // world and must go before the new one's), Charge and its bank carry on.
            if (holding) {
                release("no world - loading");
            }
            return;
        }

        readKey(client, cfg);

        if (holding) {
            boolean overTicks = heldTicks >= cfg.getHoldTicks();
            boolean overTime = System.currentTimeMillis() - holdStartMs > MAX_HOLD_MS;
            if (overTicks || overTime) {
                // Past 20 ticks Hypixel sets the blink back, so a full hold goes now; the key has to come up before
                // the next one.
                release(overTicks ? "tick limit" : "time limit");
                if ("key".equals(wantedBy)) {
                    wanted = false;
                    holdLatched = true;
                    BlinkLog.event("Hold key still down after the limit - the next hold waits for it to come up");
                } else if ("command".equals(wantedBy)) {
                    // /blink is one hold: before 2026-10-26 it started again every tick after its limit, forever.
                    wanted = false;
                }
            }
        }
        if (holding && !wanted) {
            release("key".equals(holdOwner) ? "key up" : "ended by " + holdOwner);
        }
        if (!holding && wanted && (spawnWaitTicks > 0 || spawnSettle)) {
            // A new world: the hold waits until the server's spawn teleport has been confirmed (campaign-blink-1027).
        } else if (!holding && wanted) {
            if (!SkyblockGate.allows()) {
                if (!gateRefusalLogged) {
                    gateRefusalLogged = true;
                    BlinkLog.event("hold wanted by %s but not started: Skyblock Only is on and this is not Skyblock", wantedBy);
                }
                wanted = false;
            } else {
                start(listener.getConnection(), wantedBy);
            }
        }
        BlinkCharge.tick(client, SkyblockGate.allows(), listener);
    }

    /** The Hold key: held, a hold; up, the release. A limit release waits for the key to come up. */
    private static void readKey(Minecraft client, BlinkConfig cfg) {
        boolean down = keyDown(client, cfg);
        keyWasDown = down;
        if (!down) {
            gateRefusalLogged = false;
            holdLatched = false;
            if ("key".equals(wantedBy)) {
                wanted = false;
            }
            return;
        }
        if (!holdLatched) {
            wanted = true;
            wantedBy = "key";
        }
    }

    private static boolean keyDown(Minecraft client, BlinkConfig cfg) {
        Boolean forced = keyHeldForTest;
        if (forced != null) {
            return forced;
        }
        int code = cfg.getHoldKeyCode();
        if (code == KeyUtil.NONE || client.getWindow() == null) {
            return false;
        }
        if (!keysReadUnder(client)) {
            // Chat, pause, the death screen, a mod screen: the key keeps the state it had, so opening one neither ends a
            // hold nor (typing the key's letter) starts one (killer560 2026-10-27: never drop a blink he did not release).
            return keyWasDown;
        }
        return KeyUtil.isBindDown(client.getWindow(), code);
    }

    /**
     * Whether Blink's keys are read with the current screen up: none, or a container menu (a terminal, a chest). Inside a
     * menu the Hold key must keep holding and the Charge key keep its state (killer560, 2026-10-25: a blink "while being in a
     * terminal menu and clicking"); before 2026-10-26 any screen read as the key up, so opening a terminal mid-hold released
     * it. Chat, signs and the mod's own screens still read as up (the key is a letter typed there).
     */
    static boolean keysReadUnder(Minecraft client) {
        Object screen = McCompat.screen(client);
        return screen == null || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Starting and releasing

    private static void start(Connection connection, String owner) {
        if (holding) {
            return;
        }
        if (connection == null || !connection.isConnected()) {
            BlinkLog.event("hold wanted by %s but not started: no connection", owner);
            return;
        }
        // Charging: the waiting answers go first (TransactionOrder) and the bank is dropped whether or not any were
        // waiting - a bank kept through a hold and fired after it adds its ticks to the hold's (more than GrimAC allows).
        String charge = BlinkCharge.dropForHold();
        synchronized (LOCK) {
            if (holding || !connection.isConnected()) {
                return;
            }
            holdConnection = connection;
            heldTicks = 0;
            heldMoves = 0;
            holdStartMs = System.currentTimeMillis();
            holdOwner = owner;
            holdStartServerPos = serverPos;
            clicksAtStart = clicksLive;
            holding = true;
        }
        Minecraft mc = Minecraft.getInstance();
        holdStartClientPos = mc.player == null ? null : mc.player.position();
        BlinkLog.event("hold started by %s at %s; server has him at %s; %s%s", owner, BlinkLog.fmt(holdStartClientPos),
                BlinkLog.fmt(serverPos), BlinkLog.settings(), charge);
        if (BlinkConfig.getInstance().isChatMessages()) {
            ModChat.send("Blink", ModChat.text("holding packets"));
        }
    }

    /** Releases the current hold, if any, sending everything held in order. Callable from any thread. */
    public static void release(String why) {
        synchronized (LOCK) {
            releaseLocked(why, false);
        }
    }

    /**
     * @param dropStale drop the held moves and tick-ends and send the rest. No caller does since 2026-10-26: what is left
     *                  then (a sprint command with a ping answer behind it and no flying between) is GrimAC Post (testkit
     *                  1025-blink-refused and -world). It was for holds past 22 ticks, which drew TimerLimit; Hold Ticks
     *                  stops at 20 now.
     */
    private static void releaseLocked(String why, boolean dropStale) {
        if (!holding) {
            return;
        }
        holding = false;
        Connection connection = holdConnection;
        holdConnection = null;
        List<Held> out = new ArrayList<>(queue);
        queue.clear();
        int ticks = heldTicks;
        int sent = 0;
        int dropped = 0;
        if (connection != null && connection.isConnected()) {
            for (Held h : out) {
                if (dropStale && (h.packet() instanceof ServerboundMovePlayerPacket
                        || h.packet() instanceof ServerboundClientTickEndPacket)) {
                    dropped++;
                    continue;
                }
                // holding is off, so this send passes our hook (and notes the move for the box), and Charge's too.
                releasingThread = Thread.currentThread();
                try {
                    connection.send(h.packet(), h.listener(), false);
                } finally {
                    releasingThread = null;
                }
                sent++;
            }
            connection.flushChannel();
        } else {
            dropped = out.size();
        }
        if (sent > 0 && BlinkCharge.isActive()) {
            // The hold sent Charge's waiting answers at its start and its own in the burst: the kept bank is no longer
            // covered by GrimAC's lag allowance and is backed again on the next still ticks (BlinkCharge.unbacked).
            BlinkCharge.answersWentEarly("a hold was released while charging");
        }
        releasedHolds++;
        releasedPackets += sent;
        lastHoldTicks = ticks;
        lastHoldPackets = sent;
        longestHoldTicks = Math.max(longestHoldTicks, ticks);
        ticksSinceRelease = 0;
        // What the burst carried, by packet type, so a test log shows exactly what the server got at once.
        java.util.TreeMap<String, Integer> kinds = new java.util.TreeMap<>();
        for (Held h : out) {
            kinds.merge(h.packet().getClass().getSimpleName().replace("Serverbound", "").replace("Packet", ""), 1,
                    Integer::sum);
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 now = mc.player == null ? null : mc.player.position();
        double moved = now == null || holdStartClientPos == null ? 0 : now.distanceTo(holdStartClientPos);
        BlinkLog.event("released (%s) after %d ms, owner %s: %d tick(s), %d move(s), %d packet(s) sent, %d dropped, %d "
                        + "menu click(s) went live during it; client moved %.2f blocks during the hold, %s -> %s; server now has "
                        + "him at %s; burst %s; release #%d", why, System.currentTimeMillis() - holdStartMs, holdOwner, ticks,
                heldMoves, sent, dropped, clicksLive - clicksAtStart, moved, BlinkLog.fmt(holdStartClientPos),
                BlinkLog.fmt(now), BlinkLog.fmt(serverPos), kinds, releasedHolds);
        if (BlinkConfig.getInstance().isChatMessages()) {
            String extra = dropped > 0 ? ", " + dropped + " stale dropped" : "";
            ModChat.send("Blink", ModChat.text("released " + ticks + " tick(s), " + sent + " packet(s) (" + why + extra + ")"));
        }
    }

    /**
     * Starts a hold on behalf of another feature (an AP3 or Auto Routes node). The hold follows the same tick limit and
     * the same release rules as the key's. No-op in a legit build, with Blink switched off, or without a connection.
     */
    public static void begin(String owner) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || !BlinkConfig.getInstance().isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) {
            return;
        }
        wanted = true;
        wantedBy = owner == null ? "feature" : owner;
        start(client.getConnection().getConnection(), wantedBy);
    }

    /** Ends what {@link #begin} started (the release itself is immediate). */
    public static void end(String why) {
        wanted = false;
        release(why);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Server packets

    /**
     * {@code mixin/BlinkPacketListenerMixin}: a server position packet, render thread, before vanilla applies it (so the
     * accept and the move vanilla answers with go out after everything held).
     */
    /**
     * Ticks a Hold key still down across a world change waits before its new hold: until the new world's spawn teleport is
     * confirmed (then 2 more ticks), at most 40. A hold started on the new world's first tick held that confirm and the first
     * moves: GrimAC Simulation 0.03-0.065 on ~20 ticks (1026-blink-death (a), on 3011406a too).
     */
    private static int spawnWaitTicks;
    private static boolean spawnSettle;
    private static int spawnSettleTicks;

    /** The wait for the spawn teleport and the settle onto the ground after a world change, one tick of each. */
    private static void tickSpawnWait(Minecraft client) {
        if (spawnWaitTicks > 0) {
            spawnWaitTicks--;
        }
        if (spawnSettle) {
            // After the spawn teleport he drops the 0.1 the server spawns him above the ground; held, those first ticks
            // drew GrimAC Simulation 0.029 decaying x8 on 26.2 (1026-blink-death (a), 1 run in 2). The new hold waits until
            // he stands on the ground (at most 60 ticks after the world change).
            spawnSettleTicks++;
            if ((spawnWaitTicks == 0 && client.player.onGround() && client.player.getY() == client.player.yo)
                    || spawnSettleTicks > 60) {
                spawnSettle = false;
            }
        }
    }

    public static void onServerPosition(Vec3 from, Vec3 to) {
        if (spawnWaitTicks > 2) {
            spawnWaitTicks = 2;
        }
        boolean wasHolding = holding;
        double dist = from == null || to == null ? 0 : from.distanceTo(to);
        // The server putting him back where a blink started is the server refusing that blink: what is held is no use
        // to it (it ignores moves until its teleport is accepted), so the blink is dropped, honored, never fought.
        Vec3 start = holdStartServerPos;
        boolean backToStart = start != null && to != null && dist > RUBBERBAND_BLOCKS && to.distanceTo(start) < 0.5;
        BlinkCharge.noteServerTeleport(from, to, dist);
        if (wasHolding && !backToStart) {
            // An etherwarp landing, a leap, a lag-back to somewhere new: keep holding. The accept vanilla answers with goes
            // into the queue in its place, so on release the server sees a client that got the teleport late - every
            // held move before the accept is ignored by it, every one after starts from the new spot.
            teleportsHeld++;
            BlinkLog.event("server teleport while holding: %.2f blocks, client at %s, server put him at %s (%d tick(s) "
                    + "into the hold) - kept holding, the accept goes out in order; teleport #%d held through", dist,
                    BlinkLog.fmt(from), BlinkLog.fmt(to), heldTicks, teleportsHeld);
            if (to != null) {
                serverPos = to;
            }
            return;
        }
        if (wasHolding) {
            // A refusal releases everything held, moves and tick-ends included, in order and before the setback's accept:
            // to the server they are moves made before he heard of the setback, which it ignores until the accept. Until
            // 2026-10-26 the moves and tick-ends were dropped, which left the held sprint/sneak commands after his last
            // flying with a ping answer behind them and no flying between: GrimAC Post "entity action" (testkit
            // 1025-blink-refused). Holds are 20 ticks at most since the rework, so the tick-ends no longer reach TimerLimit.
            synchronized (LOCK) {
                releaseLocked(backToStart ? "server set him back to the blink's start - blink refused" : "server teleport",
                        false);
            }
        }
        if (backToStart) {
            refused++;
            BlinkLog.event("REFUSED: the server put him back at the hold's start %s (%.2f blocks) while the client was at %s "
                    + "- everything held was sent in order ahead of the setback's accept; refusal #%d", BlinkLog.fmt(to), dist,
                    BlinkLog.fmt(from), refused);
            ServerCorrections.report("Blink", "the server refused a blink and put you back where it started - dropped, "
                    + "carrying on", dist);
        } else if (wasHolding) {
            ServerCorrections.report("Blink", "while holding - released, carrying on", dist);
        } else if (ticksSinceRelease <= RUBBERBAND_WATCH_TICKS && dist > RUBBERBAND_BLOCKS) {
            // A teleport soon after a release that is not back to the blink's start: an etherwarp landing, a leap. Logged.
            BlinkLog.event("server teleport %d tick(s) after a release: %.2f blocks, client at %s, server put him at %s - "
                    + "not back to the hold's start, not a refusal", ticksSinceRelease, dist, BlinkLog.fmt(from),
                    BlinkLog.fmt(to));
        }
        if (to != null) {
            serverPos = to;
        }
    }

    public static long teleportsHeld() {
        return teleportsHeld;
    }

    public static long refused() {
        return refused;
    }

    public static long hudFrames() {
        return hudFrames;
    }

    public static long landingFrames() {
        return landingFrames;
    }

    public static long boxFrames() {
        return boxFrames;
    }

    public static String boxSkip() {
        return boxSkip;
    }

    /** A login or respawn packet: a new world. What is held is released, all of it, in order; the blink ends. */
    public static void onWorldChange() {
        if (holding || BlinkCharge.isActive()) {
            BlinkLog.event("world change (login or respawn packet): holding %s (%d packet(s)), charging %s (bank %d)", holding,
                    heldPackets(), BlinkCharge.isActive(), BlinkCharge.banked());
        }
        BlinkCharge.onWorldChange();
        serverPos = null;
        synchronized (LOCK) {
            // Everything, in order, before the new world's packets: dropping the moves and tick-ends left a held sprint
            // command after his last flying with a ping answer behind it, GrimAC Post (testkit 1025-blink-world,
            // 2026-10-26). The answers in the burst tell GrimAC those moves came before the world change.
            releaseLocked("world change", false);
        }
        // The new world's first position packet is the move into it, not a rubberband after the release.
        ticksSinceRelease = Integer.MAX_VALUE;
        wanted = false;
        // The held packets cannot cross into the new world (sent above, in order); a Hold key still down starts a new hold
        // there (killer560 2026-10-27: never drop a blink he did not release). From 2026-10-26 to then it waited for the
        // key to come up, because a hold on the new world's first tick drew GrimAC Post (testkit 1025-blink-world).
        holdLatched = false;
        spawnWaitTicks = 40;
        spawnSettle = true;
        spawnSettleTicks = 0;
        if (keyWasDown) {
            BlinkLog.event("Hold key still down across the world change - a new hold goes on in the new world");
        }
    }

    /** The server starts a configuration phase: everything held is a play packet for it, so all of it goes now. */
    public static void onConfigurationStart() {
        BlinkCharge.onConfiguration();
        synchronized (LOCK) {
            releaseLocked("configuration", false);
        }
        wanted = false;
    }

    /** Netty thread (Fabric's DISCONNECT): the queue has nowhere to go. */
    private static void onDisconnect() {
        BlinkCharge.reset("disconnect");
        synchronized (LOCK) {
            if (holding) {
                BlinkLog.event("disconnected while holding: %d packet(s) (%d tick(s)) discarded - nothing to send them on",
                        queue.size(), heldTicks);
            }
            holding = false;
            holdConnection = null;
            queue.clear();
        }
        wanted = false;
        serverPos = null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // State, for the HUD, the box, other features and the testkit

    public static boolean isHolding() {
        return holding;
    }

    public static int heldTicks() {
        return heldTicks;
    }

    public static int heldMoves() {
        return heldMoves;
    }

    public static int heldPackets() {
        synchronized (LOCK) {
            return queue.size();
        }
    }

    public static String holdOwner() {
        return holdOwner;
    }

    public static Vec3 serverPosition() {
        return serverPos;
    }

    public static long releasedHolds() {
        return releasedHolds;
    }

    public static long releasedPackets() {
        return releasedPackets;
    }

    public static int longestHoldTicks() {
        return longestHoldTicks;
    }

    public static int lastHoldTicks() {
        return lastHoldTicks;
    }

    public static int lastHoldPackets() {
        return lastHoldPackets;
    }

    /** Ticks since the last release (capped), for {@code /blink debug}. */
    static int ticksSinceRelease() {
        return ticksSinceRelease;
    }

    static boolean holdLatched() {
        return holdLatched;
    }

    static String wantedBy() {
        return wanted ? wantedBy : "-";
    }

    static long clicksLiveTotal() {
        return clicksLive;
    }

    /** The HUD line: the charge bank when Charge is on, the hold while one runs. Null when there is nothing to show. */
    static Component statusLine() {
        BlinkConfig cfg = BlinkConfig.getInstance();
        StringBuilder sb = new StringBuilder();
        if (BlinkCharge.isActive()) {
            sb.append("§cCharge §f").append(BlinkCharge.banked()).append("§7/").append(BlinkCharge.cap());
            if (BlinkCharge.isArmed()) {
                sb.append(" §e(move to fire)");
            }
        }
        if (holding) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append("§cHold §f").append(heldTicks).append("§7/").append(cfg.getHoldTicks());
        }
        return sb.length() == 0 ? null : Component.literal(sb.toString());
    }
}
