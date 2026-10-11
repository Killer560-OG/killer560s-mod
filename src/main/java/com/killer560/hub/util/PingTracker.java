package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.util.Util;

import java.util.function.BooleanSupplier;

/**
 * Real round-trip ping, measured the way Odin and Skyblocker do it: send vanilla's own
 * {@link ServerboundPingRequestPacket} carrying {@code Util.getMillis()}, and on the matching
 * {@code ClientboundPongResponsePacket} take {@code now - time}. It is the packet pair vanilla's F3 ping chart
 * ({@code PingDebugMonitor}) sends every tick while that chart is open, so one a second is ordinary traffic.
 *
 * <p>Why not the tab list: Hypixel never fills in a player's own {@code PlayerInfo.getLatency()} with a real value,
 * so the Performance HUD read "Ping: 1ms" all the time (killer560, 2026-10-11: "The performance hud still says i have
 * one ping whne I know it is more than that"). The tab value is only used here when no pong has arrived yet.
 *
 * <p>The pong is received on the Netty thread ({@code ClientPacketListener.handlePongResponse} has no thread hop,
 * javap 26.1.2 and 26.2), hence the volatile fields. Pings are only sent while something wants them
 * ({@link #addDemand}), so a player with the HUD's ping line off sends nothing extra.
 */
public final class PingTracker {

    /** One request a second. */
    private static final int INTERVAL_TICKS = 20;
    /** Samples averaged for the shown value. */
    private static final int WINDOW = 5;
    /** A pong older than this is not a round trip worth showing (a stalled connection, a stale request). */
    private static final long MAX_SAMPLE_MS = 30_000L;

    private static final java.util.List<BooleanSupplier> DEMAND = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final long[] samples = new long[WINDOW];
    private static int sampleCount = 0;
    private static int sampleNext = 0;
    private static volatile long lastSampleMs = -1L;
    private static volatile long lastSentTime = Long.MIN_VALUE;
    private static volatile int pongsReceived = 0;
    private static volatile int pingsSent = 0;
    private static int tickCounter = 0;
    private static boolean registered = false;

    private PingTracker() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PingTracker", PingTracker::tick));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    /** Pings are sent while any registered supplier answers true. */
    public static void addDemand(BooleanSupplier wanted) {
        DEMAND.add(wanted);
    }

    private static void tick(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null || client.player == null) {
            return;
        }
        if (++tickCounter < INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        boolean wanted = false;
        for (BooleanSupplier s : DEMAND) {
            if (s.getAsBoolean()) {
                wanted = true;
                break;
            }
        }
        if (!wanted) {
            return;
        }
        long now = Util.getMillis();
        lastSentTime = now;
        pingsSent++;
        connection.send(new ServerboundPingRequestPacket(now));
    }

    /** From {@code PingPongMixin}, on the Netty thread. Vanilla's own F3 pongs are real round trips too. */
    public static void onPong(long sentTime) {
        long rtt = Util.getMillis() - sentTime;
        if (rtt < 0 || rtt > MAX_SAMPLE_MS) {
            return;
        }
        synchronized (samples) {
            samples[sampleNext] = rtt;
            sampleNext = (sampleNext + 1) % WINDOW;
            if (sampleCount < WINDOW) {
                sampleCount++;
            }
        }
        lastSampleMs = rtt;
        pongsReceived++;
    }

    private static void reset() {
        synchronized (samples) {
            sampleCount = 0;
            sampleNext = 0;
        }
        lastSampleMs = -1L;
        tickCounter = INTERVAL_TICKS - 1; // first request on the next tick after joining
    }

    /** Average of the last few measured round trips, or -1 before the first pong. */
    public static int measuredMs() {
        synchronized (samples) {
            if (sampleCount == 0) {
                return -1;
            }
            long sum = 0;
            for (int i = 0; i < sampleCount; i++) {
                sum += samples[i];
            }
            return (int) Math.round(sum / (double) sampleCount);
        }
    }

    /** The measured ping, else the tab-list latency (unreliable on Hypixel), else -1. */
    public static int bestMs() {
        int measured = measuredMs();
        return measured >= 0 ? measured : tabListMs();
    }

    /** The local player's own tab-list latency, or -1 when unknown. */
    public static int tabListMs() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) {
            return -1;
        }
        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    /** Read by the testkit. */
    public static long lastSampleMs() {
        return lastSampleMs;
    }

    public static int pongsReceived() {
        return pongsReceived;
    }

    public static int pingsSent() {
        return pingsSent;
    }
}
