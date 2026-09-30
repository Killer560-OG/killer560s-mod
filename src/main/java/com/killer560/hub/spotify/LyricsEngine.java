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
 * <p>It no longer knows WHERE the now-playing came from: it is handed a {@link NowPlayingSource} and asks it.
 * That split is what let the Last.fm account, API key and website steps become optional on 2026-09-30 - the
 * Spotify desktop app can answer the same question with nothing set up at all. Only the lyrics themselves
 * still come off the network, from lrclib.net, and those need no key.
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
    private List<LyricLine> syncedLyrics = List.of();
    private Long trackDetectTimeMs = null;
    private long transitionMessageUntilMs = 0;

    public record LyricLine(double timeSeconds, String text) {
    }

    /** Call roughly every 2 seconds. Never throws - a failed poll just keeps showing the last known lyric.
     *  {@code fullLyricsMode} controls only the WORDING of the track-change announcement: "And that was
     *  ___" reads fine when the listener has actually been seeing lyric lines, but makes no sense in
     *  Song-Title-Only mode where they never saw any - that mode gets "I just finished listening to ___,
     *  now I'm listening to ___" instead. Which one fired is exposed via {@link #isCurrentLyricTransition()}
     *  so the caller can detect a track-change message without depending on its exact wording. */
    public synchronized void poll(NowPlayingSource source, int timingOffsetMs, boolean fullLyricsMode) {
        if (source == null) {
            return;
        }
        try {
            NowPlaying nowPlaying = source.fetch();

            if (nowPlaying == null) {
                currentLyric = "";
                currentIsTransition = false;
                lastTrackKey = null;
                syncedLyrics = List.of();
                trackDetectTimeMs = null;
                return;
            }

            String trackKey = nowPlaying.artist() + "|||" + nowPlaying.title();

            if (!trackKey.equals(lastTrackKey)) {
                if (lastTrackKey != null) {
                    String lastTitle = lastTrackKey.substring(lastTrackKey.indexOf("|||") + 3);
                    currentLyric = fullLyricsMode
                            ? "And that was " + lastTitle + ", now playing " + nowPlaying.title()
                            : "I just finished listening to " + lastTitle + ", now I'm listening to " + nowPlaying.title();
                    currentIsTransition = true;
                    transitionMessageUntilMs = System.currentTimeMillis() + 5000;
                }
                lastTrackKey = trackKey;
                trackDetectTimeMs = System.currentTimeMillis();
                syncedLyrics = fetchLyrics(nowPlaying.artist(), nowPlaying.title());
                SpotifyLyricsFeature.LOGGER.info("Now playing: artist='{}' title='{}' -> {} synced lines found",
                        nowPlaying.artist(), nowPlaying.title(), syncedLyrics.size());
            }

            if (transitionMessageUntilMs != 0 && System.currentTimeMillis() >= transitionMessageUntilMs) {
                transitionMessageUntilMs = 0;
                currentLyric = "";
                currentIsTransition = false;
            }

            // Skipped while a transition message is still actively showing (transitionMessageUntilMs != 0)
            // - otherwise this would overwrite it with the new track's first lyric line on the very same
            // poll call it was just set, since trackDetectTimeMs/syncedLyrics are already populated above
            // by then. That silently ate the transition message on every track change into a song that
            // had synced lyrics - especially bad for Song-Title-Only mode, where the transition message is
            // the ONLY thing that mode ever sends.
            if (transitionMessageUntilMs == 0 && trackDetectTimeMs != null && !syncedLyrics.isEmpty()) {
                // A source that knows the real playback position is believed over the stopwatch.
                //
                // Counting from "when we first saw this track" only works if we saw it at second zero, and it
                // drifts the moment the user seeks, pauses or joins a song part-way through - which is what the
                // offset slider was papering over. Nothing supplies a position yet (a window title has no
                // clock), so this is the branch a Spotify login would light up; until then it falls through to
                // the stopwatch exactly as before.
                double elapsed = nowPlaying.positionMs() != null
                        ? nowPlaying.positionMs() / 1000.0
                        : (System.currentTimeMillis() - trackDetectTimeMs) / 1000.0 + timingOffsetMs / 1000.0;
                currentLyric = currentLyricAt(elapsed);
                currentIsTransition = false;
            }
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Poll failed: {}", String.valueOf(e), e);
        }
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
