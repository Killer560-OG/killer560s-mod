package com.killer560.hub.roomsim;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The aim a sim weapon fires along: the SERVER player's eye and rotation when the shot is the server's answer to a
 * use packet, otherwise the player's own.
 *
 * <p>The Spirit Sceptre's bats and the Terminator's arrows are flown from client-side code (particles, the
 * puzzle's lantern line). When a use packet arrives the server hands that code the aim the packet carried - which
 * for a hand click is his real rotation and for an automated click is the rotation the feature put in the packet,
 * exactly the aim Hypixel's server would use. Set only for the duration of one call, on the client thread.
 */
public final class SimAim {

    /** One captured aim. */
    public record Aim(Vec3 eye, float yaw, float pitch) {
    }

    private static Aim override;

    private SimAim() {
    }

    /** The server player's aim right now. Call on the server thread. */
    static Aim of(ServerPlayer sp) {
        return new Aim(sp.getEyePosition(), sp.getYRot(), sp.getXRot());
    }

    /** Runs {@code body} with {@code aim} in force. Client thread. */
    static void with(Aim aim, Runnable body) {
        Aim before = override;
        override = aim;
        try {
            body.run();
        } finally {
            override = before;
        }
    }

    public static Vec3 eye(Player player) {
        Aim a = override;
        return a != null ? a.eye() : player.getEyePosition();
    }

    public static float yaw(Player player) {
        Aim a = override;
        return a != null ? a.yaw() : player.getYRot();
    }

    public static float pitch(Player player) {
        Aim a = override;
        return a != null ? a.pitch() : player.getXRot();
    }

    /** The look vector of {@link #yaw}/{@link #pitch}, the way {@code Entity.getLookAngle} computes it. */
    public static Vec3 look(Player player) {
        Aim a = override;
        return a != null ? Vec3.directionFromRotation(a.pitch(), a.yaw()) : player.getLookAngle();
    }
}
