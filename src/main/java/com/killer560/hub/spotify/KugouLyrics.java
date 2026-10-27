package com.killer560.hub.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModNet;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Word-timed lyrics from Kugou's public lyrics service (killer560, 2026-10-27: "find a way they don't have to sign up or
 * anything ... it needs to be as good as physically possible").
 *
 * <p>lrclib almost never has word times (1 of about 380 listings searched); Kugou's KRC lyrics carry a start and length for
 * every word, and its lyrics search and download need no account, cookie or key. The same two calls are what open-source
 * lyric tools use for KRC (Lyricify, LyricsX's Kugou source, the many "krc decoder" scripts): {@code lyrics.kugou.com/search}
 * by "artist - title" and length, then {@code lyrics.kugou.com/download?fmt=krc}, whose body is base64 of "krc1" followed by
 * the zlib-compressed lyrics XORed with a fixed 16-byte key that every KRC reader carries.
 *
 * <p>Nothing about the player is sent: the artist, the title and the length only, as lrclib already gets. One search and
 * one download per track, only when the track has no word-timed lyrics yet, from the lyrics thread, with a short timeout;
 * any failure (down, changed, no match) just leaves lrclib's line times in place.
 *
 * <p>Format: {@code [lineStartMs,lineLengthMs]<wordOffsetMs,wordLengthMs,0>word<...>word}, offsets from the line's start.
 * Credit lines ("Lyrics by: ...", the "Title - Artist" line at 0:00) are dropped.
 */
public final class KugouLyrics {

    private static final String SEARCH_URL = ModNet.url("kugou", "http://lyrics.kugou.com/search");
    private static final String DOWNLOAD_URL = ModNet.url("kugou", "http://lyrics.kugou.com/download");
    /** The KRC key every KRC reader uses. */
    private static final byte[] KEY = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105};

    private static final Pattern LINE = Pattern.compile("^\\[(\\d{1,8}),(\\d{1,8})](.*)$");
    private static final Pattern WORD = Pattern.compile("<(\\d{1,8}),(\\d{1,8}),\\d{1,3}>([^<]*)");
    private static final Pattern CREDIT = Pattern.compile(
            "(?i)(?:^|\\s)(?:lyrics|lyricist|composed|composer|produced|producer|written|arranged|mixed|mastered)\\s*by|[:\\uff1a]"
                    + "|\\u4f5c\\u8bcd|\\u4f5c\\u66f2|\\u7f16\\u66f2|\\u5236\\u4f5c");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    // Test hooks (read by the testkit).
    private static volatile String lastResult = "";

    private KugouLyrics() {
    }

    /** One search hit. */
    record Hit(String id, String accessKey, String artist, String title, double durationSeconds) {
    }

    /**
     * Word-timed lines for the track, or null when Kugou has no version of it within {@link LyricsEngine#SAME_VERSION_SECONDS}
     * of {@code spotifySeconds}, or is unreachable. Blocking: call from the lyrics thread only.
     */
    static List<LyricsEngine.LyricLine> fetch(String artist, String title, double spotifySeconds) {
        try {
            String keyword = (artist == null || artist.isBlank() ? "" : artist + " - ") + title;
            String url = SEARCH_URL + "?ver=1&man=yes&client=pc&keyword=" + enc(keyword) + "&duration="
                    + Math.round(spotifySeconds * 1000) + "&hash=";
            String body = get(url);
            if (body == null) {
                lastResult = "search failed";
                return null;
            }
            Hit hit = pick(body, artist, title, spotifySeconds);
            if (hit == null) {
                lastResult = "no version within " + LyricsEngine.SAME_VERSION_SECONDS + " s";
                return null;
            }
            String dl = get(DOWNLOAD_URL + "?ver=1&client=pc&id=" + enc(hit.id()) + "&accesskey=" + enc(hit.accessKey())
                    + "&fmt=krc&charset=utf8");
            if (dl == null) {
                lastResult = "download failed";
                return null;
            }
            JsonObject o = JsonParser.parseString(dl).getAsJsonObject();
            String content = o.has("content") && !o.get("content").isJsonNull() ? o.get("content").getAsString() : "";
            String krc = decode(content);
            List<LyricsEngine.LyricLine> lines = krc == null ? List.of() : parse(krc);
            int words = 0;
            for (LyricsEngine.LyricLine l : lines) {
                words += l.parsed().words().size();
            }
            if (lines.isEmpty() || words < 10) {
                lastResult = "no word-timed lines";
                return null;
            }
            lastResult = "'" + hit.title() + "' / '" + hit.artist() + "' " + hit.durationSeconds() + " s, " + lines.size()
                    + " lines, " + words + " words";
            return lines;
        } catch (Exception e) {
            lastResult = "failed: " + e.getClass().getSimpleName();
            return null;
        }
    }

    static String lastResult() {
        return lastResult;
    }

    /** The first search candidate that is the same song (normalized title, artist) and within 2 s of Spotify's length. */
    static Hit pick(String searchJson, String artist, String title, double spotifySeconds) {
        JsonElement root = JsonParser.parseString(searchJson);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("candidates")) {
            return null;
        }
        JsonArray cands = root.getAsJsonObject().getAsJsonArray("candidates");
        String want = LyricsEngine.titleKey(title);
        Hit best = null;
        double bestDiff = Double.MAX_VALUE;
        for (JsonElement el : cands) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject c = el.getAsJsonObject();
            String song = str(c, "song");
            String singer = str(c, "singer");
            double secs = c.has("duration") ? c.get("duration").getAsDouble() / 1000.0 : 0;
            if (!LyricsEngine.titleKey(song).equals(want) || !LyricsEngine.artistListHas(singer, artist)) {
                continue;
            }
            double diff = spotifySeconds > 0 ? Math.abs(secs - spotifySeconds) : 0;
            if (spotifySeconds > 0 && diff > LyricsEngine.SAME_VERSION_SECONDS) {
                continue;
            }
            if (diff < bestDiff) {
                bestDiff = diff;
                best = new Hit(str(c, "id"), str(c, "accesskey"), singer, song, secs);
            }
        }
        return best;
    }

    /** The KRC text inside a download's base64 {@code content}, or null. */
    static String decode(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            if (raw.length <= 4) {
                return null;
            }
            byte[] x = new byte[raw.length - 4];
            for (int i = 0; i < x.length; i++) {
                x[i] = (byte) (raw[i + 4] ^ KEY[i % KEY.length]);
            }
            Inflater inf = new Inflater();
            inf.setInput(x);
            ByteArrayOutputStream out = new ByteArrayOutputStream(x.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
                if (out.size() > 2_000_000) {
                    break;   // no real lyrics are this big
                }
            }
            inf.end();
            String s = out.toString(StandardCharsets.UTF_8);
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * KRC text to lyric lines with a start per word ({@link LyricTiming.Parsed#timed()}). A token without a trailing space
     * is a syllable joined to the next token (one word); a word starts where its first token does. Credit lines before the
     * first sung line are dropped.
     */
    static List<LyricsEngine.LyricLine> parse(String krc) {
        List<LyricsEngine.LyricLine> out = new ArrayList<>();
        boolean sung = false;
        for (String raw : krc.split("\\r?\\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) {
                continue;
            }
            double lineStart = Integer.parseInt(m.group(1)) / 1000.0;
            Matcher w = WORD.matcher(m.group(3));
            List<String> words = new ArrayList<>();
            List<Double> starts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            double curStart = 0;
            while (w.find()) {
                double at = lineStart + Integer.parseInt(w.group(1)) / 1000.0;
                String tok = w.group(3).replace('§', '?');
                if (cur.length() == 0) {
                    curStart = at;
                }
                cur.append(tok);
                if (tok.endsWith(" ") || tok.endsWith("　")) {
                    flush(cur, curStart, words, starts);
                }
            }
            flush(cur, curStart, words, starts);
            String text = String.join(" ", words);
            if (!sung && (text.isEmpty() || CREDIT.matcher(text).find() || lineStart < 0.5)) {
                continue;   // the "Title - Artist" line at 0:00 and the credits before the first sung line
            }
            if (words.isEmpty()) {
                continue;
            }
            sung = true;
            double[] st = new double[starts.size()];
            for (int i = 0; i < st.length; i++) {
                st[i] = starts.get(i);
            }
            out.add(new LyricsEngine.LyricLine(lineStart, text, new LyricTiming.Parsed(text, List.copyOf(words), st)));
        }
        return out;
    }

    private static void flush(StringBuilder cur, double start, List<String> words, List<Double> starts) {
        String word = cur.toString().trim();
        cur.setLength(0);
        if (word.isEmpty()) {
            return;
        }
        for (String part : word.split("\\s+")) {   // a token holding two words keeps one start for both
            words.add(part);
            starts.add(start);
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Test hook: answers a request instead of the network (null answer = a failed request). */
    static volatile java.util.function.Function<String, String> testResponder;

    private static String get(String url) {
        java.util.function.Function<String, String> t = testResponder;
        if (t != null) {
            return t.apply(url);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> r = ModNet.send(HTTP, req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
