package com.killer560.hub.roomsim;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

/**
 * A Shadow Assassin's leap, as Hypixel's server does it to the client: the assassin (a {@link SimMiniboss} named
 * "Shadow Assassin") is put behind him, and a {@link ClientboundInitializeBorderPacket} goes to him whose border leaves
 * him and the room outside it - the red flash Shadow Assassin Alert reads, and the reason vanilla refuses every block
 * click until it lifts ({@code util/ShadowAssassinLeap}). When the leap lands, the level's own border is sent back.
 * <p>
 * Only the CLIENT's border changes: the server's is untouched, so nothing in the world (collisions, damage) differs.
 * For the testkit's Auto Routes leap case (killer560, 2026-10-20: a leap while a chest was being clicked froze the route).
 * Everything goes through {@link SimState#canAct}, like the rest of the sim.
 */
public final class SimShadowAssassinLeap {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-sim");

    /** Server tick the leap lands on (its border is given back), or -1 with no leap on. Server thread. */
    private static volatile int landsAt = -1;

    private SimShadowAssassinLeap() {
    }

    /** A leap is on: the client's border still refuses clicks. */
    public static boolean leaping() {
        return landsAt >= 0;
    }

    /**
     * Leaps a Shadow Assassin at him for {@code ticks} server ticks: the nearest placed one within 32 blocks, else a new
     * one placed for it, put two blocks behind him; and the leap's border sent. False when the sim cannot act.
     */
    public static boolean leap(Minecraft client, int ticks) {
        if (!SimState.canAct(client) || ticks <= 0) {
            return false;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null
                    : server.getPlayerList().getPlayers().get(0);
            if (player == null) {
                return;
            }
            double yaw = Math.toRadians(player.getYRot());
            // Two blocks behind him: his look along the ground is (-sin yaw, cos yaw).
            Vec3 at = player.position().add(Math.sin(yaw) * 2.0, 0, -Math.cos(yaw) * 2.0);
            ServerPlayer assassin = null;
            for (ServerPlayer p : level.players()) {
                if (SimMiniboss.isPlaced(p) && "Shadow Assassin".equals(p.getGameProfile().name())
                        && p.distanceTo(player) <= 32.0) {
                    assassin = p;
                    break;
                }
            }
            if (assassin == null) {
                SimMiniboss.Placed placed = SimMiniboss.place(level, BlockPos.containing(at),
                        SimMiniboss.Name.SHADOW_ASSASSIN, 1_000_000);
                assassin = placed == null ? null : placed.boss();
            }
            if (assassin != null) {
                assassin.snapTo(at.x, at.y, at.z, player.getYRot(), 0f);
            }
            WorldBorder flash = new WorldBorder();
            flash.setCenter(player.getX() + 10_000.0, player.getZ() + 10_000.0);
            flash.setSize(1.0);
            player.connection.send(new ClientboundInitializeBorderPacket(flash));
            landsAt = server.getTickCount() + ticks;
            LOGGER.info("[Sim] Shadow Assassin leap at {}: {} - client border moved off him for {} tick(s)",
                    player.blockPosition().toShortString(), assassin == null ? "no assassin could be placed"
                            : "assassin behind him at " + BlockPos.containing(at).toShortString(), ticks);
        });
        return true;
    }

    /** Lands a leap that is on, on the next server tick (the testkit's cleanup). */
    public static void landNow() {
        if (landsAt >= 0) {
            landsAt = 0;
        }
    }

    /** Server thread, every tick from {@link SimMobs}: the leap lands - the level's own border goes back to him. */
    static void tickServer(ServerLevel level) {
        int at = landsAt;
        if (at < 0 || level.getServer().getTickCount() < at) {
            return;
        }
        landsAt = -1;
        for (ServerPlayer real : level.getServer().getPlayerList().getPlayers()) {
            real.connection.send(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        }
        LOGGER.info("[Sim] Shadow Assassin leap landed - the client's world border is back");
    }
}
