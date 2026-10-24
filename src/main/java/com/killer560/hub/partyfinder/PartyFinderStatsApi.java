package com.killer560.hub.partyfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Dungeon stats for the Party Finder tooltip (and Auto Kick), from killer560's relay first and the Profile Viewer's own
 * data source second.
 *
 * <p><b>Sources.</b> (1) The relay ({@link RelayEndpoint}, {@code POST /pf/stats}): a cache shared by every user of the
 * mod; a player anyone looked at recently is {@code ok} at once, an unknown one {@code pending} while the relay fetches
 * it within the upstream's limit for everybody (relay repo {@code src/pfstats.ts}). (2) Direct: {@link
 * ProfileViewerApi#resolve} (Mojang name to UUID) and {@link ProfileViewerApi#fetchProfiles} - the keyless SkyBlockPV
 * backend, or the player's own Hypixel key if they set one in the Profile Viewer. The relay's stats rules are a port of
 * {@link #toStats} ({@code src/pfextract.ts}); testkit case 410 checks the two give identical numbers.
 *
 * <p><b>Why it was slow</b> (killer560 2026-10-15: "still taking 10+ minutes ... waiting 4m and it only had 3 peoples
 * info filled in"). Both upstreams are rate limited far below a menu's worth of names: his own logs (26.1.2 Mod Only
 * Test, 2026-10-12..15) show the SkyBlockPV backend letting 2-6 profile requests through and then answering 429
 * "Retry-After: 30" (43 pauses, every one 30 s), so the direct path managed about 2-4 players per 32 s; and the relay,
 * whose Hypixel key is still refused (403, checked 2026-10-15) and which therefore also runs on SkyBlockPV, shared by
 * every user, delivered 7 players in 187 s while promising 3.3 s each. On top of that this class threw away most of
 * what little got through: 53 of 126 direct answers were transient "HTTP 500"s (the relay fetched the same players
 * fine later) and each was shown as {@code ?} and not asked again for 3 minutes; the relay was polled only while the
 * menu was open (a name the menu had not asked about for 3 s was dropped), so anything it fetched after he closed the
 * menu never arrived until a reopen; the direct path took every name the relay said would take over 20 s although the
 * relay's estimate was 3-8x optimistic, and otherwise none; nothing was kept across restarts; and names were fetched in
 * page order, not hovered party first.
 *
 * <p><b>Now.</b> Every name the menu (or Auto Kick) asks about becomes a {@link Want} that lives until it is answered,
 * the world changes ({@link #onWorldChange}), it has not been on screen for {@link #KEEP_MS}, or {@link #MAX_WANTS}
 * newer ones push it out - so closing the menu does not stop anything, and reopening it shows what arrived meanwhile.
 * Wants have a tier: HOT (the hovered party, Auto Kick's joiner), VISIBLE (on the open page), BACKGROUND (the menu was
 * closed or the page changed). The relay thread ({@code killer560smod-pf-relay}) asks for up to {@link #RELAY_BATCH}
 * names per request, hot and visible first, at most one request per {@link #RELAY_SPACING_MS} (the relay allows one a
 * second per address), polling visible names every 1-2 s and background ones every 3-10 s. The direct thread ({@code
 * killer560smod-partyfinder-stats}) runs up to {@link #MAX_IN_FLIGHT} lookups and never sits idle while a name lacks
 * stats: it takes names the relay has not answered within {@link #RELAY_FIRST_ANSWER_MS}, has given an error for, or
 * expects later than {@link #DIRECT_HELP_MS} (or has kept pending past {@link #PENDING_TRUST_MS}), hottest tier first,
 * names with no stats at all before names with old ones, and within that the name the relay will reach LAST first - so
 * the two paths work the list from opposite ends instead of fetching the same players. A 429 pauses the direct path for
 * the source's Retry-After (plus a second); a transient failure (HTTP 5xx, timeout, network) is retried after 15, 30,
 * 60, 120 s rather than shown as {@code ?} for three minutes; only "no such account" and "no Catacombs data" are final.
 * Answers are kept on disk ({@code killer560smod-partyfinder-stats-cache.json}, {@link #DISK_MAX_AGE_MS}), so a player
 * seen in an earlier session shows at once and is refreshed behind the ones with nothing.
 *
 * <p>Every lookup outcome is one INFO line ({@code [PartyFinder] stats for X: ...} / {@code [PartyFinder] no stats for X
 * - ...}), a backend pause one more, and every burst one summary when it settles; nothing per tick. Every callback
 * swallows its own errors: nothing here can throw into the tick or a packet handler.
 */
public final class PartyFinderStatsApi {

    private static final Logger LOGGER = ModLog.get("killer560smod-partyfinder");

    private static final long CACHE_MS = 10 * 60 * 1000L;
    /** A final failure (no account, no dungeon data) is not asked again for this long. */
    private static final long FAILURE_RETRY_MS = 3 * 60 * 1000L;
    /** Waits before retrying a transient direct failure (HTTP 5xx, timeout, network), by failure count. */
    private static final long[] TRANSIENT_RETRY_MS = {15_000L, 30_000L, 60_000L, 120_000L};
    /** Transient direct failures in a row after which the want is dropped (the menu asks again later). */
    private static final int MAX_TRANSIENT = 5;
    /** After {@link #MAX_THROTTLES} 429s in a row for one name, the shortest wait before it is asked again. */
    private static final long THROTTLE_RETRY_MS = 30 * 1000L;
    private static final int MAX_THROTTLES = 5;
    /** The backend lets 2-6 through a window (his logs); three at once fills a window without piling up 429s. */
    static final int MAX_IN_FLIGHT = 3;
    private static final long LOOKUP_TIMEOUT_S = 60;
    private static final int MAX_CACHED = 3000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A relay answer is re-asked after this: the relay holds the real cache and refreshes it on view. */
    static final long RELAY_CACHE_MS = 2 * 60 * 1000L;
    /** A relay that could not be reached or answered an error is not asked again for this long. */
    static final long RELAY_DOWN_MS = 60 * 1000L;
    /** A name still pending at the relay after this is polled only every 10 s (the direct path has it by then). */
    static final long RELAY_PENDING_MAX_MS = 120 * 1000L;
    /** A pending name the relay expects later than this is also fetched directly. */
    static final long DIRECT_HELP_MS = 3 * 1000L;
    /** A pending name is fetched directly once it has been pending this long, whatever the relay estimates (its
     *  estimate assumes a free upstream; his logs show it 3-8x optimistic). */
    static final long PENDING_TRUST_MS = 8 * 1000L;
    /** A name the relay has not answered at all within this goes direct (relay slow to respond, or busy elsewhere). */
    static final long RELAY_FIRST_ANSWER_MS = 2 * 1000L;
    /** The relay repeats an upstream error for 2 minutes (relay ERROR_TTL_MS); it is not polled for that name meanwhile. */
    static final long RELAY_ERROR_MS = 2 * 60 * 1000L;
    /** A stale answer is re-polled for at most this long, waiting for the relay's refresh to land. */
    private static final long STALE_FOLLOW_MS = 60 * 1000L;
    /** The menu asks every second and the tooltip every frame: a want seen within these is VISIBLE / HOT. */
    static final long VISIBLE_MS = 3 * 1000L;
    static final long HOT_MS = 3 * 1000L;
    /** A want the menu has not asked about for this long is dropped (the background lookups are bounded). */
    static final long KEEP_MS = 10 * 60 * 1000L;
    static final int MAX_WANTS = 150;
    private static final int RELAY_BATCH = 30;
    /** The relay allows a 30-request burst refilling at one a second per address (relay pfstats.ts IP_BURST). */
    static final long RELAY_SPACING_MS = 1000L;
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(6);
    /** Disk cache: entries older than this are not loaded (PBs move), and at most this many are kept. */
    static final long DISK_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int DISK_MAX_ENTRIES = 2000;
    private static final long DISK_SAVE_DELAY_MS = 10_000L;

    /** {@code pbNormal}/{@code pbMaster}: {@code {"s": {"floor_7": "4:12"}, "s_plus": {...}, "any": {...}}}, may be
     *  null. {@code any} is the floor's fastest clear at any score. */
    public record PlayerStats(double level, int secrets, double averageSecrets, JsonObject pbNormal,
                              JsonObject pbMaster, long fetchedAtMs) {
    }

    enum Tier { HOT, VISIBLE, BACKGROUND }

    /** One name being looked up, by the relay, the direct path, or both. */
    static final class Want {
        final String name;
        final long firstAt;
        final long seq;
        volatile long lastSeenAt;
        volatile long lastHotAt;
        // relay
        volatile long nextPollAt;
        /** When the relay last answered about it (0: never). */
        volatile long relayAnsweredAt;
        /** When the relay expects to have it (its etaMs); 0 when it gave none. */
        volatile long relayEtaAt;
        /** Before this the relay is not asked about it (the relay answered "error"). */
        volatile long relayErrorUntil;
        /** When the relay first answered it stale: polling then only follows the refresh, for a bounded time. */
        volatile long staleSince;
        // direct
        volatile boolean directBusy;
        volatile long directRetryAt;
        volatile int directFailures;
        volatile int throttles;

        Want(String name, long now, long seq) {
            this.name = name;
            this.firstAt = now;
            this.seq = seq;
            this.lastSeenAt = now;
            this.nextPollAt = now;
        }

        Tier tier(long now) {
            if (now - lastHotAt < HOT_MS) {
                return Tier.HOT;
            }
            return now - lastSeenAt < VISIBLE_MS ? Tier.VISIBLE : Tier.BACKGROUND;
        }
    }

    private static final Map<String, PlayerStats> CACHE = new ConcurrentHashMap<>();
    /** Name -> the time before which it is not asked again (a failure, or an answer with no dungeon data). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURE_REASON = new ConcurrentHashMap<>();
    /** Name -> expiry of its {@link #CACHE} entry when that is not fetchedAt + {@link #CACHE_MS} (relay answers). */
    private static final Map<String, Long> EXPIRES = new ConcurrentHashMap<>();
    static final Map<String, Want> WANTS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    /** Test hooks (testkit 400/406/410/411): lookups started, the threads that started them, the 429s met, relay
     *  requests, names the relay could not serve, and the last relay failure. */
    static final AtomicInteger LOOKUPS_STARTED = new AtomicInteger();
    static final Set<String> LOOKUP_THREADS = ConcurrentHashMap.newKeySet();
    static final AtomicInteger THROTTLED = new AtomicInteger();
    static final AtomicInteger RELAY_REQUESTS = new AtomicInteger();
    static final AtomicInteger RELAY_FALLBACKS = new AtomicInteger();
    /** Test hooks: answers by path, wants dropped by a world change, entries loaded from disk. */
    static final AtomicInteger ANSWERED_BY_RELAY = new AtomicInteger();
    static final AtomicInteger ANSWERED_DIRECT = new AtomicInteger();
    static final AtomicInteger DROPPED_ON_WORLD_CHANGE = new AtomicInteger();
    static volatile int diskLoaded = -1;
    static volatile String lastRelayError;
    /** Before this the relay is not asked (the testkit's 410 clears it, so an earlier case's outage does not carry over). */
    static volatile long relayDownUntil = 0;

    private static final Semaphore RELAY_WAKE = new Semaphore(0);
    private static Thread relayWorker;
    private static volatile long lastRelayAt = 0;
    private static final HttpClient RELAY_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Object DIRECT_LOCK = new Object();
    private static Thread worker;
    private static int inFlight = 0;
    /** The direct path's own pause after a 429 from any source (the backend's is {@link
     *  ProfileViewerApi#backendRetryAtMs}). */
    private static volatile long directPausedUntil = 0;
    /** The backend pause last logged, so two lookups throttled together log it once. */
    private static volatile long loggedPauseUntil = 0;

    /** For the per-burst summary: when the first want of the burst appeared, and how its names were answered. */
    private static volatile long burstStartedAt = 0;
    private static final AtomicInteger BURST_NAMES = new AtomicInteger();
    private static final AtomicInteger BURST_RELAY = new AtomicInteger();
    private static final AtomicInteger BURST_DIRECT = new AtomicInteger();

    private static final AtomicBoolean DISK_LOADED = new AtomicBoolean();
    private static final AtomicBoolean DISK_SAVE_PENDING = new AtomicBoolean();
    private static final ScheduledExecutorService DISK_IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-pf-cache");
        t.setDaemon(true);
        return t;
    });

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

    /** Ask for every name that has no fresh answer (names on the open page). Cheap and network-free: safe on the
     *  client tick. */
    public static void request(Collection<String> names) {
        request(names, false);
    }

    /** As {@link #request}, for the names that matter most right now: the hovered party, Auto Kick's joiner. These
     *  go to the front of both paths. Safe to call every frame. */
    public static void requestHot(Collection<String> names) {
        request(names, true);
    }

    private static void request(Collection<String> names, boolean hot) {
        try {
            loadDiskOnce();
            long now = System.currentTimeMillis();
            boolean added = false;
            boolean promoted = false;
            for (String raw : names) {
                // Names come from item lore and end up in a URL path: real Minecraft usernames only.
                if (raw == null || !NAME.matcher(raw).matches()) {
                    continue;
                }
                String name = raw.toLowerCase(Locale.ROOT);
                Want want = WANTS.get(name);
                if (want != null) {
                    want.lastSeenAt = now;
                    if (hot) {
                        promoted |= now - want.lastHotAt >= HOT_MS;
                        want.lastHotAt = now;
                    }
                    continue;
                }
                if (isFresh(name, now)) {
                    continue;
                }
                Long retryAt = FAILED.get(name);
                if (retryAt != null && now < retryAt) {
                    continue;
                }
                if (WANTS.size() >= MAX_WANTS) {
                    dropOldestWant();
                }
                Want w = new Want(name, now, SEQ.incrementAndGet());
                if (hot) {
                    w.lastHotAt = now;
                }
                if (WANTS.isEmpty()) {
                    burstStartedAt = now;
                    BURST_NAMES.set(0);
                    BURST_RELAY.set(0);
                    BURST_DIRECT.set(0);
                }
                WANTS.put(name, w);
                BURST_NAMES.incrementAndGet();
                added = true;
            }
            if (added || promoted) {
                ensureStarted();
                RELAY_WAKE.release();
                wakeDirect();
            }
        } catch (Throwable t) {
            LOGGER.warn("[PartyFinder] could not queue stats lookups: {}", t.toString());
        }
    }

    /** The world changed (dungeon start, lobby swap, disconnect): every lookup not yet answered is dropped. Answers
     *  already in flight still land in the cache. */
    public static void onWorldChange() {
        try {
            int n = WANTS.size();
            if (n > 0) {
                WANTS.clear();
                DROPPED_ON_WORLD_CHANGE.addAndGet(n);
                LOGGER.info("[PartyFinder] world changed: {} unanswered lookup(s) dropped", n);
            }
        } catch (Throwable ignored) {
            // never into the tick
        }
    }

    private static void dropOldestWant() {
        Want oldest = null;
        for (Want w : WANTS.values()) {
            if (!w.directBusy && (oldest == null || w.lastSeenAt < oldest.lastSeenAt)) {
                oldest = w;
            }
        }
        if (oldest != null) {
            WANTS.remove(oldest.name, oldest);
        }
    }

    private static boolean isFresh(String name, long now) {
        PlayerStats cached = CACHE.get(name);
        if (cached == null) {
            return false;
        }
        Long expires = EXPIRES.get(name);
        return now < (expires != null ? expires : cached.fetchedAtMs() + CACHE_MS);
    }

    private static boolean relayUsable(long now) {
        return now >= relayDownUntil && RelayEndpoint.isUsable(RelayEndpoint.DEFAULT_BASE_URL);
    }

    private static void putCache(String name, PlayerStats stats) {
        if (CACHE.size() >= MAX_CACHED && !CACHE.containsKey(name)) {
            long now = System.currentTimeMillis();
            CACHE.entrySet().removeIf(e -> now - e.getValue().fetchedAtMs() >= DISK_MAX_AGE_MS);
            while (CACHE.size() >= MAX_CACHED) {
                String oldest = null;
                long at = Long.MAX_VALUE;
                for (Map.Entry<String, PlayerStats> e : CACHE.entrySet()) {
                    if (e.getValue().fetchedAtMs() < at) {
                        at = e.getValue().fetchedAtMs();
                        oldest = e.getKey();
                    }
                }
                if (oldest == null) {
                    break;
                }
                CACHE.remove(oldest);
            }
        }
        CACHE.put(name, stats);
        FAILED.remove(name);
        FAILURE_REASON.remove(name);
        scheduleDiskSave();
    }

    /** A want is finished: removed, and the burst's summary logged when it was the last one. */
    private static void settle(Want w, boolean byRelay) {
        if (w == null || !WANTS.remove(w.name, w)) {
            return;
        }
        (byRelay ? BURST_RELAY : BURST_DIRECT).incrementAndGet();
        if (WANTS.isEmpty() && burstStartedAt != 0) {
            LOGGER.info("[PartyFinder] all {} name(s) settled {} ms after the first request ({} by the relay, {} directly)",
                    BURST_NAMES.get(), System.currentTimeMillis() - burstStartedAt, BURST_RELAY.get(), BURST_DIRECT.get());
            burstStartedAt = 0;
        }
    }

    private static synchronized void ensureStarted() {
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(PartyFinderStatsApi::directLoop, "killer560smod-partyfinder-stats");
            worker.setDaemon(true);
            worker.start();
        }
        if (relayWorker == null || !relayWorker.isAlive()) {
            relayWorker = new Thread(PartyFinderStatsApi::relayLoop, "killer560smod-pf-relay");
            relayWorker.setDaemon(true);
            relayWorker.start();
        }
    }

    // ------------------------------------------------------------------ the direct path

    private static void wakeDirect() {
        synchronized (DIRECT_LOCK) {
            DIRECT_LOCK.notifyAll();
        }
    }

    /** May the direct path take this name now? See the class comment. */
    static boolean directEligible(Want w, long now) {
        if (w.directBusy || now < w.directRetryAt || isFresh(w.name, now)) {
            return false;
        }
        if (!relayUsable(now) || now < w.relayErrorUntil) {
            return true;
        }
        if (w.staleSince != 0) {
            return false; // the relay has numbers for it and is refreshing them
        }
        if (w.relayAnsweredAt == 0) {
            return now - w.firstAt > RELAY_FIRST_ANSWER_MS;
        }
        return w.relayEtaAt - now > DIRECT_HELP_MS || now - w.firstAt > PENDING_TRUST_MS;
    }

    private static Want pickDirect(long now) {
        Want best = null;
        Tier bestTier = null;
        boolean bestHas = false;
        for (Want w : WANTS.values()) {
            if (!directEligible(w, now)) {
                continue;
            }
            Tier t = w.tier(now);
            boolean has = CACHE.containsKey(w.name);
            if (best == null) {
                best = w;
                bestTier = t;
                bestHas = has;
                continue;
            }
            int c = Integer.compare(t.ordinal(), bestTier.ordinal());
            if (c == 0) {
                c = Boolean.compare(has, bestHas);
            }
            if (c == 0) {
                // The relay works its queue front to back: take what it will reach last.
                c = -Long.compare(w.relayEtaAt, best.relayEtaAt);
            }
            if (c == 0) {
                c = Long.compare(w.seq, best.seq);
            }
            if (c < 0) {
                best = w;
                bestTier = t;
                bestHas = has;
            }
        }
        return best;
    }

    private static void directLoop() {
        while (true) {
            Want w;
            try {
                synchronized (DIRECT_LOCK) {
                    while (true) {
                        long now = System.currentTimeMillis();
                        long pause = Math.max(directPausedUntil, ProfileViewerApi.backendRetryAtMs()) - now;
                        if (pause > 0) {
                            DIRECT_LOCK.wait(Math.min(pause, 1000));
                            continue;
                        }
                        if (inFlight >= MAX_IN_FLIGHT) {
                            DIRECT_LOCK.wait(1000);
                            continue;
                        }
                        w = WANTS.isEmpty() ? null : pickDirect(now);
                        if (w == null) {
                            DIRECT_LOCK.wait(WANTS.isEmpty() ? 5000 : 250);
                            continue;
                        }
                        w.directBusy = true;
                        inFlight++;
                        break;
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                lookup(w);
            } catch (Throwable t) {
                transientFailure(w, w.name, "lookup error (" + t.getClass().getSimpleName() + ")");
                directDone(w);
            }
        }
    }

    private static void directDone(Want w) {
        synchronized (DIRECT_LOCK) {
            w.directBusy = false;
            inFlight = Math.max(0, inFlight - 1);
            DIRECT_LOCK.notifyAll();
        }
    }

    /** Starts one player's lookup; its slot is released when it completes, however it completes. */
    private static void lookup(Want w) {
        String name = w.name;
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
                        throttled(w, display, r);
                    } else if (shown.get() == null && cause instanceof ProfileViewerApi.ApiException) {
                        // Mojang answered: no such account. Final.
                        finalFailure(w, display, "Mojang name lookup: " + ProfileViewerApi.messageFor(t), CACHE_MS);
                    } else {
                        String why = ProfileViewerApi.messageFor(t);
                        transientFailure(w, display, shown.get() != null ? why : "Mojang name lookup: " + why);
                    }
                } else {
                    answered(w, display, result);
                }
            } catch (Throwable ignored) {
                // Never let a callback throw into the HTTP client's threads.
            } finally {
                directDone(w);
            }
        });
    }

    /** A 429: the direct path pauses for the source's wait; the name stays wanted. */
    private static void throttled(Want w, String display, ProfileViewerApi.RateLimitedException r) {
        THROTTLED.incrementAndGet();
        long until = System.currentTimeMillis() + r.retryAfterMs + 1000;
        if (until > directPausedUntil) {
            directPausedUntil = until;
        }
        w.throttles++;
        if (w.throttles > MAX_THROTTLES) {
            int n = w.throttles;
            w.throttles = 0;
            long wait = Math.max(THROTTLE_RETRY_MS, r.retryAfterMs);
            w.directRetryAt = System.currentTimeMillis() + wait;
            markFailed(w.name, display, "SkyBlockPV backend: rate limited (429) " + n + " times in a row", wait);
            return;
        }
        if (until > loggedPauseUntil + 1000) {
            loggedPauseUntil = until;
            int waiting = 0;
            for (String n : WANTS.keySet()) {
                if (!CACHE.containsKey(n)) {
                    waiting++;
                }
            }
            LOGGER.info("[PartyFinder] SkyBlockPV backend rate limited (429): next direct lookup in {} s, {} name(s) without stats",
                    (r.retryAfterMs + 999) / 1000, waiting);
        }
    }

    private static void answered(Want w, String display, ProfileViewerApi.ProfilesResult result) {
        String name = w.name;
        w.throttles = 0;
        List<SbProfile> profiles = result.profiles();
        SbProfile chosen = SbProfile.dungeonProfile(profiles);
        String source = result.source() == null ? "SkyBlockPV backend" : result.source();
        if (chosen == null) {
            finalFailure(w, display, source + ": no SkyBlock profile", CACHE_MS);
            return;
        }
        if (chosen.dungeons.catacombsXp() <= 0 && chosen.dungeons.totalRuns() <= 0) {
            finalFailure(w, display, source + ": no Catacombs data on any of " + profiles.size() + " profile(s)", CACHE_MS);
            return;
        }
        PlayerStats stats = toStats(profiles, System.currentTimeMillis());
        EXPIRES.remove(name);
        putCache(name, stats);
        ANSWERED_DIRECT.incrementAndGet();
        settle(w, false);
        LOGGER.info("[PartyFinder] stats for {}: {}", display, describe(profiles, chosen, stats));
    }

    private static void markFailed(String name, String display, String reason, long retryAfterMs) {
        FAILED.put(name, System.currentTimeMillis() + retryAfterMs);
        FAILURE_REASON.put(name, reason);
        LOGGER.info("[PartyFinder] no stats for {} - {}; asked again in {} s", display, reason.replace('\n', ';'),
                retryAfterMs / 1000);
    }

    /** No account, or no dungeon data: shown as such, the want is finished. */
    private static void finalFailure(Want w, String display, String reason, long retryAfterMs) {
        markFailed(w.name, display, reason, retryAfterMs);
        settle(w, false);
    }

    /** HTTP 5xx, a timeout, the network: shown as "?" with the reason meanwhile, retried soon, still wanted. */
    private static void transientFailure(Want w, String display, String reason) {
        int n = ++w.directFailures;
        long wait = TRANSIENT_RETRY_MS[Math.min(n - 1, TRANSIENT_RETRY_MS.length - 1)];
        w.directRetryAt = System.currentTimeMillis() + wait;
        if (!CACHE.containsKey(w.name)) {
            markFailed(w.name, display, reason, wait);
        } else {
            LOGGER.info("[PartyFinder] refresh failed for {} - {}; old stats kept, retried in {} s", display,
                    reason.replace('\n', ';'), wait / 1000);
        }
        if (n >= MAX_TRANSIENT) {
            settle(w, false);
        }
    }

    // ------------------------------------------------------------------ the relay

    private static void relayLoop() {
        while (true) {
            try {
                long now = System.currentTimeMillis();
                prune(now);
                if (WANTS.isEmpty()) {
                    RELAY_WAKE.tryAcquire(5000, TimeUnit.MILLISECONDS);
                    RELAY_WAKE.drainPermits();
                    continue;
                }
                if (!relayUsable(now)) {
                    long sleep = relayDownUntil > now ? Math.min(5000, relayDownUntil - now) : 5000;
                    RELAY_WAKE.tryAcquire(Math.max(1, sleep), TimeUnit.MILLISECONDS);
                    RELAY_WAKE.drainPermits();
                    continue;
                }
                List<Want> due = new ArrayList<>();
                long nextAt = Long.MAX_VALUE;
                for (Want w : WANTS.values()) {
                    long at = Math.max(w.nextPollAt, w.relayErrorUntil);
                    if (at <= now) {
                        due.add(w);
                    } else {
                        nextAt = Math.min(nextAt, at);
                    }
                }
                long spacing = lastRelayAt + RELAY_SPACING_MS - now;
                if (due.isEmpty() || spacing > 0) {
                    long sleep = due.isEmpty() ? (nextAt == Long.MAX_VALUE ? 5000 : nextAt - now) : spacing;
                    RELAY_WAKE.tryAcquire(Math.max(1, Math.min(5000, sleep)), TimeUnit.MILLISECONDS);
                    RELAY_WAKE.drainPermits();
                    continue;
                }
                due.sort(Comparator.<Want>comparingInt(w -> w.tier(now).ordinal()).thenComparingLong(w -> w.seq));
                lastRelayAt = now;
                askRelay(due.subList(0, Math.min(RELAY_BATCH, due.size())));
                wakeDirect();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOGGER.warn("[PartyFinder] relay worker: {}", t.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Drops wants that are answered, unseen for {@link #KEEP_MS}, or done following a stale refresh. */
    private static void prune(long now) {
        for (Want w : WANTS.values()) {
            if (w.directBusy) {
                continue;
            }
            if (now - w.lastSeenAt > KEEP_MS) {
                WANTS.remove(w.name, w);
            } else if (w.staleSince != 0 ? now - w.staleSince > STALE_FOLLOW_MS : isFresh(w.name, now)) {
                settle(w, w.staleSince != 0);
            }
        }
    }

    /** The relay's stats endpoint: service {@code pf-relay} (testkit fakes), on top of the {@code relay} override. */
    static String relayUrl() {
        return ModNet.url("pf-relay", RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL) + "/pf/stats");
    }

    private static String userAgent() {
        String version = FabricLoader.getInstance().getModContainer("killer560smod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        return "killer560s-mod/" + version + " (PartyFinder)";
    }

    /** One {@code POST /pf/stats}, on the relay thread. */
    private static void askRelay(List<Want> batch) {
        long now = System.currentTimeMillis();
        long sentAt = now;
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        boolean first = false;
        for (Want w : batch) {
            arr.add(w.name);
            first |= w.relayAnsweredAt == 0;
        }
        body.add("names", arr);
        RELAY_REQUESTS.incrementAndGet();
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(relayUrl()))
                    .timeout(RELAY_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            res = ModNet.send(RELAY_HTTP, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            relayDown(e.getClass().getSimpleName());
            return;
        }
        if (res.statusCode() == 429) {
            // This address is asking too fast: slow down, but the relay itself is fine.
            for (Want w : batch) {
                w.nextPollAt = now + 5000;
            }
            return;
        }
        JsonObject results = null;
        if (res.statusCode() == 200) {
            try {
                JsonElement root = JsonParser.parseString(res.body());
                if (root.isJsonObject() && root.getAsJsonObject().get("results") instanceof JsonObject o) {
                    results = o;
                }
            } catch (RuntimeException ignored) {
                // handled below
            }
        }
        if (results == null) {
            relayDown("HTTP " + res.statusCode());
            return;
        }
        int ok = 0;
        int stale = 0;
        int pending = 0;
        int missing = 0;
        int error = 0;
        now = System.currentTimeMillis();
        for (Want w : batch) {
            String name = w.name;
            JsonObject r = results.get(name) instanceof JsonObject o ? o : null;
            String status = r != null && r.get("status") != null && r.get("status").isJsonPrimitive()
                    ? r.get("status").getAsString() : "error";
            long eta = r != null && r.get("etaMs") != null && r.get("etaMs").isJsonPrimitive()
                    ? (long) r.get("etaMs").getAsDouble() : 2000;
            boolean background = w.tier(now) == Tier.BACKGROUND;
            w.relayAnsweredAt = now;
            try {
                switch (status) {
                    case "ok" -> {
                        PlayerStats stats = fromRelay(r.getAsJsonObject("stats"), now);
                        boolean isStale = r.get("stale") != null && r.get("stale").isJsonPrimitive()
                                && r.get("stale").getAsBoolean();
                        EXPIRES.put(name, now + RELAY_CACHE_MS);
                        putCache(name, stats);
                        ok++;
                        ANSWERED_BY_RELAY.incrementAndGet();
                        if (isStale) {
                            stale++;
                            if (w.staleSince == 0) {
                                w.staleSince = now;
                            }
                            if (now - w.staleSince > STALE_FOLLOW_MS) {
                                settle(w, true);
                            } else {
                                w.nextPollAt = now + Math.max(1500, Math.min(10_000, eta));
                            }
                        } else {
                            settle(w, true);
                        }
                    }
                    case "pending" -> {
                        pending++;
                        w.relayEtaAt = now + Math.max(0, eta);
                        long poll = now - w.firstAt > RELAY_PENDING_MAX_MS ? 10_000
                                : background ? Math.max(3000, Math.min(10_000, eta)) : Math.max(1000, Math.min(2000, eta));
                        w.nextPollAt = now + poll;
                    }
                    case "missing" -> {
                        missing++;
                        finalFailure(w, name, clean(r.get("reason")), CACHE_MS);
                    }
                    default -> {
                        error++;
                        RELAY_FALLBACKS.incrementAndGet();
                        w.relayErrorUntil = now + RELAY_ERROR_MS;
                    }
                }
            } catch (RuntimeException e) {
                // A malformed answer for one name: that name goes direct, the rest stand.
                error++;
                RELAY_FALLBACKS.incrementAndGet();
                w.relayErrorUntil = now + RELAY_ERROR_MS;
            }
        }
        if (first) {
            LOGGER.info("[PartyFinder] relay: {} name(s) -> {} cached{}, {} pending, {} missing, {} error(s) ({} ms)",
                    batch.size(), ok, stale > 0 ? " (" + stale + " refreshing)" : "", pending, missing, error,
                    System.currentTimeMillis() - sentAt);
        }
    }

    /** The relay could not be used: every wanted name is fetched directly, and the relay is left alone for a minute. */
    private static void relayDown(String why) {
        lastRelayError = why;
        relayDownUntil = System.currentTimeMillis() + RELAY_DOWN_MS;
        int n = WANTS.size();
        RELAY_FALLBACKS.addAndGet(n);
        LOGGER.info("[PartyFinder] stats relay unavailable ({}); {} name(s) fetched directly, relay asked again in {} s",
                why, n, RELAY_DOWN_MS / 1000);
        wakeDirect();
    }

    // ------------------------------------------------------------------ the disk cache

    private static Path diskPath() {
        return ModPaths.config("killer560smod-partyfinder-stats-cache.json");
    }

    /** Loads the last sessions' answers once, so players seen before show at once (refreshed behind new ones). */
    static void loadDiskOnce() {
        if (!DISK_LOADED.compareAndSet(false, true)) {
            return;
        }
        int loaded = 0;
        try {
            Path p = diskPath();
            if (Files.exists(p)) {
                JsonElement root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                JsonObject players = root.isJsonObject() && root.getAsJsonObject().get("players") instanceof JsonObject o ? o : null;
                long now = System.currentTimeMillis();
                if (players != null) {
                    for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                        try {
                            String name = e.getKey().toLowerCase(Locale.ROOT);
                            if (!NAME.matcher(name).matches() || !e.getValue().isJsonObject() || CACHE.containsKey(name)) {
                                continue;
                            }
                            JsonObject s = e.getValue().getAsJsonObject();
                            long at = s.get("at").getAsLong();
                            if (at > now || now - at > DISK_MAX_AGE_MS) {
                                continue;
                            }
                            CACHE.put(name, new PlayerStats(s.get("level").getAsDouble(), s.get("secrets").getAsInt(),
                                    s.get("avg").getAsDouble(),
                                    s.get("pbNormal") instanceof JsonObject n ? n : null,
                                    s.get("pbMaster") instanceof JsonObject m ? m : null, at));
                            loaded++;
                        } catch (RuntimeException ignored) {
                            // one bad entry
                        }
                    }
                }
            }
            if (loaded > 0) {
                LOGGER.info("[PartyFinder] {} player(s) loaded from the stats cache on disk", loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("[PartyFinder] could not read the stats cache: {}", e.toString());
        }
        diskLoaded = loaded;
    }

    private static void scheduleDiskSave() {
        if (DISK_LOADED.get() && DISK_SAVE_PENDING.compareAndSet(false, true)) {
            try {
                DISK_IO.schedule(PartyFinderStatsApi::saveDisk, DISK_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                DISK_SAVE_PENDING.set(false);
            }
        }
    }

    /** Writes the cache (newest {@link #DISK_MAX_ENTRIES}, none older than {@link #DISK_MAX_AGE_MS}). Package-private
     *  for the testkit, which saves without waiting the delay. */
    static void saveDisk() {
        DISK_SAVE_PENDING.set(false);
        try {
            long now = System.currentTimeMillis();
            List<Map.Entry<String, PlayerStats>> rows = new ArrayList<>(CACHE.entrySet());
            rows.removeIf(e -> now - e.getValue().fetchedAtMs() > DISK_MAX_AGE_MS);
            rows.sort((a, b) -> Long.compare(b.getValue().fetchedAtMs(), a.getValue().fetchedAtMs()));
            JsonObject players = new JsonObject();
            for (int i = 0; i < rows.size() && i < DISK_MAX_ENTRIES; i++) {
                PlayerStats s = rows.get(i).getValue();
                JsonObject o = new JsonObject();
                o.addProperty("at", s.fetchedAtMs());
                o.addProperty("level", s.level());
                o.addProperty("secrets", s.secrets());
                o.addProperty("avg", s.averageSecrets());
                if (s.pbNormal() != null) {
                    o.add("pbNormal", s.pbNormal());
                }
                if (s.pbMaster() != null) {
                    o.add("pbMaster", s.pbMaster());
                }
                players.add(rows.get(i).getKey(), o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("players", players);
            Path p = diskPath();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.warn("[PartyFinder] could not write the stats cache: {}", e.toString());
        }
    }

    /** A relay reason, safe for the tooltip footer: printable ASCII, no color codes, bounded. */
    private static String clean(JsonElement reason) {
        String s = reason != null && reason.isJsonPrimitive() ? reason.getAsString() : "relay: no data";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 120; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '&') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The relay's stats object (relay PROTOCOL.md "Party Finder stats") as the overlay's {@link PlayerStats}. The
     *  level is recomputed from {@code cataXp} with this mod's own table, so it cannot drift from the direct path. */
    static PlayerStats fromRelay(JsonObject s, long now) {
        double level = s.has("cataXp")
                ? LevelTables.catacombsLevel(s.get("cataXp").getAsLong(), false).fractional()
                : s.get("cata").getAsDouble();
        long secrets = s.get("secrets").getAsLong();
        double average = s.get("avgSecrets").getAsDouble();
        JsonObject pb = s.has("pb") && s.get("pb").isJsonObject() ? s.getAsJsonObject("pb") : null;
        return new PlayerStats(level, (int) Math.max(0, Math.min(Integer.MAX_VALUE, secrets)), average,
                relayPbs(pb == null ? null : pb.getAsJsonObject("normal")),
                relayPbs(pb == null ? null : pb.getAsJsonObject("master")), now);
    }

    /** {@code {"floor_7": {"splus": ms, "s": ms, "any": ms}}} -> the {@link #personalBests} shape. */
    private static JsonObject relayPbs(JsonObject floors) {
        JsonObject s = new JsonObject();
        JsonObject sPlus = new JsonObject();
        JsonObject any = new JsonObject();
        Map<Integer, JsonObject> sorted = new TreeMap<>();
        if (floors != null) {
            for (Map.Entry<String, JsonElement> e : floors.entrySet()) {
                if (e.getKey().matches("floor_[0-7]") && e.getValue().isJsonObject()) {
                    sorted.put(e.getKey().charAt(6) - '0', e.getValue().getAsJsonObject());
                }
            }
        }
        for (Map.Entry<Integer, JsonObject> e : sorted.entrySet()) {
            JsonObject f = e.getValue();
            String key = "floor_" + e.getKey();
            if (f.has("s") && f.get("s").getAsLong() > 0) {
                s.addProperty(key, time(f.get("s").getAsLong()));
            }
            if (f.has("splus") && f.get("splus").getAsLong() > 0) {
                sPlus.addProperty(key, time(f.get("splus").getAsLong()));
            }
            if (f.has("any") && f.get("any").getAsLong() > 0) {
                any.addProperty(key, time(f.get("any").getAsLong()));
            }
        }
        JsonObject out = new JsonObject();
        out.add("s", s);
        out.add("s_plus", sPlus);
        out.add("any", any);
        return out;
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
