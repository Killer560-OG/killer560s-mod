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
        if (!active || passing) {
            return false;
        }
        synchronized (LOCK) {
            if (!active || passing || c != connection) {
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
                boolean bank = !busy && banked < BlinkConfig.getInstance().getMaxTicks() && stillThisTick();
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
            LOGGER.info("[Blink] charge on (bank up to {} ticks, shoot when you move {})",
                    BlinkConfig.getInstance().getMaxTicks(), BlinkConfig.getInstance().isShootOnMove());
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
        passing = true;
        try {
            for (int i = 0; i < n; i++) {
                if (player.isRemoved() || client.level == null) {
                    break;
                }
                // What ClientLevel.tickNonPassenger does for an entity each tick (javap 26.1.2), then the tick-end
                // Minecraft.tick sends after it: one whole client tick of his own movement, physics and keys.
                player.setOldPosAndRot();
                player.tickCount++;
                player.tick();
                connection.send(ServerboundClientTickEndPacket.INSTANCE);
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
                        + "%.2f blocks from %s to %s; shot #%d", why, ran, (System.nanoTime() - t0) / 1e6, blocks,
                fmt(from), fmt(player.position()), shots));
        if (BlinkConfig.getInstance().isChatMessages()) {
            ModChat.send("Blink", ModChat.text(String.format(java.util.Locale.ROOT, "shot %d tick(s), %.1f blocks", ran, blocks)));
        }
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
            busy = false;
            if (stash != null && connection != null && connection.isConnected()) {
                sendLocked(stash);
            }
            stash = null;
            connection = null;
        }
        if (was) {
            LOGGER.info("[Blink] charge off ({}), {} banked tick(s) left unused", why, left);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

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
