package com.killer560.hub.spotify;

import com.killer560.hub.util.ModNet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "what is playing" into "which lyric line is now", in-process.
 *
 * <p>Now-playing comes from {@link SpotifyDesktopSource} and nothing else. Last.fm, the API key, the username
 * and the website steps behind them are gone (killer560, 2026-09-30: "it should only ever be able to read
 * spotify"). Only the lyrics themselves still come off the network, from lrclib.net, and those need no key.
 *
 * <p>This is also what used to be a separate Node.js companion server (server.js) that had to be started
 * outside Minecraft; everything it did happens here instead.
 */
public final class LyricsEngine {

    private static final String LRCLIB_URL = ModNet.url("lrclib", "https://lrclib.net/api/search");
    private static final String LRCLIB_GET_URL = ModNet.url("lrclib", "https://lrclib.net/api/get");
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d+):(\\d+\\.\\d+)](.*)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String currentLyric = "";
    private boolean currentIsTransition = false;
    private String lastTrackKey = null;
    private String lastTrackTitle = null;
    private List<LyricLine> syncedLyrics = List.of();
    private long transitionMessageUntilMs = 0;

    /**
     * The lyric clock, which stops when Spotify does and follows Spotify's own position when Windows reports it.
     *
     * <p>killer560 (2026-09-30): "is it possible to make it so you no longer need the delay since it reads
     * automatically for when the lyrics come out." Yes, and the offset slider is gone. It only ever existed to
     * compensate for Last.fm taking about five seconds to report a track change; the window title flips within
     * a frame of the track actually changing, so the right offset is zero.
     *
     * <p>A paused Spotify shows "Spotify Premium" in its title, so the pause is visible and the clock stops instead of
     * running on. SEEKING, and starting the game part-way through a track, the window title cannot show; since
     * 2026-10-20 {@link SpotifyMediaSession} reads Spotify's real position from Windows' media controls and
     * {@link PlaybackClock#follow} snaps the clock to it on any jump over a second. Without that reader (not Windows, or
     * the API missing) the clock is the stopwatch from the track change, as before.
     */
    private final PlaybackClock clock = new PlaybackClock();
    /**
     * Wall time of the last track change or resume this engine saw. A media-session reading Spotify took before it is
     * about the time before (a paused position, or the last song's), so it is not believed until a newer one arrives.
     */
    private long lastEventMs = 0;
    /** Whether the clock has been set from Spotify's real position at least once on this track. */
    private boolean positionFromSpotify = false;

    /** The current track's artist and title, kept through a pause so the HUD can still name it. */
    private String lastArtist = "";
    private String lastTitle = "";
    private boolean lastWasAd = false;
    /** Spotify's own length for the current track (media session), seconds, or 0 when unknown. */
    private double spotifyDurationSeconds = 0;
    /** The chosen lyrics' lrclib {@code duration} in seconds, or 0 when it gave none. */
    private double durationSeconds = 0;
    /** Whether lrclib has answered for the current track (with or without lyrics). */
    private boolean lyricsAnswered = false;
    /** Every lrclib version of the current track with synced lyrics, best name match first. */
    private List<Candidate> candidates = List.of();
    /** lrclib had synced lyrics, but none of them is the length Spotify says this recording is. */
    private boolean versionMismatch = false;
    /** The nearest wrong-version length, seconds, for the HUD's note (0 when none). */
    private double mismatchSeconds = 0;
    /** Whether the by-duration lookup has already been tried for this track. */
    private boolean durationLookupTried = false;

    /**
     * How far lrclib's length may be from Spotify's and still be the same recording. A remix, a radio edit or a version
     * with a different feature differs by more; the same recording's two listings agree to about a second.
     */
    static final double SAME_VERSION_SECONDS = 2.0;

    /** One lrclib listing with synced lyrics. */
    record Candidate(String artist, String title, double durationSeconds, List<LyricLine> lines) {
    }

    /** {@code parsed} carries the line's words and, for an enhanced-LRC line, each word's start. */
    public record LyricLine(double timeSeconds, String text, LyricTiming.Parsed parsed) {
    }

    /**
     * What the Spotify HUD draws, published whole by the poll thread and read by the render thread with no lock.
     * The clock is kept as its two parts, so the HUD can run it on every frame for a smooth word highlight
     * instead of stepping on the 250 ms poll.
     *
     * @param playingSinceMs     wall time playback last resumed, or 0 while paused
     * @param durationSeconds    the track's length: Spotify's own when known, else the lyrics' lrclib length, else 0
     * @param lines              the synced lyric lines (immutable), empty while unknown, when lrclib had none, or when
     *                           they are for another version of the song
     * @param versionMismatch    lrclib had synced lyrics, but only for recordings of a different length than Spotify's
     * @param mismatchSeconds    the nearest such length (seconds), for the HUD's note
     * @param positionFromSpotify the clock was set from Spotify's real position (false: a stopwatch from the track change)
     */
    public record Snapshot(NowPlaying.Kind kind, String artist, String title, long playedMs, long playingSinceMs,
                           double durationSeconds, List<LyricLine> lines, boolean lyricsAnswered,
                           boolean versionMismatch, double mismatchSeconds, boolean positionFromSpotify) {

        public static final Snapshot NONE =
                new Snapshot(NowPlaying.Kind.PAUSED, "", "", 0, 0, 0, List.of(), false, false, 0, false);

        /** Seconds played of this track at wall time {@code nowMs}, pauses excluded. */
        public double elapsedSeconds(long nowMs) {
            return (playedMs + (playingSinceMs == 0 ? 0 : Math.max(0, nowMs - playingSinceMs))) / 1000.0;
        }

        public boolean hasTrack() {
            return !title.isEmpty();
        }

        /** Index of the line being sung at {@code seconds}, or -1 before the first. */
        public int lineAt(double seconds) {
            return LyricTiming.lineAt(i -> lines.get(i).timeSeconds(), lines.size(), seconds);
        }

        /** Where line {@code i} ends: the next line's start, else the track's end, else NaN. */
        public double lineEnd(int i) {
            if (i + 1 < lines.size()) {
                return lines.get(i + 1).timeSeconds();
            }
            return durationSeconds > 0 ? durationSeconds : Double.NaN;
        }
    }

    private volatile Snapshot snapshot = Snapshot.NONE;

    /** The latest {@link Snapshot}; any thread, never null. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** Publishes the current state as a {@link Snapshot}. Caller holds the lock. */
    private void publish(NowPlaying.Kind kind) {
        if (lastTrackKey == null) {
            snapshot = Snapshot.NONE;
            return;
        }
        NowPlaying.Kind shown = kind == NowPlaying.Kind.PAUSED ? kind
                : (lastWasAd ? NowPlaying.Kind.AD : NowPlaying.Kind.TRACK);
        double length = spotifyDurationSeconds > 0 ? spotifyDurationSeconds : durationSeconds;
        snapshot = new Snapshot(shown, lastArtist, lastWasAd ? "" : lastTitle, clock.playedMs(), clock.sinceMs(),
                length, syncedLyrics, lyricsAnswered, versionMismatch, mismatchSeconds, positionFromSpotify);
    }

    /**
     * Picks which lrclib version's lines are used (caller holds the lock).
     *
     * <p>killer560 (2026-10-20): the HUD said 3:59 for a song Spotify plays as 4:06 - "Rosa Walton, Hallie Coggins" on
     * Spotify, but the lyrics matched were another recording's, whose lines fall at other times. Neither the window
     * title nor Windows' media session gives the full artist list (both say only "Rosa Walton", checked 2026-10-20), so
     * the LENGTH is what tells two recordings apart: a version is used only when its title matches, its artist list
     * contains Spotify's artist, and its length is within {@link #SAME_VERSION_SECONDS} of Spotify's. With no such
     * version the lines are dropped and the HUD says why. Without Spotify's length (no media session) the best name
     * match is used as before, since there is nothing to check it against.
     */
    private void choose() {
        List<LyricLine> lines = List.of();
        double length = 0;
        boolean mismatch = false;
        double nearest = 0;
        if (!candidates.isEmpty()) {
            if (spotifyDurationSeconds <= 0) {
                lines = candidates.get(0).lines();
                length = candidates.get(0).durationSeconds();
            } else {
                Candidate pick = pickVersion(candidates, lastArtist, lastTitle, spotifyDurationSeconds);
                if (pick != null) {
                    lines = pick.lines();
                    length = pick.durationSeconds();
                } else {
                    mismatch = true;
                    double best = Double.MAX_VALUE;
                    for (Candidate c : candidates) {
                        double d = Math.abs(c.durationSeconds() - spotifyDurationSeconds);
                        if (c.durationSeconds() > 0 && d < best) {
                            best = d;
                            nearest = c.durationSeconds();
                        }
                    }
                }
            }
        }
        syncedLyrics = lines;
        if (length > 0 || !candidates.isEmpty()) {
            durationSeconds = length;
        }
        versionMismatch = mismatch;
        mismatchSeconds = nearest;
    }

    /** The first candidate (best name match first) that is the same recording as Spotify's, or null. */
    static Candidate pickVersion(List<Candidate> candidates, String artist, String title, double spotifySeconds) {
        for (Candidate c : candidates) {
            if (c.durationSeconds() <= 0 || Math.abs(c.durationSeconds() - spotifySeconds) > SAME_VERSION_SECONDS) {
                continue;
            }
            if (!sameTitle(c.title(), title) || !artistListHas(c.artist(), artist)) {
                continue;
            }
            return c;
        }
        return null;
    }

    private static boolean sameTitle(String listed, String want) {
        return want == null || want.isBlank() || (listed != null && listed.trim().equalsIgnoreCase(want.trim()));
    }

    /**
     * Whether lrclib's artist credit (which may list several: "A, B", "A & B", "A feat. B", "A x B") names every artist
     * Spotify gave. Spotify's local sources give only the first, so this cannot reject a listing for an extra artist.
     */
    static boolean artistListHas(String listed, String want) {
        if (want == null || want.isBlank()) {
            return true;
        }
        if (listed == null) {
            return false;
        }
        java.util.Set<String> have = splitArtists(listed);
        for (String a : splitArtists(want)) {
            if (!have.contains(a)) {
                return false;
            }
        }
        return true;
    }

    private static java.util.Set<String> splitArtists(String credit) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String part : credit.toLowerCase(java.util.Locale.ROOT)
                .split("\\s*(?:,|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\sx\\s|\\band\\b)\\s*")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** What the last poll saw, for Song Tag ({@link SongShare}); null before the first poll or once polling stops. */
    private volatile NowPlaying lastNowPlaying = null;
    /**
     * False while only Song Tag polls: the tag shows the song name, so lrclib is not asked for lyrics. (Named apart
     * from the {@code fetchLyrics} method that does the lrclib request.)
     */
    private volatile boolean wantLyrics = true;
    /** The track lyrics were last requested for, so turning lyrics on mid-song still fetches them. */
    private String lyricsRequestedFor = null;

    /**
     * Call roughly every 2 seconds. Never throws - a failed poll just keeps showing the last known lyric.
     *
     * <p>{@code fullLyricsMode} controls only the WORDING of the track-change announcement: "And that was
     * ___" reads fine when the listener has actually been seeing lyric lines, but makes no sense in
     * Song-Title-Only mode where they never saw any - that mode gets "I just finished listening to ___, now
     * I'm listening to ___" instead. Which one fired is exposed via {@link #isCurrentLyricTransition()} so the
     * caller can detect a track-change message without depending on its exact wording.
     */
    public synchronized void poll(SpotifyDesktopSource source, SpotifyMediaSession session, boolean fullLyricsMode) {
        if (source == null) {
            return;
        }
        try {
            NowPlaying nowPlaying = source.fetch();
            lastNowPlaying = nowPlaying;
            long now = System.currentTimeMillis();
            SpotifyMediaSession.Timeline timeline = session == null ? null : session.latest(now);

            if (nowPlaying.kind() == NowPlaying.Kind.PAUSED) {
                // The clock stops; the track is REMEMBERED. Forgetting it would announce the same song again
                // as a brand new track every time he paused and un-paused.
                clock.pause(now);
                // A scrub while paused: Spotify's paused position is believed, so the line shown is where it resumes.
                if (timeline != null && !lastWasAd && timeline.paused() && timeline.matches(lastArtist, lastTitle)
                        && timeline.updatedMs() >= lastEventMs - EVENT_SLACK_MS) {
                    follow(timeline, now);
                }
                currentLyric = "";
                currentIsTransition = false;
                publish(NowPlaying.Kind.PAUSED);
                return;
            }

            String trackKey = nowPlaying.key();
            boolean isAd = nowPlaying.kind() == NowPlaying.Kind.AD;
            String title = isAd ? AD_TITLE : nowPlaying.title();

            if (!trackKey.equals(lastTrackKey)) {
                boolean firstTrack = lastTrackKey == null;
                if (lastTrackKey != null) {
                    currentLyric = fullLyricsMode
                            ? "And that was " + lastTrackTitle + ", now playing " + title
                            : "I just finished listening to " + lastTrackTitle
                              + ", now I'm listening to " + title;
                    currentIsTransition = true;
                    transitionMessageUntilMs = now + 5000;
                } else if (isAd) {
                    // First thing heard this session is an advert: say so rather than sitting silent.
                    currentLyric = "Now I'm listening to " + AD_TITLE;
                    currentIsTransition = true;
                    transitionMessageUntilMs = now + 5000;
                }
                lastTrackKey = trackKey;
                lastTrackTitle = title;
                lastArtist = isAd ? "" : nowPlaying.artist();
                lastTitle = isAd ? "" : nowPlaying.title();
                lastWasAd = isAd;
                durationSeconds = 0;
                spotifyDurationSeconds = 0;
                lyricsAnswered = isAd;
                candidates = List.of();
                versionMismatch = false;
                mismatchSeconds = 0;
                durationLookupTried = false;
                positionFromSpotify = false;
                // The first track of the session may have been playing for minutes: any reading about it is
                // believed. After a real change, only a reading Spotify took since the change is.
                lastEventMs = firstTrack ? 0 : now;
                clock.start(now);
                syncedLyrics = List.of();
                lyricsRequestedFor = null;
                SpotifyLyricsFeature.LOGGER.info("Now playing: artist='{}' title='{}'",
                        nowPlaying.artist(), title);
            } else if (!clock.running()) {
                clock.resume(now);   // resumed after a pause, same track: on from the same position
                lastEventMs = now;
            }
            // Fetched OFF this thread, because the clock is already running.
            //
            // lrclib is an HTTP round trip with a ten-second timeout, and it used to happen inline here -
            // inside a synchronized poll - so every track change stalled the poll loop for however long
            // the request took while the song played on. The clock is correct either way, but no line
            // could be emitted until it returned, so the first lyrics of every song arrived late by the
            // request time. Now the poll returns immediately and the lines appear the moment they land.
            // Asked once per track, and only while lyrics are wanted (chat or the HUD, not while only Song Tag
            // polls); turning either on part-way through a song still fetches that song's lyrics on the next poll.
            if (!isAd && wantLyrics && !trackKey.equals(lyricsRequestedFor)) {
                lyricsRequestedFor = trackKey;
                requestLyrics(trackKey, nowPlaying.artist(), nowPlaying.title());
            }

            // Spotify's own clock, when Windows reports it for this track: its length, a pause the title has not
            // shown yet, and its position, which corrects the stopwatch after a scrub or a late start.
            boolean pausedBySession = false;
            if (!isAd && timeline != null && timeline.matches(nowPlaying.artist(), nowPlaying.title())) {
                double length = timeline.endMs() > 0 ? timeline.endMs() / 1000.0 : 0;
                if (length > 0 && Math.abs(length - spotifyDurationSeconds) > 0.01) {
                    spotifyDurationSeconds = length;
                    choose();
                }
                if (timeline.paused()) {
                    clock.pause(now);
                    pausedBySession = true;
                } else if (timeline.playing() && timeline.updatedMs() >= lastEventMs - EVENT_SLACK_MS) {
                    follow(timeline, now);
                }
                maybeLookUpByLength(trackKey, nowPlaying.artist(), nowPlaying.title());
            }
            if (pausedBySession) {
                currentLyric = "";
                currentIsTransition = false;
                publish(NowPlaying.Kind.PAUSED);
                return;
            }

            if (transitionMessageUntilMs != 0 && now >= transitionMessageUntilMs) {
                transitionMessageUntilMs = 0;
                currentLyric = "";
                currentIsTransition = false;
            }

            // Skipped while a transition message is still actively showing (transitionMessageUntilMs != 0)
            // - otherwise this would overwrite it with the new track's first lyric line on the very same
            // poll call it was just set, since the clock and syncedLyrics are already populated above by
            // then. That silently ate the transition message on every track change into a song that had
            // synced lyrics - especially bad for Song-Title-Only mode, where the transition message is the
            // ONLY thing that mode ever sends.
            if (transitionMessageUntilMs == 0 && !syncedLyrics.isEmpty()) {
                // The clock already follows Spotify's real position when the media session gives one, so a
                // scrub back re-sends the line now being sung and a scrub into a gap sends nothing.
                double elapsed = clock.elapsedMs(now) / 1000.0 + SEND_LEAD_SECONDS;
                currentLyric = currentLyricAt(elapsed);
                currentIsTransition = false;
            }
            publish(nowPlaying.kind());
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Poll failed: {}", String.valueOf(e), e);
        }
    }

    /** What the title portion says while an advert plays. */
    private static final String AD_TITLE = "an ad";

    /**
     * How far ahead of a line's own timestamp it is sent.
     *
     * <p>A lyric timestamp is when the line STARTS being sung, and a message sent at that instant still has to
     * reach Hypixel and come back before it is on screen. Sending it a quarter-second early spends that on the
     * round trip instead of on the reader noticing it is behind. Deliberately small: overshooting reads as the
     * lyric arriving before the vocal, which is more obviously wrong than arriving a moment after it.
     */
    private static final double SEND_LEAD_SECONDS = 0.25;

    /** Lyrics are fetched off the poll thread; one thread is plenty for one request per track. */
    private static final java.util.concurrent.ExecutorService LYRIC_FETCH =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "killer560smod-spotify-lyrics-fetch");
                t.setDaemon(true);
                return t;
            });

    /**
     * Looks the track's lyrics up in the background and installs them if it is still the current track.
     *
     * <p>The track check on the way back matters: skipping through three songs fires three requests, and
     * without it the slowest reply could land last and leave the wrong song's lyrics installed.
     */
    private void requestLyrics(String forTrackKey, String artist, String title) {
        LYRIC_FETCH.submit(() -> {
            Fetched fetched = fetchLyrics(artist, title);
            synchronized (this) {
                if (forTrackKey.equals(lastTrackKey)) {
                    candidates = fetched.candidates();
                    durationSeconds = fetched.durationSeconds();
                    lyricsAnswered = true;
                    choose();
                    publish(snapshot.kind());
                    SpotifyLyricsFeature.LOGGER.info("Lyrics for '{}': {} version(s) with synced lines, using {} line(s){}",
                            title, candidates.size(), syncedLyrics.size(), versionMismatch ? " (none is Spotify's length)" : "");
                }
            }
        });
    }

    /**
     * When the search found no version of Spotify's length, asks lrclib once for that exact length ({@code /api/get}
     * matches the duration itself), since the search returns only its first twenty listings. Caller holds the lock.
     */
    private void maybeLookUpByLength(String forTrackKey, String artist, String title) {
        if (durationLookupTried || !lyricsAnswered || spotifyDurationSeconds <= 0
                || pickVersion(candidates, artist, title, spotifyDurationSeconds) != null) {
            return;
        }
        durationLookupTried = true;
        long seconds = Math.round(spotifyDurationSeconds);
        LYRIC_FETCH.submit(() -> {
            Candidate found = fetchByLength(artist, title, seconds);
            synchronized (this) {
                if (found != null && forTrackKey.equals(lastTrackKey)) {
                    List<Candidate> more = new ArrayList<>(candidates);
                    more.add(found);
                    candidates = List.copyOf(more);
                    choose();
                    publish(snapshot.kind());
                    SpotifyLyricsFeature.LOGGER.info("Lyrics for '{}' at {} s: {}", title, seconds,
                            versionMismatch ? "still not Spotify's version" : "found Spotify's version");
                }
            }
        });
    }

    /** Snaps the clock to Spotify's position on a jump. Caller holds the lock. */
    private void follow(SpotifyMediaSession.Timeline timeline, long now) {
        long before = clock.elapsedMs(now);
        long real = timeline.positionAt(now);
        if (clock.follow(real, now) && positionFromSpotify) {
            SpotifyLyricsFeature.LOGGER.info("Spotify position jumped {} -> {}", mmss(before),
                    mmss(real));
        }
        positionFromSpotify = true;
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /**
     * How much older than the last track change or resume a media-session reading may be and still be believed: the
     * title is read every 250 ms, so the change is seen a little after Spotify made it.
     */
    private static final long EVENT_SLACK_MS = 1000;

    /** What lrclib gave for one track: every version with synced lines, and the first listing's length (0 = unknown). */
    private record Fetched(List<Candidate> candidates, double durationSeconds) {
        static final Fetched NOTHING = new Fetched(List.of(), 0);
    }

    public NowPlaying lastNowPlaying() {
        return lastNowPlaying;
    }

    /** Polling stopped: what was playing is no longer known. */
    public void forgetNowPlaying() {
        lastNowPlaying = null;
    }

    public void setFetchLyrics(boolean fetch) {
        wantLyrics = fetch;
    }

    public synchronized String getCurrentLyric() {
        return currentLyric;
    }

    /** @return whether the current {@link #getCurrentLyric()} value is a track-change announcement
     *  rather than an actual lyric line - lets the caller detect that without depending on its exact
     *  wording, which now varies by {@code fullLyricsMode} (see {@link #poll}). */
    public synchronized boolean isCurrentLyricTransition() {
        return currentIsTransition;
    }

    private String currentLyricAt(double elapsedSeconds) {
        String current = "";
        for (LyricLine line : syncedLyrics) {
            if (line.timeSeconds() <= elapsedSeconds) current = line.text();
            else break;
        }
        return current;
    }

    private Fetched fetchLyrics(String artist, String title) {
        try {
            String url = LRCLIB_URL + "?artist_name=" + urlEncode(artist) + "&track_name=" + urlEncode(title);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = ModNet.send(http, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                SpotifyLyricsFeature.LOGGER.warn("lrclib search failed: HTTP {} for artist='{}' title='{}'",
                        response.statusCode(), artist, title);
                return Fetched.NOTHING;
            }

            JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
            if (results.isEmpty()) {
                SpotifyLyricsFeature.LOGGER.info("lrclib had no results at all for artist='{}' title='{}'", artist, title);
                return Fetched.NOTHING;
            }

            // An EXACT artist and title match wins over merely the first result with synced lyrics.
            //
            // lrclib's search is fuzzy, so a query returns remixes, live cuts, sped-up edits and other
            // artists' covers alongside the real thing - measured on "Lil Uzi Vert - Money Longer"
            // (2026-09-30) it returned 20 results, all with synced lyrics. Taking the first one that happened
            // to have lyrics meant the choice between them was whatever order the server replied in, and a
            // remix's timings do not fit the album version at all.
            //
            // Every listing with synced lyrics is kept, exact matches first, so that once Spotify's own length is
            // known (media session) the one of the right length can be chosen - see choose().
            List<Candidate> exact = new ArrayList<>();
            List<Candidate> rest = new ArrayList<>();
            for (JsonElement el : results) {
                JsonObject obj = el.getAsJsonObject();
                Candidate c = candidate(obj);
                if (c == null) {
                    continue;
                }
                (same(obj, "trackName", title) && same(obj, "artistName", artist) ? exact : rest).add(c);
            }
            exact.addAll(rest);
            double duration = exact.isEmpty() ? duration(results.get(0).getAsJsonObject()) : exact.get(0).durationSeconds();
            if (exact.isEmpty()) {
                SpotifyLyricsFeature.LOGGER.info(
                        "lrclib had {} result(s) for artist='{}' title='{}' but none had synced lyrics",
                        results.size(), artist, title);
            }
            return new Fetched(List.copyOf(exact), duration);
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("lrclib search threw for artist='{}' title='{}': {}", artist, title, e.toString());
            return Fetched.NOTHING;
        }
    }

    /** One listing as a {@link Candidate}, or null when it has no synced lyrics. */
    private Candidate candidate(JsonObject obj) {
        if (!obj.has("syncedLyrics") || obj.get("syncedLyrics").isJsonNull()) {
            return null;
        }
        List<LyricLine> lines = List.copyOf(parseLrc(obj.get("syncedLyrics").getAsString()));
        if (lines.isEmpty()) {
            return null;
        }
        return new Candidate(text(obj, "artistName"), text(obj, "trackName"), duration(obj), lines);
    }

    private static String text(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** lrclib's {@code /api/get}, which matches a recording by artist, title and length (within two seconds). */
    private Candidate fetchByLength(String artist, String title, long seconds) {
        try {
            String url = LRCLIB_GET_URL + "?artist_name=" + urlEncode(artist) + "&track_name=" + urlEncode(title)
                    + "&duration=" + seconds;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = ModNet.send(http, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;   // 404: lrclib has no recording of that length
            }
            JsonElement body = JsonParser.parseString(response.body());
            return body.isJsonObject() ? candidate(body.getAsJsonObject()) : null;
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("lrclib get threw for artist='{}' title='{}': {}", artist, title, e.toString());
            return null;
        }
    }

    /** lrclib's {@code duration} (seconds) for a result, or 0 when missing or not a sane number. */
    private static double duration(JsonObject obj) {
        try {
            if (obj.has("duration") && !obj.get("duration").isJsonNull()) {
                double d = obj.get("duration").getAsDouble();
                return d > 0 && d < 24 * 3600 ? d : 0;
            }
        } catch (RuntimeException ignored) {
            // not a number: unknown length
        }
        return 0;
    }

    private static boolean same(JsonObject obj, String key, String want) {
        if (want == null || want.isBlank()) {
            return true;   // nothing to compare against - the window title gave no artist
        }
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return false;
        }
        return obj.get(key).getAsString().trim().equalsIgnoreCase(want.trim());
    }

    private List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> result = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            Matcher m = LRC_LINE.matcher(line);
            if (m.matches()) {
                double time = Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2));
                // Word tags (enhanced LRC) are taken out of the text, so chat never shows them.
                LyricTiming.Parsed parsed = LyricTiming.parse(m.group(3), time);
                result.add(new LyricLine(time, parsed.text(), parsed));
            }
        }
        return result;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
