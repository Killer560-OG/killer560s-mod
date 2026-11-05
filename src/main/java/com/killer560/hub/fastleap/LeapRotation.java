package com.killer560.hub.fastleap;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.compat.McPackets;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Leap rotation (leaprot-1104, killer560: "sometimes you leap to someone and you will be rotated 180 degrees"; "it is
 * 100% because of teleporting ... make a quick logger for it"). Hypixel's teleports carry an ABSOLUTE look, the server's
 * own copy of his look, and vanilla puts the camera on it; when the server is behind his real look (it dropped his looks
 * for a while after an earlier teleport), a leap turns him back to an old look.
 *
 * <p>The logger (cheat build, logger {@code killer560smod-leaprot}): one line per server teleport (the packet's look and
 * flags, his look before and after, the last look sent and how long ago, a leap-menu click in the last 3 s, distance) and
 * one line per look-carrying packet sent within {@link #WATCH_MS} of a teleport (at most {@link #WATCH_MAX} per teleport),
 * so the log shows what the server heard in that window. Event-driven only; nothing per frame or tick.
 */
public final class LeapRotation {

    private static final Logger LOGGER = ModLog.get("killer560smod-leaprot");
    static final long LEAP_CLICK_MS = 3000L;
    private static final long WATCH_MS = 1500L;
    private static final int WATCH_MAX = 40;

    private static long leapClickMs;
    private static long lastTeleportMs;
    private static int watched;
    private static long lastSentMs;
    private static float lastSentYaw = Float.NaN;
    private static float lastSentPitch = Float.NaN;
    /** handleMovePlayer's before-half, read by its after-half (client thread, never nested). */
    private static float beforeYaw;
    private static float beforePitch;
    private static Vec3 beforePos;
    private static boolean pending;

    private LeapRotation() {}

    static boolean on() {
        return BuildVariant.CHEAT_FEATURES_ENABLED;
    }

    /** A container click: a leap if the open menu is Hypixel's leap menu. */
    public static void onContainerClick(Screen screen) {
        if (on() && LeapManager.isExactLeapMenu(screen)) {
            leapClickMs = System.currentTimeMillis();
        }
    }

    static boolean leapClickedRecently(long now) {
        return now - leapClickMs <= LEAP_CLICK_MS;
    }

    /** Before vanilla applies a server teleport. */
    public static void beforeTeleport(ClientboundPlayerPositionPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        pending = on() && p != null && packet != null && packet.change() != null;
        if (!pending) {
            return;
        }
        beforeYaw = p.getYRot();
        beforePitch = p.getXRot();
        beforePos = p.position();
    }

    /** After vanilla applied it (and sent the accept). */
    public static void afterTeleport(ClientboundPlayerPositionPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!pending || p == null) {
            pending = false;
            return;
        }
        pending = false;
        long now = System.currentTimeMillis();
        PositionMoveRotation c = packet.change();
        float turn = Mth.wrapDegrees(p.getYRot() - beforeYaw);
        LOGGER.info(String.format(Locale.ROOT,
                "[LeapRotation] teleport: %d ms since the last one, packet yaw %.1f pitch %.1f relatives %s; look before "
                        + "%.1f/%.1f, after %.1f/%.1f (yaw change %.1f); last look sent %.1f/%.1f %d ms ago; leap click in "
                        + "last 3 s: %s; moved %.2f blocks",
                lastTeleportMs == 0 ? -1 : now - lastTeleportMs, c.yRot(), c.xRot(), packet.relatives(), beforeYaw,
                beforePitch, p.getYRot(), p.getXRot(), turn, lastSentYaw, lastSentPitch,
                lastSentMs == 0 ? -1 : now - lastSentMs, leapClickedRecently(now), p.position().distanceTo(beforePos)));
        lastTeleportMs = now;
        watched = 0;
    }

    /** Every packet that went on the wire (Connection.send's tail): looks are remembered, and logged near a teleport. */
    public static void onSent(Object packet) {
        float yaw;
        float pitch;
        String what;
        boolean ground = false;
        if (packet instanceof ServerboundMovePlayerPacket move) {
            ground = move.isOnGround();
            if (!move.hasRotation()) {
                yaw = Float.NaN;
                pitch = Float.NaN;
                what = "move (position only)";
            } else {
                yaw = move.getYRot(0f);
                pitch = move.getXRot(0f);
                what = move.hasPosition() ? "move+look" : "look";
            }
        } else if (packet instanceof ServerboundAcceptTeleportationPacket accept) {
            yaw = McPackets.acceptYRot(accept);
            pitch = McPackets.acceptXRot(accept);
            what = "teleport accept";
        } else {
            return;
        }
        if (!on()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!Float.isNaN(yaw)) {
            lastSentYaw = yaw;
            lastSentPitch = pitch;
            lastSentMs = now;
        }
        if (lastTeleportMs != 0 && now - lastTeleportMs <= WATCH_MS && watched < WATCH_MAX) {
            watched++;
            LOGGER.info(String.format(Locale.ROOT, "[LeapRotation] sent %s yaw %.1f pitch %.1f onGround %s, %d ms after "
                    + "the teleport", what, yaw, pitch, ground, now - lastTeleportMs));
        }
    }
}
