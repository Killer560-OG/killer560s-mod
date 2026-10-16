package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops one broken feature from taking the whole game down.
 *
 * <p>An exception thrown out of a tick handler reaches Minecraft's game loop and crashes the client. That is
 * survivable while the only users are killer560 and friends, who can describe what they were doing. It is not
 * survivable once the mod is public: a stranger gets a crash screen naming this mod, and neither they nor he can
 * tell which of a hundred features threw.
 *
 * <p>So a guarded handler catches, logs loudly with the feature's name, and after {@link #MAX_FAILURES}
 * consecutive-ish failures stops calling that handler at all and says so in chat. The rest of the mod keeps
 * running.
 *
 * <p><b>It deliberately does not fail quietly.</b> Every throw is logged at ERROR with a stack trace, and the
 * first one is announced in chat as well as the disable. A guard that hid failures would be worse than the
 * crash it prevents - a feature that silently stopped working, with nothing in the log to find later. The point
 * is to keep the game alive and to make the failure easy to report, not to pretend it did not happen.
 */
public final class FeatureGuard {

    private static final Logger LOGGER = ModLog.get("killer560smod-guard");

    /** Three, not one: a feature that throws once on a world boundary or a null player is common and recovers.
     *  A feature that has thrown three times is broken and will keep throwing every tick. */
    private static final int MAX_FAILURES = 3;

    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();
    /** Set once anything is ever disabled, so the per-tick check is a field read until then. Never cleared by
     *  {@link #reset}: a stale true only costs the set lookup it replaced. */
    private static volatile boolean anyDisabled;

    private FeatureGuard() {
    }

    /** Wraps a START_CLIENT_TICK handler. {@code name} is what the user will see if it has to be disabled. */
    public static ClientTickEvents.StartTick start(String name, ClientTickEvents.StartTick guarded) {
        // Debug-only: the probe (perfprobe/PerfProbe) times the feature's own handler, not this shared wrapper.
        ClientTickEvents.StartTick handler = com.killer560.hub.perfprobe.PerfProbe.wrapListener(ClientTickEvents.StartTick.class, guarded);
        // The try is written out here rather than wrapping the handler in a Runnable for a shared run(): that
        // allocated a capturing lambda per handler per tick and put a second megamorphic call in front of every
        // one of the ~150 guarded handlers (95-fps-bench JFR, 2026-10-05: about 2% of render-thread samples sat
        // in the wrapper itself).
        return client -> {
            if (anyDisabled && DISABLED.contains(name)) {
                return;
            }
            try {
                handler.onStartTick(client);
            } catch (Throwable t) {
                failed(name, t);
            }
        };
    }

    /** Wraps an END_CLIENT_TICK handler. */
    public static ClientTickEvents.EndTick end(String name, ClientTickEvents.EndTick guarded) {
        ClientTickEvents.EndTick handler = com.killer560.hub.perfprobe.PerfProbe.wrapListener(ClientTickEvents.EndTick.class, guarded);
        return client -> {
            if (anyDisabled && DISABLED.contains(name)) {
                return;
            }
            try {
                handler.onEndTick(client);
            } catch (Throwable t) {
                failed(name, t);
            }
        };
    }

    // ---- gated: skip a switched-off feature with one boolean read (FeatureBits) -----------------------------------

    /**
     * {@link #start} that calls {@code guarded} only while {@link FeatureBits bit} {@code bit} is on - and once more on the
     * first tick it is off (after it was on, and at start-up), so a handler whose switched-off branch clears its state
     * still does that exactly once, on the same tick it did before. Use only when every later switched-off tick of the
     * handler would do nothing (its off branch is a bare return, or a reset of state nothing else writes while off).
     */
    public static ClientTickEvents.StartTick start(String name, int bit, ClientTickEvents.StartTick guarded) {
        ClientTickEvents.StartTick handler = com.killer560.hub.perfprobe.PerfProbe.wrapListener(ClientTickEvents.StartTick.class, guarded);
        boolean[] ranOn = {true};
        return client -> {
            if (!FeatureBits.on(bit)) {
                if (!ranOn[0]) {
                    FeatureBits.skipped(bit);
                    return;
                }
                ranOn[0] = false; // the one switched-off call, then skip
            } else {
                ranOn[0] = true;
            }
            if (anyDisabled && DISABLED.contains(name)) {
                return;
            }
            try {
                handler.onStartTick(client);
            } catch (Throwable t) {
                failed(name, t);
            }
        };
    }

    /** {@link #end} that calls {@code guarded} only while bit {@code bit} is on, plus its first switched-off tick (see
     *  {@link #start(String, int, ClientTickEvents.StartTick)}). */
    public static ClientTickEvents.EndTick end(String name, int bit, ClientTickEvents.EndTick guarded) {
        ClientTickEvents.EndTick handler = com.killer560.hub.perfprobe.PerfProbe.wrapListener(ClientTickEvents.EndTick.class, guarded);
        boolean[] ranOn = {true};
        return client -> {
            if (!FeatureBits.on(bit)) {
                if (!ranOn[0]) {
                    FeatureBits.skipped(bit);
                    return;
                }
                ranOn[0] = false;
            } else {
                ranOn[0] = true;
            }
            if (anyDisabled && DISABLED.contains(name)) {
                return;
            }
            try {
                handler.onEndTick(client);
            } catch (Throwable t) {
                failed(name, t);
            }
        };
    }

    /** A level-render listener called only while bit {@code bit} is on. No try/catch is added (as before). */
    public static net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentFeatures afterFeatures(
            int bit, net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentFeatures listener) {
        var inner = com.killer560.hub.perfprobe.PerfProbe.wrapListener(
                net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentFeatures.class, listener);
        return context -> {
            if (!FeatureBits.on(bit)) {
                FeatureBits.skipped(bit);
                return;
            }
            inner.afterTranslucentFeatures(context);
        };
    }

    /** {@link #afterFeatures} that still calls the listener on its first frame with the bit off, like the gated tick
     *  wrappers, for a listener whose switched-off branch clears what it drew last (once). */
    public static net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentFeatures afterFeaturesFlush(
            int bit, net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentFeatures listener) {
        var inner = com.killer560.hub.perfprobe.PerfProbe.wrapListener(
                net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentFeatures.class, listener);
        boolean[] ranOn = {true};
        return context -> {
            if (!FeatureBits.on(bit)) {
                if (!ranOn[0]) {
                    FeatureBits.skipped(bit);
                    return;
                }
                ranOn[0] = false;
            } else {
                ranOn[0] = true;
            }
            inner.afterTranslucentFeatures(context);
        };
    }

    /** As {@link #afterFeatures}, for AFTER_TRANSLUCENT_TERRAIN. */
    public static net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentTerrain afterTerrain(
            int bit, net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentTerrain listener) {
        var inner = com.killer560.hub.perfprobe.PerfProbe.wrapListener(
                net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AfterTranslucentTerrain.class, listener);
        return context -> {
            if (!FeatureBits.on(bit)) {
                FeatureBits.skipped(bit);
                return;
            }
            inner.afterTranslucentTerrain(context);
        };
    }

    /**
     * Several END_CLIENT_TICK handlers that are registered back to back, as ONE Fabric listener that runs them in the
     * same order, each guarded under its own name exactly as {@link #end} would (fps-idle-1015, 2026-10-15). A tick
     * handler that returns at once still costs about 1-2 us a tick, nearly all of it cache misses on its own lambda,
     * code and data (JFR, every switch off); a shared listener saves the per-listener dispatch and wrapper for each one
     * after the first. Only for handlers that would have been registered consecutively: the order relative to every
     * other listener stays what it was.
     */
    public static ClientTickEvents.EndTick endAll(String[] names, ClientTickEvents.EndTick... guarded) {
        if (names.length != guarded.length) {
            throw new IllegalArgumentException("one name per handler");
        }
        ClientTickEvents.EndTick[] handlers = new ClientTickEvents.EndTick[guarded.length];
        for (int i = 0; i < guarded.length; i++) {
            handlers[i] = com.killer560.hub.perfprobe.PerfProbe.wrapListener(ClientTickEvents.EndTick.class, guarded[i]);
        }
        String[] ns = names.clone();
        return client -> {
            for (int i = 0; i < handlers.length; i++) {
                if (anyDisabled && DISABLED.contains(ns[i])) {
                    continue;
                }
                try {
                    handlers[i].onEndTick(client);
                } catch (Throwable t) {
                    failed(ns[i], t);
                }
            }
        };
    }

    /**
     * Guards a handler that must NEVER be switched off, however often it throws.
     *
     * <p>For the {@link ActionGate}, and anything else where not running is more dangerous than crashing. The gate
     * is what holds this mod to one automated interaction per tick; if it stopped being called, several features
     * could each send an interaction on the same tick, which is exactly the traffic an anticheat is looking for.
     * A crash is a bad afternoon, a silent flood of interactions is an account. So this one keeps being called and
     * keeps catching, and only the log volume is held down.
     */
    public static ClientTickEvents.StartTick criticalStart(String name, ClientTickEvents.StartTick guarded) {
        ClientTickEvents.StartTick handler = com.killer560.hub.perfprobe.PerfProbe.wrapListener(ClientTickEvents.StartTick.class, guarded);
        return client -> {
            try {
                handler.onStartTick(client);
            } catch (Throwable t) {
                int count = countFailure(name);
                // Every throw for the first few, then powers of ten - a handler throwing every tick would
                // otherwise write twenty log lines a second and bury whatever else went wrong.
                if (count <= 3 || Integer.toString(count).matches("10*")) {
                    LOGGER.error("[{}] threw on tick (failure {}) - NOT disabled, it is load-bearing", name, count, t);
                }
                if (count == 3) {
                    notifyUser("§c" + name + " is erroring every tick. Automation is not safe right now - "
                            + "turn the cheat features off and send a bug report.");
                }
            }
        };
    }

    /** Whether a feature has been switched off by the guard - for a status line, and for tests. */
    public static boolean isDisabled(String name) {
        return DISABLED.contains(name);
    }

    /** Every feature the guard has switched off this session. */
    public static Set<String> disabled() {
        return Set.copyOf(DISABLED);
    }

    /** Clears the guard's memory of a feature, so it runs again - used after the user fixes their config. */
    public static void reset(String name) {
        DISABLED.remove(name);
        FAILURES.remove(name);
        LAST_FAILURE.remove(name);
    }

    /**
     * How long a failure counts against a feature.
     *
     * <p>The count never decayed, so three errors were three errors however far apart: a feature that threw
     * once on a bad room early in a session, once an hour later on another, and once more near the end was
     * switched off for the rest of the session as though it were broken. That is the opposite of the guard's
     * job - it exists to stop a feature failing NOW from spamming, not to build a life sentence out of
     * unrelated one-offs.
     *
     * <p>Five minutes: three failures inside that really is broken, three across an evening is noise.
     */
    private static final long FAILURE_WINDOW_MS = 5 * 60 * 1000L;

    /** When each feature last threw, for the window above. */
    private static final java.util.Map<String, Long> LAST_FAILURE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Counts one failure against {@code name}, forgetting a tally that has gone stale. */
    private static int countFailure(String name) {
        long now = System.currentTimeMillis();
        Long last = LAST_FAILURE.put(name, now);
        if (last != null && now - last > FAILURE_WINDOW_MS) {
            // Too long ago to be the same fault: start again from this one rather than adding to a tally
            // left over from earlier in the session.
            FAILURES.remove(name);
        }
        return FAILURES.merge(name, 1, Integer::sum);
    }

    /** Logs, announces and (at {@link #MAX_FAILURES}) disables a guarded handler that threw. */
    private static void failed(String name, Throwable t) {
        int count = countFailure(name);
        LOGGER.error("[{}] threw on tick (failure {} of {})", name, count, MAX_FAILURES, t);
        if (count == 1) {
            notifyUser("§e" + name + " just errored - it will be switched off if it keeps happening. "
                    + "Please send a bug report.");
        }
        if (count >= MAX_FAILURES) {
            DISABLED.add(name);
            anyDisabled = true;
            notifyUser("§c" + name + " has been switched off for this session after " + MAX_FAILURES
                    + " errors, so it cannot crash your game. Everything else is still running.");
            LOGGER.error("[{}] disabled for this session after {} failures", name, MAX_FAILURES);
        }
    }

    /** Best-effort chat notice. Never throws - a guard that throws while reporting a throw is worse than useless. */
    private static void notifyUser(String message) {
        try {
            ModChat.send("Killer560's Mod", ModChat.text(message));
        } catch (Throwable ignored) {
            // no player yet, or chat unavailable: the log line above is still there
        }
    }
}
