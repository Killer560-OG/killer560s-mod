package com.killer560.hub.spotify;

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

    private static final String LRCLIB_URL = "https://lrclib.net/api/search";
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

    public record LyricLine(double timeSeconds, String text) {
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
                playedMs = 0;
                playingSinceMs = now;
                // No lyrics to look up for an advert, and no point asking lrclib for one.
                syncedLyrics = isAd ? List.of() : fetchLyrics(nowPlaying.artist(), nowPlaying.title());
                SpotifyLyricsFeature.LOGGER.info("Now playing: artist='{}' title='{}' -> {} synced lines found",
                        nowPlaying.artist(), title, syncedLyrics.size());
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
                double elapsed = nowPlaying.positionMs() != null
                        ? nowPlaying.positionMs() / 1000.0
                        : elapsedSeconds(now);
                currentLyric = currentLyricAt(elapsed);
                currentIsTransition = false;
            }
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Poll failed: {}", String.valueOf(e), e);
        }
    }

    /** What the title portion says while an advert plays. */
    private static final String AD_TITLE = "an ad";

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

    private List<LyricLine> fetchLyrics(String artist, String title) {
        try {
            String url = LRCLIB_URL + "?artist_name=" + urlEncode(artist) + "&track_name=" + urlEncode(title);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                SpotifyLyricsFeature.LOGGER.warn("lrclib search failed: HTTP {} for artist='{}' title='{}'",
                        response.statusCode(), artist, title);
                return List.of();
            }

            JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
            if (results.isEmpty()) {
                SpotifyLyricsFeature.LOGGER.info("lrclib had no results at all for artist='{}' title='{}'", artist, title);
                return List.of();
            }

            JsonObject match = null;
            for (JsonElement el : results) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("syncedLyrics") && !obj.get("syncedLyrics").isJsonNull()) {
                    match = obj;
                    break;
                }
            }
            if (match == null) {
                match = results.get(0).getAsJsonObject();
            }
            if (!match.has("syncedLyrics") || match.get("syncedLyrics").isJsonNull()) {
                SpotifyLyricsFeature.LOGGER.info(
                        "lrclib had {} result(s) for artist='{}' title='{}' but none had synced lyrics",
                        results.size(), artist, title);
                return List.of();
            }

            return parseLrc(match.get("syncedLyrics").getAsString());
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("lrclib search threw for artist='{}' title='{}': {}", artist, title, e.toString());
            return List.of();
        }
    }

    private List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> result = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            Matcher m = LRC_LINE.matcher(line);
            if (m.matches()) {
                double time = Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2));
                result.add(new LyricLine(time, m.group(3).trim()));
            }
        }
        return result;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
