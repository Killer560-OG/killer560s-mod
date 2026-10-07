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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Dungeon stats for the Party Finder tooltip, one player at a time, from the Profile Viewer's own data source.
 *
 * <p><b>Source.</b> Each name goes through {@link ProfileViewerApi#resolve} (Mojang name to UUID) and
 * {@link ProfileViewerApi#fetchProfiles} - the keyless SkyBlockPV backend ({@code skyblock-pv.thatgravyboat.tech},
 * which hands back Hypixel's {@code /v2/skyblock/profiles} response unchanged), or the player's own Hypixel key if
 * they set one in the Profile Viewer. No new host, no new HTTP client, no key in the mod. Until 2026-10-07 this class
 * asked {@code api.docilelm.top/v2/dungeons} (the endpoint Devonian uses), which now answers {@code {"result":{}}}
 * for every name - so every stat showed {@code ?}. That service is no longer called at all.
 *
 * <p><b>Fields</b> (Hypixel's names, under {@code members.<uuid>.dungeons}), all from ONE profile,
 * {@link SbProfile#dungeonProfile} (the selected one, or the one with the most Catacombs XP when none is selected or
 * the selected one has no dungeon data): Catacombs level from {@code dungeon_types.catacombs.experience} through
 * {@link LevelTables#catacombsLevel} (capped at 50, as Hypixel shows it); secrets is {@code secrets} summed over every
 * profile, and the average is that over every floor's {@code tier_completions} (normal and Master Mode) on the same
 * profiles; the PB is that profile's {@code fastest_time_s_plus} / {@code fastest_time_s} / {@code fastest_time} (any
 * score) for the party's floor, milliseconds, shown {@code m:ss} - which one the overlay shows is its PB mode.
 *
 * <p><b>Requests.</b> {@link #request} only queues names - it is called from the client tick and never touches the
 * network. One daemon thread takes names off the queue and starts at most {@link #MAX_IN_FLIGHT} lookups at once. A
 * name is never queued twice, never queued while its lookup runs, and not asked again for {@link #CACHE_MS} after an
 * answer or {@link #FAILURE_RETRY_MS} after a failure.
 *
 * <p><b>Rate limit</b> (the cause of "the vast majority still do not have any times", killer560 2026-10-07). The
 * backend lets three or four profile requests through and then answers 429 "Retry-After: 10" (measured by hand on 25
 * real dungeon players the same morning: 4 answered, the next 19 were 429s within two seconds). This class used to
 * record each 429 as a failure and not ask again for three minutes, so a full menu showed stats for about four names.
 * Now a 429 is not an answer: the name goes back on the queue, and the worker starts nothing until the backend's
 * Retry-After has passed ({@link ProfileViewerApi#backendRetryAtMs}). Only after {@link #MAX_THROTTLES} throttles in a
 * row is the name shown as failed, and then retried after the backend's own wait (at least 30 s), not three minutes.
 *
 * <p>Every lookup outcome is one INFO line ({@code [PartyFinder] stats for X: ...} / {@code [PartyFinder] no stats
 * for X - ...}), and a backend pause one more; nothing per tick. Every callback swallows its own errors: nothing here
 * can throw into the tick or a packet handler.
 */
public final class PartyFinderStatsApi {

    private static final Logger LOGGER = ModLog.get("killer560smod-partyfinder");

    private static final long CACHE_MS = 10 * 60 * 1000L;
    /** The backend itself caches a failed player for 3 minutes; asking sooner only gets the same failure. */
    private static final long FAILURE_RETRY_MS = 3 * 60 * 1000L;
    /** After {@link #MAX_THROTTLES} 429s in a row for one name, the shortest wait before it is asked again. */
    private static final long THROTTLE_RETRY_MS = 30 * 1000L;
    private static final int MAX_THROTTLES = 5;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long LOOKUP_TIMEOUT_S = 60;
    private static final int MAX_CACHED = 512;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** {@code pbNormal}/{@code pbMaster}: {@code {"s": {"floor_7": "4:12"}, "s_plus": {...}, "any": {...}}}, may be
     *  null. {@code any} is the floor's fastest clear at any score. */
    public record PlayerStats(double level, int secrets, double averageSecrets, JsonObject pbNormal,
                              JsonObject pbMaster, long fetchedAtMs) {
    }

    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>();
    /** Names queued or being looked up - the dedupe set. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, PlayerStats> CACHE = new ConcurrentHashMap<>();
    /** Name -> the time before which it is not asked again (a failure, or an answer with no dungeon data). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURE_REASON = new ConcurrentHashMap<>();
    /** Name -> 429s in a row for it. */
    private static final Map<String, Integer> THROTTLES = new ConcurrentHashMap<>();
    private static final Semaphore SLOTS = new Semaphore(MAX_IN_FLIGHT);

    /** Test hooks (testkit 400/406-menu-partyfinder-stats): lookups started, the threads that started them, and the
     *  429s met. */
    static final AtomicInteger LOOKUPS_STARTED = new AtomicInteger();
    static final Set<String> LOOKUP_THREADS = ConcurrentHashMap.newKeySet();
    static final AtomicInteger THROTTLED = new AtomicInteger();

    private static Thread worker;
    /** The backend pause last logged, so two lookups throttled together log it once. */
    private static volatile long loggedPauseUntil = 0;

    private PartyFinderStatsApi() {
    }

    public static PlayerStats get(String name) {
        return name == null ? null : CACHE.get(name.toLowerCase(Locale.ROOT));
    }

    /** True when the last lookup for {@code name} failed (no answer, or no SkyBlock dungeon data for it). */
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
                Long retryAt = FAILED.get(name);
                if (retryAt != null && now < retryAt) {
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
                waitForBackend();
                SLOTS.acquire();
                // A lookup still in flight may have been throttled while this one waited for its slot.
                waitForBackend();
            } catch (InterruptedException e) {
                return;
            }
            try {
                lookup(name);
            } catch (Throwable t) {
                fail(name, name, "lookup error (" + t.getClass().getSimpleName() + ")", FAILURE_RETRY_MS);
                SLOTS.release();
            }
        }
    }

    /** Sleeps on the worker thread until the backend's last Retry-After has passed. */
    private static void waitForBackend() throws InterruptedException {
        while (true) {
            long wait = ProfileViewerApi.backendRetryAtMs() - System.currentTimeMillis();
            if (wait <= 0) {
                return;
            }
            Thread.sleep(Math.min(wait, 1000));
        }
    }

    /** Starts one player's lookup; the slot is released when it completes, however it completes. */
    private static void lookup(String name) {
        LOOKUPS_STARTED.incrementAndGet();
        LOOKUP_THREADS.add(Thread.currentThread().getName());
        AtomicReference<String> shown = new AtomicReference<>();
        CompletableFuture<ProfileViewerApi.ProfilesResult> f = ProfileViewerApi.resolve(name).thenCompose(player -> {
            shown.set(player.name() == null || player.name().isEmpty() ? name : player.name());
            LOOKUP_THREADS.add(Thread.currentThread().getName());
            return ProfileViewerApi.fetchProfiles(player.uuid(), false);
        });
        f.orTimeout(LOOKUP_TIMEOUT_S, TimeUnit.SECONDS).whenComplete((result, t) -> {
            try {
                String display = shown.get() != null ? shown.get() : name;
                if (t != null) {
                    Throwable cause = ProfileViewerApi.unwrap(t);
                    if (cause instanceof ProfileViewerApi.RateLimitedException r && shown.get() != null) {
                        throttled(name, display, r);
                    } else {
                        String why = ProfileViewerApi.messageFor(t);
                        fail(name, display, shown.get() != null ? why : "Mojang name lookup: " + why, FAILURE_RETRY_MS);
                    }
                } else {
                    answered(name, display, result);
                }
            } catch (Throwable ignored) {
                // Never let a callback throw into the HTTP client's threads.
                PENDING.remove(name);
            } finally {
                SLOTS.release();
            }
        });
    }

    /** A 429: back on the queue (still pending), unless this name has now been throttled too often in a row. */
    private static void throttled(String name, String display, ProfileViewerApi.RateLimitedException r) {
        THROTTLED.incrementAndGet();
        int n = THROTTLES.merge(name, 1, Integer::sum);
        long until = System.currentTimeMillis() + r.retryAfterMs;
        if (n > MAX_THROTTLES) {
            THROTTLES.remove(name);
            fail(name, display, "SkyBlockPV backend: rate limited (429) " + n + " times in a row",
                    Math.max(THROTTLE_RETRY_MS, r.retryAfterMs));
            return;
        }
        if (until > loggedPauseUntil + 1000) {
            loggedPauseUntil = until;
            LOGGER.info("[PartyFinder] SkyBlockPV backend rate limited (429): next lookup in {} s, {} name(s) waiting",
                    (r.retryAfterMs + 999) / 1000, QUEUE.size() + 1);
        }
        QUEUE.add(name);
    }

    private static void answered(String name, String display, ProfileViewerApi.ProfilesResult result) {
        THROTTLES.remove(name);
        List<SbProfile> profiles = result.profiles();
        SbProfile chosen = SbProfile.dungeonProfile(profiles);
        String source = result.source() == null ? "SkyBlockPV backend" : result.source();
        if (chosen == null) {
            fail(name, display, source + ": no SkyBlock profile", CACHE_MS);
            return;
        }
        if (chosen.dungeons.catacombsXp() <= 0 && chosen.dungeons.totalRuns() <= 0) {
            fail(name, display, source + ": no Catacombs data on any of " + profiles.size() + " profile(s)", CACHE_MS);
            return;
        }
        PlayerStats stats = toStats(profiles, System.currentTimeMillis());
        FAILED.remove(name);
        FAILURE_REASON.remove(name);
        if (CACHE.size() >= MAX_CACHED) {
            long now = System.currentTimeMillis();
            CACHE.entrySet().removeIf(e -> now - e.getValue().fetchedAtMs() >= CACHE_MS);
        }
        CACHE.put(name, stats);
        PENDING.remove(name);
        LOGGER.info("[PartyFinder] stats for {}: {}", display, describe(profiles, chosen, stats));
    }

    private static void fail(String name, String display, String reason, long retryAfterMs) {
        FAILED.put(name, System.currentTimeMillis() + retryAfterMs);
        FAILURE_REASON.put(name, reason);
        PENDING.remove(name);
        LOGGER.info("[PartyFinder] no stats for {} - {}; shown as ?, asked again in {} s", display,
                reason.replace('\n', ';'), retryAfterMs / 1000);
    }

    /** The stats the overlay shows, from a player's parsed profiles, or null for none. */
    static PlayerStats toStats(List<SbProfile> profiles, long now) {
        SbProfile chosen = SbProfile.dungeonProfile(profiles);
        if (chosen == null) {
            return null;
        }
        long secrets = 0;
        long runs = 0;
        for (SbProfile p : profiles) {
            secrets += p.dungeons.secrets();
            runs += p.dungeons.totalRuns();
        }
        double level = LevelTables.catacombsLevel(chosen.dungeons.catacombsXp(), false).fractional();
        double average = runs > 0 ? (double) secrets / runs : 0.0;
        return new PlayerStats(level, (int) Math.min(Integer.MAX_VALUE, secrets), average,
                personalBests(chosen.dungeons.normal()), personalBests(chosen.dungeons.master()), now);
    }

    /** {@code {"s": {"floor_N": "m:ss"}, "s_plus": {...}, "any": {...}}} from Hypixel's millisecond fastest times. */
    private static JsonObject personalBests(Map<Integer, SbProfile.Floor> floors) {
        JsonObject s = new JsonObject();
        JsonObject sPlus = new JsonObject();
        JsonObject any = new JsonObject();
        for (Map.Entry<Integer, SbProfile.Floor> e : floors.entrySet()) {
            SbProfile.Floor f = e.getValue();
            if (f.fastestS() > 0) {
                s.addProperty("floor_" + e.getKey(), time(f.fastestS()));
            }
            if (f.fastestSPlus() > 0) {
                sPlus.addProperty("floor_" + e.getKey(), time(f.fastestSPlus()));
            }
            if (f.fastest() > 0) {
                any.addProperty("floor_" + e.getKey(), time(f.fastest()));
            }
        }
        JsonObject out = new JsonObject();
        out.add("s", s);
        out.add("s_plus", sPlus);
        out.add("any", any);
        return out;
    }

    /** The log line's body: what was found, which profile it came from, and what was missing. */
    private static String describe(List<SbProfile> profiles, SbProfile chosen, PlayerStats stats) {
        int sPlus = 0;
        int sOnly = 0;
        int anyOnly = 0;
        for (Map<Integer, SbProfile.Floor> floors : List.of(chosen.dungeons.normal(), chosen.dungeons.master())) {
            for (SbProfile.Floor f : floors.values()) {
                if (f.fastestSPlus() > 0) {
                    sPlus++;
                } else if (f.fastestS() > 0) {
                    sOnly++;
                } else if (f.fastest() > 0) {
                    anyOnly++;
                }
            }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "Catacombs %.1f, %d secrets, %.1f per run; best times on %d floor(s) (S+ %d, S only %d, any score only %d); profile '%s' (%s of %d)",
                stats.level(), stats.secrets(), stats.averageSecrets(), sPlus + sOnly + anyOnly, sPlus, sOnly, anyOnly,
                chosen.cuteName, chosen.selected ? "selected" : "most Catacombs XP", profiles.size()));
        if (stats.secrets() <= 0) {
            sb.append("; no secrets field");
        }
        if (sPlus + sOnly + anyOnly == 0) {
            sb.append("; no fastest times");
        }
        return sb.toString();
    }

    static String time(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
}
