package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
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
import org.slf4j.Logger;

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
 * {@link BlinkConfig#getMaxTicks()} the hold is released at the next tick boundary, and then either stops or starts again
 * ({@link BlinkConfig.LimitAction}). Holds start and end only at START_CLIENT_TICK, so every burst is made of whole ticks.
 * A hold that somehow sends no tick-ends is still released after {@link #MAX_HOLD_MS}, and one that grows past
 * {@link #MAX_HELD_PACKETS} is released at once.
 *
 * <p><b>The server's word is final.</b> A server position packet while holding releases everything first (the server
 * ignores moves until its teleport is accepted, so the held ones do no harm) and lets the accept and the move after it go
 * straight out; the hold starts again next tick if the key still says so - the correction is honored, reported through
 * {@link ServerCorrections} when it moved him, and nothing stops. A world change (login or respawn packet) releases what is
 * held but drops the held moves and tick-ends, which belong to the world he just left; a configuration switch releases everything; a
 * disconnect throws the queue away (there is nothing to send it on). After a world change or a disconnect the blink is
 * off until pressed again.
 *
 * <p><b>Nothing is written.</b> His position, velocity, rotation and input are never touched; the client moves exactly as
 * it would without Blink. Other features can drive a hold through {@link #begin} / {@link #release}.
 */
public final class BlinkFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-blink");

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
        if (!holding) {
            noteSent(connection, packet);
            return false;
        }
        synchronized (LOCK) {
            if (!holding || connection != holdConnection) {
                noteSent(connection, packet);
                return false;
            }
            // A hit is its own packet since 26.1 (ServerboundAttackPacket, javap 26.1.2 and 26.2: MultiPlayerGameMode.attack
            // sends it); ServerboundInteractPacket is now only the entity right click. Both count.
            if ((packet instanceof ServerboundAttackPacket || packet instanceof ServerboundInteractPacket)
                    && BlinkConfig.getInstance().isReleaseOnHit()) {
                // A hit (or entity click) is never held behind other held packets: a burst carrying two hits lets the
                // server apply the first (the kill, the knockback) and then judge the second against it, a swing the
                // client made before it could have seen that - GrimAC's Hitboxes, every time (testkit 1025-blink-attack
                // against its no-Blink control, 2026-10-25). Release what is held, let this tick go out live (the hit,
                // its swing, the move), and hold again from the next tick.
                releaseLocked("hit", false);
                return false;
            }
            if (packet instanceof ServerboundPlayerActionPacket action && isDigEdge(action.getAction())
                    && BlinkConfig.getInstance().isReleaseOnDig()) {
                // GrimAC times every break on arrival: start to finish (held together, a ~300 ms stone break read as
                // instant - FastBreak diff=300ms) and finish to the next start (a held finish landed 49 ms before the
                // live next start - FastBreak delay=49ms), testkit 1025-blink-dig 2026-10-25. So every dig edge (start,
                // finish, abort) goes out live, on real time, after what was held.
                releaseLocked("dig", false);
                return false;
            }
            if (BlinkConfig.getInstance().isReleaseOnCharge() && isChargeEdge(packet)) {
                // The server times a charged use (a bow draw, eating, a shield) by its own ticks between the start and
                // the release. Held together they arrived together and a 25-tick bow draw fired nothing - the server
                // drops a shot under 0.1 charge (testkit 1025-blink-bow, 2026-10-25: 3 of 5 arrows). Both edges go live.
                // An instant right click (an ability, an etherwarp) is not a charged use and stays held.
                releaseLocked("item use", false);
                return false;
            }
            queue.addLast(new Held(packet, listener, flush));
            if (packet instanceof ServerboundClientTickEndPacket) {
                heldTicks++;
            } else if (packet instanceof ServerboundMovePlayerPacket) {
                heldMoves++;
            }
            if (queue.size() >= MAX_HELD_PACKETS) {
                // Released right here, this packet included: the queue is in order, and the send below re-enters with
                // holding off. Hold mode waits for the key to come up before starting again.
                releaseLocked("packet limit", false);
                holdLatched = true;
                wanted = wanted && BlinkConfig.getInstance().getLimitAction() == BlinkConfig.LimitAction.PULSE;
            }
            return true;
        }
    }

    /** The start of a use whose item charges (use duration above 0), or the release of one. Render thread. */
    private static boolean isChargeEdge(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket action) {
            return action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM;
        }
        if (!(packet instanceof ServerboundUseItemPacket use)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || use.getHand() == null) {
            return false;
        }
        return client.player.getItemInHand(use.getHand()).getUseDuration(client.player) > 0;
    }

    private static boolean isDigEdge(ServerboundPlayerActionPacket.Action a) {
        return a == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || a == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                || a == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
    }

    /** A packet that really left: a move updates where the server thinks he is. */
    private static void noteSent(Connection connection, Packet<?> packet) {
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
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || !cfg.isEnabled() || client.player == null
                || client.level == null || listener == null) {
            wanted = false;
            holdLatched = false;
            keyWasDown = false;
            if (holding) {
                release(cfg.isEnabled() ? "no world" : "switched off");
            }
            return;
        }

        readKey(client, cfg);

        if (holding) {
            boolean overTicks = heldTicks >= cfg.getMaxTicks();
            boolean overTime = System.currentTimeMillis() - holdStartMs > MAX_HOLD_MS;
            if (overTicks || overTime) {
                release(overTicks ? "tick limit" : "time limit");
                if (cfg.getLimitAction() == BlinkConfig.LimitAction.RELEASE) {
                    wanted = false;
                    holdLatched = "key".equals(wantedBy);
                }
            }
        }
        if (holding && !wanted) {
            release("key");
        }
        if (!holding && wanted) {
            if (!SkyblockGate.allows()) {
                wanted = false;
                return;
            }
            start(listener.getConnection(), wantedBy);
        }
    }

    private static void readKey(Minecraft client, BlinkConfig cfg) {
        boolean down = keyDown(client, cfg);
        boolean pressed = down && !keyWasDown;
        keyWasDown = down;
        if (cfg.getMode() == BlinkConfig.Mode.TOGGLE) {
            if (pressed) {
                if (wanted || holding) {
                    wanted = false;
                } else {
                    wanted = true;
                    wantedBy = "key";
                }
            }
            return;
        }
        // Hold: the key's state is the want, except that a limit release waits for the key to come up.
        if (!down) {
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
        int code = cfg.getKeyCode();
        if (code == KeyUtil.NONE || McCompat.screen(client) != null || client.getWindow() == null) {
            return false;
        }
        return KeyUtil.isBindDown(client.getWindow(), code);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Starting and releasing

    private static void start(Connection connection, String owner) {
        synchronized (LOCK) {
            if (holding || connection == null || !connection.isConnected()) {
                return;
            }
            holdConnection = connection;
            heldTicks = 0;
            heldMoves = 0;
            holdStartMs = System.currentTimeMillis();
            holdOwner = owner;
            holding = true;
        }
        LOGGER.info("[Blink] hold started by {}", owner);
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
     * @param dropStale a world change: the held moves and tick-ends belong to the world he left, so they are dropped (the
     *                  tick-ends too: sent, they would still count against GrimAC's timer, and a hold longer than 22 ticks
     *                  ending in a world change drew TimerLimit, testkit 1025-blink-world 2026-10-25); everything else goes
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
                // holding is off, so this send passes our hook (and notes the move for the box).
                connection.send(h.packet(), h.listener(), false);
                sent++;
            }
            connection.flushChannel();
        } else {
            dropped = out.size();
        }
        releasedHolds++;
        releasedPackets += sent;
        lastHoldTicks = ticks;
        lastHoldPackets = sent;
        longestHoldTicks = Math.max(longestHoldTicks, ticks);
        ticksSinceRelease = 0;
        LOGGER.info("[Blink] released ({}): {} tick(s), {} packet(s) sent, {} dropped", why, ticks, sent, dropped);
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
    public static void onServerPosition(Vec3 from, Vec3 to) {
        boolean wasHolding = holding;
        if (wasHolding) {
            release("server teleport");
        }
        double dist = from == null || to == null ? 0 : from.distanceTo(to);
        if (wasHolding) {
            ServerCorrections.report("Blink", "while holding - released, carrying on", dist);
        } else if (ticksSinceRelease <= RUBBERBAND_WATCH_TICKS && dist > RUBBERBAND_BLOCKS) {
            ServerCorrections.report("Blink", ticksSinceRelease + " tick(s) after a release - carrying on", dist);
        }
        if (to != null) {
            serverPos = to;
        }
    }

    /** A login or respawn packet: a new world. What is held is released except its moves and tick-ends; the blink ends. */
    public static void onWorldChange() {
        serverPos = null;
        synchronized (LOCK) {
            releaseLocked("world change", true);
        }
        // The new world's first position packet is the move into it, not a rubberband after the release.
        ticksSinceRelease = Integer.MAX_VALUE;
        wanted = false;
        holdLatched = false;
    }

    /** The server starts a configuration phase: everything held is a play packet for it, so all of it goes now. */
    public static void onConfigurationStart() {
        synchronized (LOCK) {
            releaseLocked("configuration", false);
        }
        wanted = false;
    }

    /** Netty thread (Fabric's DISCONNECT): the queue has nowhere to go. */
    private static void onDisconnect() {
        synchronized (LOCK) {
            if (holding) {
                LOGGER.info("[Blink] disconnected while holding: {} packet(s) discarded", queue.size());
            }
            holding = false;
            holdConnection = null;
            queue.clear();
        }
        wanted = false;
        holdLatched = false;
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

    /** One line for the HUD. */
    static Component statusLine() {
        BlinkConfig cfg = BlinkConfig.getInstance();
        return Component.literal("§cBlink §f" + heldTicks + "§7/" + cfg.getMaxTicks() + " ticks §8(" + heldPackets() + ")");
    }
}
