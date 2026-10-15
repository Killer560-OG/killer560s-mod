package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * One non-zero {@link ClientboundPingPacket} per server tick, sent by the sim's server the way Hypixel's sends it.
 * Client features count server ticks by those pings ({@code witherdragons.ServerTickClock}) and measure a server
 * stall by the gap between two of them ({@code util.ServerLag}), so without them the sim's server read as one that
 * never lags and every server-tick clock fell back to client ticks. Nothing on the client special-cases the sim.
 * The client answers each with a pong, which the vanilla server ignores ({@code handlePong} is empty, javap 26.1.2).
 */
public final class SimServerTicks {

    private static int nextId = 1;

    private SimServerTicks() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimServerTicks::serverTick);
    }

    private static void serverTick(MinecraftServer server) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!SimAbilities.simServer(sp)) {
                continue;
            }
            int id = nextId++;
            if (nextId == 0) {
                nextId = 1;
            }
            sp.connection.send(new ClientboundPingPacket(id));
        }
    }
}
