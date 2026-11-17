package com.killer560.hub.roomsim;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sim Teammates (siminfleap-1116): up to four standing teammates for a single-player sim, so a Spirit Leap or the InfiniLeap
 * has someone to leap to ({@link SimLeap}) and the tab's party lists a team. Sim Settings "Sim Teammates" (0-4, default 0:
 * the sim as before). Each is a Fabric {@link FakePlayer} placed the way {@link SimMiniboss} places one (its profile goes to
 * every real client before the entity), standing where he stood when it appeared; it never moves, takes no damage and is
 * skipped by every "real player" loop ({@link SimMiniboss#isPlaced} answers true for it). A new map removes them and the
 * next tick places them again round him. Server thread only, except {@link #names} (a copy for the client's tab).
 */
public final class SimTeammates {

    private static final Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-sim");
    public static final int MAX = 4;
    /** Where each one stands from him when placed: E, W, S, N, two blocks out. */
    private static final int[][] OFFSETS = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};

    private static final List<Dummy> PLACED = new ArrayList<>(MAX);
    private static volatile List<String> names = List.of();
    private static volatile boolean clearPending;
    private static int realPlayers;

    private SimTeammates() {
    }

    /** The teammates' names in place order, for the tab (any thread). */
    public static List<String> names() { return names; }

    static boolean isDummy(Entity e) { return e instanceof Dummy; }

    /** A new map or a left session: the next server tick removes them (and places them again round him if wanted). */
    static void reset() { clearPending = true; }

    /** END_SERVER_TICK via SimAbilities.serverTick: keep the count the setting asks for, only while the sim is live. */
    static void tick(MinecraftServer server) {
        Minecraft client = Minecraft.getInstance();
        boolean live = SimState.canAct(client) && client.getSingleplayerServer() == server;
        int want = live ? Math.max(0, Math.min(MAX, SimMenuPrefs.cached().teammates)) : 0;
        if (clearPending) {
            clearPending = false;
            removeAll(server);
        }
        PLACED.removeIf(Entity::isRemoved);
        if (PLACED.size() > want) {
            removeAll(server);
        }
        if (PLACED.size() == want) {
            int real = server.getPlayerList().getPlayers().size();
            if (real != realPlayers && !PLACED.isEmpty()) {
                // A co-op guest joined: it needs every teammate's profile before their entities reach it.
                for (Dummy d : PLACED) announce(server, d);
            }
            realPlayers = real;
            return;
        }
        ServerPlayer host = hostOf(server, client);
        if (host == null || !(host.level() instanceof ServerLevel level)) return;
        while (PLACED.size() < want) {
            if (!place(server, level, host, PLACED.size())) break;
        }
        publish();
    }

    private static ServerPlayer hostOf(MinecraftServer server, Minecraft client) {
        if (client.player == null) return null;
        return server.getPlayerList().getPlayer(client.player.getUUID());
    }

    private static boolean place(MinecraftServer server, ServerLevel level, ServerPlayer host, int index) {
        String name = "Teammate" + (index + 1);
        Dummy d;
        try {
            d = new Dummy(level, new GameProfile(UUID.randomUUID(), name));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[SimTeammates] could not build {}", name, e);
            return false;
        }
        double[] at = SimArrival.clear(level, host.getX() + OFFSETS[index][0], host.getY(), host.getZ() + OFFSETS[index][1]);
        d.setNoGravity(true);
        d.snapTo(at[0], at[1], at[2], host.getYRot(), 0f);
        d.setYHeadRot(host.getYRot());
        announce(server, d);
        if (!level.addFreshEntity(d)) {
            forget(server, List.of(d.getUUID()));
            return false;
        }
        PLACED.add(d);
        LOGGER.info("[SimTeammates] placed {} at {}", name, d.blockPosition().toShortString());
        return true;
    }

    private static void announce(MinecraftServer server, Dummy d) {
        ClientboundPlayerInfoUpdatePacket info = new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, d);
        for (ServerPlayer real : server.getPlayerList().getPlayers()) real.connection.send(info);
    }

    private static void forget(MinecraftServer server, List<UUID> ids) {
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(ids);
        for (ServerPlayer real : server.getPlayerList().getPlayers()) real.connection.send(packet);
    }

    private static void removeAll(MinecraftServer server) {
        if (PLACED.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(PLACED.size());
        for (Dummy d : PLACED) {
            ids.add(d.getUUID());
            d.discard();
        }
        PLACED.clear();
        forget(server, ids);
        publish();
    }

    private static void publish() {
        List<String> out = new ArrayList<>(PLACED.size());
        for (Dummy d : PLACED) out.add(d.getGameProfile().name());
        names = List.copyOf(out);
    }

    /** The live teammates, server thread (the leap menu's heads after the real players). */
    static List<ServerPlayer> placed() { return List.copyOf(PLACED); }

    /** Testkit: moves a teammate (by name) to a spot, server thread. False when there is none of that name. */
    public static boolean moveForTest(String name, double x, double y, double z) {
        for (Dummy d : PLACED) {
            if (d.getGameProfile().name().equals(name)) {
                d.snapTo(x, y, z, d.getYRot(), d.getXRot());
                return true;
            }
        }
        return false;
    }

    /** Testkit: turns a teammate (by name), server thread. */
    public static boolean lookForTest(String name, float yaw, float pitch) {
        for (Dummy d : PLACED) {
            if (d.getGameProfile().name().equals(name)) {
                d.snapTo(d.getX(), d.getY(), d.getZ(), yaw, pitch);
                d.setYHeadRot(yaw);
                return true;
            }
        }
        return false;
    }

    /** A standing teammate: FakePlayer's empty tick and blanket invulnerability are kept; it is seen, never a spectator. */
    private static final class Dummy extends FakePlayer {
        Dummy(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isSpectator() { return false; }

        @Override
        public boolean isCreative() { return false; }
    }
}
