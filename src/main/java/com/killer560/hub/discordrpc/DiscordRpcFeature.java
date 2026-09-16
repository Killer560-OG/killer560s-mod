package com.killer560.hub.discordrpc;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Discord Rich Presence - "Playing Killer560's Mod" on the user's Discord profile (2026-09-15, killer560's
 * request). Default OFF, and it does nothing at all until the user pastes in the Application ID of a
 * Discord application THEY created: Rich Presence shows that application's name, so no id ships with the mod.
 * <p>
 * Threading: the client thread only ever builds a {@link DiscordRpcPresence.Key} (reading cached statics)
 * and drops a job into a small bounded queue. All socket work - connect, handshake, send, reconnect - runs
 * on one daemon thread, so a hung or missing Discord can never stall rendering. Nothing is ever printed to
 * chat; failures are logged once and then stay quiet.
 * <p>
 * Rate limiting: Discord throttles SET_ACTIVITY (roughly 5 updates / 20 s), so an update is only sent when
 * at most once per {@link #MIN_UPDATE_INTERVAL_MS} - re-sent every window even when unchanged, so Discord's own
 * game detection can't take the slot back.
 */
public final class DiscordRpcFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-discordrpc");

    /** Never send SET_ACTIVITY more often than this, even if the player keeps changing area. */
    private static final long MIN_UPDATE_INTERVAL_MS = 15_000L;
    private static final long[] BACKOFF_MS = {5_000L, 10_000L, 20_000L, 40_000L};
    /** After this many failed connects in a row, stay quiet until the presence text changes again. */
    private static final int MAX_CONNECT_ATTEMPTS = BACKOFF_MS.length;

    private record Job(String applicationId, JsonObject activity, boolean disconnect) {
    }

    private static final BlockingQueue<Job> QUEUE = new ArrayBlockingQueue<>(8);

    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile String status = "Off";

    // Client-thread state only.
    private static int tickCounter;
    private static long startedAtMs;
    private static long lastSentMs;
    private static String activeApplicationId = "";
    private static DiscordRpcPresence.Key lastSentKey;

    private DiscordRpcFeature() {
    }

    public static void register() {
        DiscordRpcConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(DiscordRpcFeature::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop("client stopping"));
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> stop("JVM shutdown"), "killer560smod-discordrpc-exit"));
    }

    /** What the settings tab shows under the toggle. */
    public static String status() {
        return status;
    }

    /** The presence line that would be shown right now - client thread only (settings tab preview). */
    public static String previewText() {
        return DiscordRpcPresence.describe(DiscordRpcPresence.current(DiscordRpcConfig.getInstance()));
    }

    /** Applies a settings change immediately instead of waiting out the 15 s rate-limit window. */
    public static void onSettingsChanged() {
        lastSentKey = null;
        lastSentMs = 0L;
    }

    // ---------------------------------------------------------------------------------- client thread

    private static void tick(Minecraft client) {
        // Once a second is plenty: the presence can only change every 15 s anyway.
        if (++tickCounter % 20 != 0) {
            return;
        }
        DiscordRpcConfig cfg = DiscordRpcConfig.getInstance();
        String appId = cfg.getApplicationId();

        if (!cfg.isEnabled() || appId.isEmpty()) {
            if (running) {
                stop(cfg.isEnabled() ? "no application id" : "turned off in settings");
            } else {
                status = cfg.isEnabled() ? "No Application ID set" : "Off";
            }
            return;
        }

        if (!running) {
            start();
        }
        if (!appId.equals(activeApplicationId)) {
            // A new application id means a whole new Discord app - drop the old connection first.
            activeApplicationId = appId;
            startedAtMs = System.currentTimeMillis();
            lastSentKey = null;
            lastSentMs = 0L;
            enqueue(new Job(appId, null, true));
        }

        DiscordRpcPresence.Key key;
        try {
            key = DiscordRpcPresence.current(cfg);
        } catch (Throwable t) {
            // Never let presence building take the client tick down with it.
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentMs < MIN_UPDATE_INTERVAL_MS) {
            return; // rechecked next second; the newest text wins when the window opens
        }
        // Re-send on a steady heartbeat even when the text hasn't changed. Discord's own game detection sees the
        // Minecraft process and keeps replacing our presence with a plain "Minecraft" entry (killer560, 2026-09-16:
        // "I can see it show up for a second then the minecraft overrides it"); re-asserting every window keeps the
        // mod's presence on top without the user having to unregister Minecraft in Discord's settings. 15s is
        // Discord's own SET_ACTIVITY rate limit, so this is the fastest allowed re-assert.
        lastSentKey = key;
        lastSentMs = now;
        enqueue(new Job(appId, DiscordRpcPresence.toActivity(key, startedAtMs), false));
    }

    private static void enqueue(Job job) {
        if (!QUEUE.offer(job)) {
            QUEUE.poll(); // bounded queue: the newest presence is the only one worth keeping
            QUEUE.offer(job);
        }
    }

    private static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        startedAtMs = System.currentTimeMillis();
        QUEUE.clear();
        status = "Connecting...";
        Thread thread = new Thread(DiscordRpcFeature::run, "killer560smod-discord-rpc");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        LOGGER.info("[DiscordRPC] Started.");
    }

    /**
     * Stops the worker and drops the Discord connection; Discord clears the presence by itself when the
     * socket closes. Safe to call twice, from the client thread, from CLIENT_STOPPING or from the JVM
     * shutdown hook. The worker is a daemon thread, so even a socket read that never returns (Discord
     * killed mid-frame) can't hold the game open.
     */
    public static synchronized void stop(String reason) {
        if (!running) {
            status = "Off";
            return;
        }
        running = false;
        QUEUE.clear();
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
        activeApplicationId = "";
        lastSentKey = null;
        lastSentMs = 0L;
        status = "Off";
        LOGGER.info("[DiscordRPC] Stopped ({}).", reason);
    }

    // ---------------------------------------------------------------------------------- worker thread

    private static void run() {
        DiscordIpcClient client = null;
        int attempts = 0;
        boolean gaveUp = false;
        boolean loggedFailure = false;
        long nextAttemptAt = 0L;

        try {
            while (running) {
                Job job;
                try {
                    job = QUEUE.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (job == null) {
                    continue;
                }
                if (job.disconnect()) {
                    client = closeQuietly(client);
                    attempts = 0;
                    gaveUp = false;
                    loggedFailure = false;
                    nextAttemptAt = 0L;
                    continue;
                }
                // A queued job only ever exists because the presence text changed, which is exactly the
                // "next state change" that earns a retry after we gave up.
                if (gaveUp) {
                    gaveUp = false;
                    attempts = 0;
                    nextAttemptAt = 0L;
                }
                long now = System.currentTimeMillis();
                if (client == null) {
                    if (now < nextAttemptAt) {
                        continue; // still backing off - this update is simply dropped
                    }
                    try {
                        client = DiscordIpcClient.connect(job.applicationId());
                        attempts = 0;
                        loggedFailure = false;
                        status = "Connected";
                        LOGGER.info("[DiscordRPC] Connected to the local Discord client.");
                    } catch (Throwable t) {
                        client = null;
                        attempts++;
                        nextAttemptAt = now + BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1];
                        if (attempts >= MAX_CONNECT_ATTEMPTS) {
                            gaveUp = true;
                        }
                        status = "Discord not found";
                        if (!loggedFailure) {
                            // Once per outage, at WARN, never in chat.
                            LOGGER.warn("[DiscordRPC] Could not reach Discord ({}). Retrying quietly.",
                                    t.toString());
                            loggedFailure = true;
                        }
                        continue;
                    }
                }
                try {
                    client.setActivity(job.activity());
                } catch (Throwable t) {
                    LOGGER.info("[DiscordRPC] Lost the Discord connection ({}); will reconnect.", t.toString());
                    client = closeQuietly(client);
                    status = "Reconnecting...";
                    nextAttemptAt = System.currentTimeMillis() + BACKOFF_MS[0];
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[DiscordRPC] Worker stopped unexpectedly: {}", t.toString());
        } finally {
            closeQuietly(client);
            status = running ? "Reconnecting..." : "Off";
        }
    }

    private static DiscordIpcClient closeQuietly(DiscordIpcClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
