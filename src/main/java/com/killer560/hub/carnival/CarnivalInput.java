package com.killer560.hub.carnival;

import com.killer560.hub.autoroutes.RouteRotation;
import com.killer560.hub.islands.Islands;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.HotbarSelect;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCorrections;
import com.killer560.hub.util.ViewFreeze;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The one way the Carnival's cheat features touch the game (cheat build only): a tap of your own Use or Attack key
 * through {@link ActionGate} ({@code CARNIVAL_WORLD}), a hotbar change through {@link HotbarSelect}
 * ({@code CARNIVAL_HOTBAR}), and turning the view only through Auto Routes' {@link RouteRotation} (eased per frame, yaw
 * a delta on the running value, pitch within -90..+90), optionally behind {@link ViewFreeze} so the screen holds still.
 * Discrete input only: nothing here writes position or velocity. A server correction is reported and the feature
 * carries on.
 */
final class CarnivalInput {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    /** Who is turning the view now (a feature name), or null. */
    private static String aimOwner;
    private static boolean registered;
    private static int packets = -1;
    private static int taps;

    private CarnivalInput() {
    }

    static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try {
                if (aimOwner != null && !com.killer560.hub.autoroutes.RouteExecutor.isRunning()
                        && !com.killer560.hub.ap3.Ap3Executor.isRunning()) {
                    RouteRotation.frame();
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Carnival] aim frame failed", e);
            }
        });
    }

    /** Yaw and pitch from the eye to {@code point}; yaw as a delta on the running yaw (never wrapped). */
    static float[] lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float pitch = Mth.clamp((float) (-Mth.atan2(dy, h) * (180.0 / Math.PI)), -90f, 90f);
        return new float[]{player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()), pitch};
    }

    /** Angular distance (degrees) from the current look to {@code point}. */
    static float error(LocalPlayer player, Vec3 point) {
        float[] r = lookAt(player, point);
        return (float) Math.hypot(Mth.wrapDegrees(r[0] - player.getYRot()), r[1] - player.getXRot());
    }

    /**
     * Turns toward {@code point} for {@code owner}: a fresh humanized approach for a still point ({@code moving} false),
     * a plain ease that follows a moving one. {@code freeCamera} holds the screen still while the body turns.
     */
    static void aim(String owner, LocalPlayer player, Vec3 point, boolean moving, boolean freeCamera) {
        if (com.killer560.hub.autoroutes.RouteExecutor.isRunning() || com.killer560.hub.ap3.Ap3Executor.isRunning()) {
            return;
        }
        if (aimOwner != null && !aimOwner.equals(owner)) {
            return;
        }
        // The Emergency Cancel Key holds everything automatic (MacroStop); ActionGate already holds the taps.
        if (com.killer560.hub.macrostop.MacroStop.emergencyHeld()) {
            release(owner);
            return;
        }
        if (aimOwner == null) {
            RouteRotation.clearUserMoved();
        }
        aimOwner = owner;
        float[] r = lookAt(player, point);
        if (moving) {
            RouteRotation.follow(r[0], r[1]);
        } else if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)
                || Math.abs(Mth.wrapDegrees(r[0] - player.getYRot())) > 3f) {
            RouteRotation.beginApproach(r[0], r[1], false, 0f, 0f);
        }
        if (freeCamera) {
            ViewFreeze.hold(player.getYRot(), player.getXRot());
        }
    }

    /** {@code owner} stops turning the view. */
    static void release(String owner) {
        if (owner.equals(aimOwner)) {
            aimOwner = null;
            RouteRotation.clear();
        }
    }

    /** Whether the player turned the mouse while {@code owner} was aiming. */
    static boolean userTookOver(String owner, LocalPlayer player) {
        return owner.equals(aimOwner) && RouteRotation.userMovedCamera(player);
    }

    /** One tap of {@code key} through the gate; false when the gate held it (try again next tick). */
    static boolean tap(KeyMapping key) {
        if (!ActionGate.tryAct(ActionGate.Actor.CARNIVAL_WORLD)) {
            return false;
        }
        Islands.tap(key);
        taps++;
        return true;
    }

    /** Selects hotbar {@code slot} through the gate, as a number key would. */
    static boolean select(Minecraft mc, LocalPlayer player, int slot) {
        // GrimAC PacketOrderE: no set_carried_item after a use / click / attack in the same server tick.
        if (HotbarSelect.swapWouldFlag() || !ActionGate.tryAct(ActionGate.Actor.CARNIVAL_HOTBAR)) {
            return false;
        }
        return HotbarSelect.select(mc, player, slot);
    }

    /** Reports a server correction once (with {@code feature}'s name) and lets it carry on. */
    static void watchCorrections(String feature) {
        int now = ServerCorrections.packetCount();
        if (packets >= 0 && now != packets) {
            ServerCorrections.report(feature, "- carrying on", ServerCorrections.lastMoveDistance());
        }
        packets = now;
    }

    static void say(String feature, String text) {
        ModChat.send(feature, ModChat.text(text));
    }

    /** Harness: taps sent by every Carnival auto. */
    public static int taps() {
        return taps;
    }
}
