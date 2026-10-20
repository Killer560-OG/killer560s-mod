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
     * The lyric clock, which stops when Spotify does.
     *
     * <p>killer560 (2026-09-30): "is it possible to make it so you no longer need the delay since it reads
     * automatically for when the lyrics come out." Yes, and the offset slider is gone. It only ever existed to
     * compensate for Last.fm taking about five seconds to report a track change; the window title flips within
     * a frame of the track actually changing, so the right offset is zero.
     *
     * <p>What still has to be handled is PAUSING, and that is what these two fields do: {@code playingSinceMs}
     * is when playback last resumed and {@code playedMs} is everything before that. A paused Spotify shows
     * "Spotify Premium" in its title, so the pause is visible and the clock can stop instead of running on -
     * without which a two-minute pause would leave every later line two minutes early for the rest of the song.
     *
     * <p>The one case this cannot get right is SEEKING, or starting the mod part-way through a track: a window
     * title carries no playback position, so the stopwatch begins when the title was first seen. A Spotify
     * login would supply a real position and fix it; nothing short of that can.
     */
    private Long playingSinceMs = null;
    private long playedMs = 0;

    /** The current track's artist and title, kept through a pause so the HUD can still name it. */
    private String lastArtist = "";
    private String lastTitle = "";
    private boolean lastWasAd = false;
    /** lrclib's {@code duration} for the current track in seconds, or 0 when it gave none. */
    private double durationSeconds = 0;
    /** Whether lrclib has answered for the current track (with or without lyrics). */
    private boolean lyricsAnswered = false;

    /** {@code parsed} carries the line's words and, for an enhanced-LRC line, each word's start. */
    public record LyricLine(double timeSeconds, String text, LyricTiming.Parsed parsed) {
    }

    /**
     * What the Spotify HUD draws, published whole by the poll thread and read by the render thread with no lock.
     * The clock is kept as its two parts, so the HUD can run it on every frame for a smooth word highlight
     * instead of stepping on the 250 ms poll.
     *
     * @param playingSinceMs wall time playback last resumed, or 0 while paused
     * @param lines          the synced lyric lines (immutable), empty while unknown or when lrclib had none
     */
    public record Snapshot(NowPlaying.Kind kind, String artist, String title, long playedMs, long playingSinceMs,
                           double durationSeconds, List<LyricLine> lines, boolean lyricsAnswered) {

        public static final Snapshot NONE =
                new Snapshot(NowPlaying.Kind.PAUSED, "", "", 0, 0, 0, List.of(), false);

        /** Seconds played of this track at wall time {@code nowMs}, pauses excluded. */
        public double elapsedSeconds(long nowMs) {
            return (playedMs + (playingSinceMs == 0 ? 0 : Math.max(0, nowMs - playingSinceMs))) / 1000.0;
        }

        public boolean hasTrack() {
            return !title.isEmpty();
        }

        /** Index of the line being sung at {@code seconds}, or -1 before the first. */
        public int lineAt(double seconds) {
            int idx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).timeSeconds() <= seconds) {
                    idx = i;
                } else {
                    break;
                }
            }
            return idx;
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
        snapshot = new Snapshot(shown, lastArtist, lastWasAd ? "" : lastTitle, playedMs,
                playingSinceMs == null ? 0 : playingSinceMs, durationSeconds, syncedLyrics, lyricsAnswered);
    }

    /**
     * Call roughly every 2 seconds. Never throws - a failed poll just keeps showing the last known lyric.
     *
     * <p>{@code fullLyricsMode} controls only the WORDING of the track-change announcement: "And that was
     * ___" reads fine when the listener has actually been seeing lyric lines, but makes no sense in
     * Song-Title-Only mode where they never saw any - that mode gets "I just finished listening to ___, now
     * I'm listening to ___" instead. Which one fired is exposed via {@link #isCurrentLyricTransition()} so the
     * caller can detect a track-change message without depending on its exact wording.
     */
    public synchronized void poll(SpotifyDesktopSource source, boolean fullLyricsMode) {
        if (source == null) {
            return;
        }
        try {
            NowPlaying nowPlaying = source.fetch();
            long now = System.currentTimeMillis();

            if (nowPlaying.kind() == NowPlaying.Kind.PAUSED) {
                // The clock stops; the track is REMEMBERED. Forgetting it would announce the same song again
                // as a brand new track every time he paused and un-paused.
                if (playingSinceMs != null) {
                    playedMs += now - playingSinceMs;
                    playingSinceMs = null;
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
                lyricsAnswered = isAd;
                playedMs = 0;
                playingSinceMs = now;
                syncedLyrics = List.of();
                // Fetched OFF this thread, because the clock is already running.
                //
                // lrclib is an HTTP round trip with a ten-second timeout, and it used to happen inline here -
                // inside a synchronized poll - so every track change stalled the poll loop for however long
                // the request took while the song played on. The clock is correct either way, but no line
                // could be emitted until it returned, so the first lyrics of every song arrived late by the
                // request time. Now the poll returns immediately and the lines appear the moment they land.
                if (!isAd) {
                    requestLyrics(trackKey, nowPlaying.artist(), nowPlaying.title());
                }
                SpotifyLyricsFeature.LOGGER.info("Now playing: artist='{}' title='{}'",
                        nowPlaying.artist(), title);
            } else if (playingSinceMs == null) {
                playingSinceMs = now;   // resumed after a pause, same track
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
                // A source that knows the real playback position is believed over the stopwatch. Nothing
                // supplies one yet - a window title has no clock - so this is the branch a Spotify login
                // would light up.
                double elapsed = (nowPlaying.positionMs() != null
                        ? nowPlaying.positionMs() / 1000.0
                        : elapsedSeconds(now)) + SEND_LEAD_SECONDS;
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
                    syncedLyrics = fetched.lines();
                    durationSeconds = fetched.durationSeconds();
                    lyricsAnswered = true;
                    publish(snapshot.kind());
                    SpotifyLyricsFeature.LOGGER.info("Lyrics for '{}': {} synced line(s)", title, fetched.lines().size());
                }
            }
        });
    }

    /** What lrclib gave for one track: its synced lines (immutable, maybe empty) and its length (0 = unknown). */
    private record Fetched(List<LyricLine> lines, double durationSeconds) {
        static final Fetched NOTHING = new Fetched(List.of(), 0);
    }

    /** Playing time on this track, with any paused stretches excluded. */
    private double elapsedSeconds(long now) {
        long total = playedMs + (playingSinceMs == null ? 0 : now - playingSinceMs);
        return total / 1000.0;
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
            JsonObject match = pick(results, artist, title, true);
            if (match == null) {
                match = pick(results, artist, title, false);
            }
            if (match == null) {
                match = results.get(0).getAsJsonObject();
            }
            double duration = duration(match);
            if (!match.has("syncedLyrics") || match.get("syncedLyrics").isJsonNull()) {
                SpotifyLyricsFeature.LOGGER.info(
                        "lrclib had {} result(s) for artist='{}' title='{}' but none had synced lyrics",
                        results.size(), artist, title);
                return new Fetched(List.of(), duration);
            }

            return new Fetched(List.copyOf(parseLrc(match.get("syncedLyrics").getAsString())), duration);
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("lrclib search threw for artist='{}' title='{}': {}", artist, title, e.toString());
            return Fetched.NOTHING;
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

    /**
     * The best result with synced lyrics: {@code exact} requires the artist and title to match what Spotify
     * reported, ignoring case and surrounding space.
     */
    private static JsonObject pick(JsonArray results, String artist, String title, boolean exact) {
        for (JsonElement el : results) {
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("syncedLyrics") || obj.get("syncedLyrics").isJsonNull()) {
                continue;
            }
            if (!exact) {
                return obj;
            }
            if (same(obj, "trackName", title) && same(obj, "artistName", artist)) {
                return obj;
            }
        }
        return null;
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
