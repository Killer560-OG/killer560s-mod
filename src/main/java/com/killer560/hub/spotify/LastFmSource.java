package com.killer560.hub.spotify;

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

/**
 * The old path: ask Last.fm what Spotify last told it.
 *
 * <p>Kept, not removed. {@link SpotifyDesktopSource} is better in every way when it applies - no setup, no
 * account, nothing leaving the PC, and a track change it sees instantly - but it only sees the desktop app on
 * this machine. Someone playing from their phone or from the web player has nothing for it to read, and for
 * them Last.fm is the only thing that works. It is the fallback rather than the default, and the five website
 * steps it costs are now opt-in instead of compulsory.
 *
 * <p>Lifted out of {@code LyricsEngine} unchanged on 2026-09-30, so the engine stops knowing where its
 * now-playing comes from.
 */
public final class LastFmSource implements NowPlayingSource {

    private static final String LASTFM_URL = "https://ws.audioscrobbler.com/2.0/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String displayName() {
        return "Last.fm";
    }

    @Override
    public boolean available() {
        return !SpotifyLyricsFeature.lastFmApiKey.isBlank() && !SpotifyLyricsFeature.lastFmUsername.isBlank();
    }

    @Override
    public String unavailableReason() {
        return available() ? "" : "no Last.fm API key and username entered yet";
    }

    @Override
    public NowPlaying fetch() {
        String apiKey = SpotifyLyricsFeature.lastFmApiKey;
        String username = SpotifyLyricsFeature.lastFmUsername;
        if (apiKey == null || apiKey.isBlank() || username == null || username.isBlank()) {
            return null;
        }
        try {
            String url = LASTFM_URL + "?method=user.getrecenttracks&user=" + urlEncode(username)
                    + "&api_key=" + urlEncode(apiKey) + "&format=json&limit=1";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Status only - the error body can echo request details (the URL carries the user's API key).
                SpotifyLyricsFeature.LOGGER.warn("Last.fm now-playing request failed: HTTP {}",
                        response.statusCode());
                return null;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject recentTracks = root.getAsJsonObject("recenttracks");
            if (recentTracks == null) {
                SpotifyLyricsFeature.LOGGER.warn("Last.fm response had no 'recenttracks' object");
                return null;
            }
            JsonElement trackEl = recentTracks.get("track");
            if (trackEl == null || trackEl.isJsonNull()) {
                return null;
            }
            JsonObject track = trackEl.isJsonArray()
                    ? (trackEl.getAsJsonArray().isEmpty() ? null : trackEl.getAsJsonArray().get(0).getAsJsonObject())
                    : trackEl.getAsJsonObject();
            if (track == null) {
                return null;
            }
            JsonObject attr = track.getAsJsonObject("@attr");
            boolean isPlaying = attr != null && attr.has("nowplaying")
                    && "true".equals(attr.get("nowplaying").getAsString());
            if (!isPlaying) {
                return null;
            }
            String artist = track.getAsJsonObject("artist").get("#text").getAsString();
            String title = track.get("name").getAsString();
            return new NowPlaying(artist, title);
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Last.fm poll failed: {}", String.valueOf(e));
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
