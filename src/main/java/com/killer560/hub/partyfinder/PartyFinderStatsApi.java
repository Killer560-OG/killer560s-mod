package com.killer560.hub.partyfinder;

import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Dungeon stats for the Party Finder tooltip, one player at a time, from the Profile Viewer's own data source.
 *
 * <p><b>Source.</b> Each name goes through {@link ProfileViewerApi#resolve} (Mojang name to UUID) and
 * {@link ProfileViewerApi#fetchProfiles} - the keyless SkyBlockPV backend ({@code skyblock-pv.thatgravyboat.tech},
 * which hands back Hypixel's {@code /v2/skyblock/profiles} response unchanged), or the player's own Hypixel key if
 * they set one in the Profile Viewer. No new host, no new HTTP client, no key in the mod. Until 2026-10-07 this class
 * asked {@code api.docilelm.top/v2/dungeons} (the endpoint Devonian uses), which now answers {@code {"result":{}}}
 * for every name - so every stat showed {@code ?}. That service is no longer called at all: a fallback that has
 * returned nothing for anyone would only cost a request per menu.
 *
 * <p><b>Fields</b> (Hypixel's names, under {@code members.<uuid>.dungeons}): Catacombs level from the SELECTED
 * profile's {@code dungeon_types.catacombs.experience} through {@link LevelTables#catacombsLevel} (capped at 50, as
 * Hypixel shows it); secrets is {@code secrets} summed over every profile, and the average is that over every
 * floor's {@code tier_completions} (normal and Master Mode) on the same profiles; the PB is the selected profile's
 * {@code fastest_time_s} / {@code fastest_time_s_plus} for the party's floor, milliseconds, shown {@code m:ss}.
 *
 * <p><b>Requests.</b> {@link #request} only queues names - it is called from the client tick and never touches the
 * network. One daemon thread takes names off the queue and starts at most {@link #MAX_IN_FLIGHT} lookups at once, so a
 * full menu (about 25 players) is two backend requests at a time, not 25. A name is never queued twice, never queued
 * while its lookup runs, and not asked again for {@link #CACHE_MS} after an answer or {@link #FAILURE_RETRY_MS} after a
 * failure. Every callback swallows its own errors: nothing here can throw into the tick or a packet handler.
 */
public final class PartyFinderStatsApi {

    private static final Logger LOGGER = ModLog.get("killer560smod-partyfinder");

    private static final long CACHE_MS = 10 * 60 * 1000L;
    /** The backend itself caches a failed player for 3 minutes; asking sooner only gets the same failure. */
    private static final long FAILURE_RETRY_MS = 3 * 60 * 1000L;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long LOOKUP_TIMEOUT_S = 60;
    private static final int MAX_CACHED = 512;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** {@code pbNormal}/{@code pbMaster}: {@code {"s": {"floor_7": "4:12"}, "s_plus": {...}}}, may be null. */
    public record PlayerStats(double level, int secrets, double averageSecrets, JsonObject pbNormal,
                              JsonObject pbMaster, long fetchedAtMs) {
    }

    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>();
    /** Names queued or being looked up - the dedupe set. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, PlayerStats> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURE_REASON = new ConcurrentHashMap<>();
    private static final Semaphore SLOTS = new Semaphore(MAX_IN_FLIGHT);

    /** Test hooks (testkit 400-menu-partyfinder-stats): lookups started, and the threads that started them. */
    static final AtomicInteger LOOKUPS_STARTED = new AtomicInteger();
    static final Set<String> LOOKUP_THREADS = ConcurrentHashMap.newKeySet();

    private static Thread worker;
    private static volatile boolean loggedFailure = false;

    private PartyFinderStatsApi() {
    }

    public static PlayerStats get(String name) {
        return name == null ? null : CACHE.get(name.toLowerCase(Locale.ROOT));
    }

    /** True when the last lookup for {@code name} failed (no answer, or no SkyBlock data for it). */
    public static boolean hasFailed(String name) {
        return name != null && FAILED.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Why the last lookup for {@code name} failed, naming the source ("SkyBlockPV backend: ..."), or null. */
    public static String failureReason(String name) {
        return name == null ? null : FAILURE_REASON.get(name.toLowerCase(Locale.ROOT));
    }

    /** Queue every name that has no fresh answer. Cheap and network-free: safe on the client tick. */
    public static void request(Collection<String> names) {
        try {
            long now = System.currentTimeMillis();
            boolean added = false;
            for (String raw : names) {
                // Names come from item lore and end up in a URL path: real Minecraft usernames only.
                if (raw == null || !NAME.matcher(raw).matches()) {
                    continue;
                }
                String name = raw.toLowerCase(Locale.ROOT);
                PlayerStats cached = CACHE.get(name);
                if (cached != null && now - cached.fetchedAtMs() < CACHE_MS) {
                    continue;
                }
                Long failedAt = FAILED.get(name);
                if (failedAt != null && now - failedAt < FAILURE_RETRY_MS) {
                    continue;
                }
                if (PENDING.add(name)) {
                    QUEUE.add(name);
                    added = true;
                }
            }
            if (added) {
                ensureStarted();
            }
        } catch (Throwable t) {
            LOGGER.warn("[PartyFinder] could not queue stats lookups: {}", t.toString());
        }
    }

    private static synchronized void ensureStarted() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(PartyFinderStatsApi::run, "killer560smod-partyfinder-stats");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run() {
        while (true) {
            String name;
            try {
                name = QUEUE.take();
                SLOTS.acquire();
            } catch (InterruptedException e) {
                return;
            }
            try {
                lookup(name);
            } catch (Throwable t) {
                fail(name, "lookup error (" + t.getClass().getSimpleName() + ")");
                SLOTS.release();
            }
        }
    }

    /** Starts one player's lookup; the slot is released when it completes, however it completes. */
    private static void lookup(String name) {
        LOOKUPS_STARTED.incrementAndGet();
        LOOKUP_THREADS.add(Thread.currentThread().getName());
        AtomicBoolean resolved = new AtomicBoolean();
        CompletableFuture<PlayerStats> f = ProfileViewerApi.resolve(name).thenCompose(player -> {
            resolved.set(true);
            LOOKUP_THREADS.add(Thread.currentThread().getName());
            return ProfileViewerApi.fetchProfiles(player.uuid(), false);
        }).thenApply(result -> toStats(result.profiles(), System.currentTimeMillis()));
        f.orTimeout(LOOKUP_TIMEOUT_S, TimeUnit.SECONDS).whenComplete((stats, t) -> {
            try {
                if (t != null) {
                    String why = ProfileViewerApi.messageFor(t);
                    fail(name, resolved.get() ? why : "Mojang name lookup: " + why);
                } else if (stats == null) {
                    fail(name, "no SkyBlock profile");
                } else {
                    FAILED.remove(name);
                    FAILURE_REASON.remove(name);
                    if (CACHE.size() >= MAX_CACHED) {
                        long now = System.currentTimeMillis();
                        CACHE.entrySet().removeIf(e -> now - e.getValue().fetchedAtMs() >= CACHE_MS);
                    }
                    CACHE.put(name, stats);
                }
            } catch (Throwable ignored) {
                // Never let a callback throw into the HTTP client's threads.
            } finally {
                PENDING.remove(name);
                SLOTS.release();
            }
        });
    }

    private static void fail(String name, String reason) {
        FAILED.put(name, System.currentTimeMillis());
        FAILURE_REASON.put(name, reason);
        PENDING.remove(name);
        if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.info("[PartyFinder] No stats for a player ({}); shown as ? - logged once a session", reason);
        }
    }

    /** The stats the overlay shows, from a player's parsed profiles (selected profile first), or null for none. */
    static PlayerStats toStats(List<SbProfile> profiles, long now) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        SbProfile selected = profiles.get(0);
        long secrets = 0;
        long runs = 0;
        for (SbProfile p : profiles) {
            secrets += p.dungeons.secrets();
            runs += p.dungeons.totalRuns();
        }
        double level = LevelTables.catacombsLevel(selected.dungeons.catacombsXp(), false).fractional();
        double average = runs > 0 ? (double) secrets / runs : 0.0;
        return new PlayerStats(level, (int) Math.min(Integer.MAX_VALUE, secrets), average,
                personalBests(selected.dungeons.normal()), personalBests(selected.dungeons.master()), now);
    }

    /** {@code {"s": {"floor_N": "m:ss"}, "s_plus": {...}}} from Hypixel's millisecond fastest times. */
    private static JsonObject personalBests(Map<Integer, SbProfile.Floor> floors) {
        JsonObject s = new JsonObject();
        JsonObject sPlus = new JsonObject();
        for (Map.Entry<Integer, SbProfile.Floor> e : floors.entrySet()) {
            if (e.getValue().fastestS() > 0) {
                s.addProperty("floor_" + e.getKey(), time(e.getValue().fastestS()));
            }
            if (e.getValue().fastestSPlus() > 0) {
                sPlus.addProperty("floor_" + e.getKey(), time(e.getValue().fastestSPlus()));
            }
        }
        JsonObject out = new JsonObject();
        out.add("s", s);
        out.add("s_plus", sPlus);
        return out;
    }

    static String time(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
}
