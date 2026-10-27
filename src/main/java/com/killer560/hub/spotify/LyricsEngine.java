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
        if (wordTimedLines != null) {
            // Word-timed lyrics of Spotify's own recording (Kugou KRC, matched on title, artist and length): every word
            // has its real start, so they win over lrclib's line times.
            syncedLyrics = wordTimedLines;
            durationSeconds = spotifyDurationSeconds;
            versionMismatch = false;
            mismatchSeconds = 0;
            return;
        }
        Choice c = chooseFrom(candidates, lastArtist, lastTitle, spotifyDurationSeconds);
        syncedLyrics = c.pick() == null ? List.of() : c.pick().lines();
        if (c.pick() != null || !candidates.isEmpty()) {
            durationSeconds = c.pick() == null ? 0 : c.pick().durationSeconds();
        }
        versionMismatch = c.mismatch();
        mismatchSeconds = c.nearestSeconds();
        String logKey = lastTrackKey + "|" + Math.round(spotifyDurationSeconds) + "|" + candidates.size();
        if (!candidates.isEmpty() && !logKey.equals(lastChoiceLog)) {
            lastChoiceLog = logKey;
            SpotifyLyricsFeature.LOGGER.info("Lyrics versions for '{}' by '{}' (Spotify {} s): {}", lastTitle, lastArtist,
                    spotifyDurationSeconds > 0 ? Math.round(spotifyDurationSeconds * 10) / 10.0 : "unknown", c.why());
        }
    }

    /** This track's word-timed lines (Kugou), or null when none were found (yet). */
    private List<LyricLine> wordTimedLines = null;
    private boolean wordLookupTried = false;

    /**
     * Word-timed lyrics per track, the last 64, misses included (an empty list): a song played again asks nobody, and a
     * song Kugou does not have is not asked about again this session.
     */
    private static final java.util.Map<String, List<LyricLine>> WORD_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, List<LyricLine>> eldest) {
            return size() > 64;
        }
    };

    /**
     * Once Spotify's length is known (the match needs it), asks Kugou once for word-timed lyrics of this recording, off
     * this thread. Caller holds the lock. Never blocks; a miss or a failure leaves lrclib's lines in place.
     */
    private void maybeLookUpWordTimed(String forTrackKey, String artist, String title) {
        if (wordLookupTried || !wantLyrics || spotifyDurationSeconds <= 0 || lastWasAd
                || !SpotifyHudConfig.getInstance().isWordTimedLyrics()) {
            return;
        }
        wordLookupTried = true;
        double seconds = spotifyDurationSeconds;
        LYRIC_FETCH.submit(() -> {
            List<LyricLine> found;
            boolean cached;
            synchronized (WORD_CACHE) {
                found = WORD_CACHE.get(forTrackKey);
            }
            cached = found != null;
            String why = "cached";
            if (found == null) {
                List<LyricLine> got = null;
                for (String t : titleSpellings(title)) {
                    if (got == null) {
                        got = KugouLyrics.fetch(artist, t, seconds);
                    }
                }
                why = KugouLyrics.lastResult();
                found = got == null ? List.of() : List.copyOf(got);
                if (!why.startsWith("failed") && !why.endsWith("failed")) {
                    synchronized (WORD_CACHE) {
                        WORD_CACHE.put(forTrackKey, found);   // a network failure is not cached: next play tries again
                    }
                }
            }
            synchronized (this) {
                if (!forTrackKey.equals(lastTrackKey)) {
                    return;
                }
                if (!found.isEmpty()) {
                    wordTimedLines = found;
                    lyricsAnswered = true;
                    choose();
                    publish(snapshot.kind());
                }
                SpotifyLyricsFeature.LOGGER.info("Word times for '{}': {}", title, found.isEmpty()
                        ? "none from Kugou (" + why + "), lrclib's line times are used"
                        : "Kugou KRC, " + found.size() + " lines" + (cached ? " (cached)" : ", " + why));
            }
        });
    }

    /** The last choice logged, so a re-choose with nothing new does not log again. */
    private String lastChoiceLog = "";

    /**
     * Which lrclib listing to use, and why (killer560, 2026-10-26: "it says lyrics found are for another version. make it
     * find the right version").
     *
     * @param pick           the listing whose lines are used, or null for none
     * @param mismatch       no listing is Spotify's length: the HUD says the lyrics are for another version
     * @param nearestSeconds the length of the nearest listing (for that note), 0 when none
     * @param why            every candidate considered and the reason the pick won, for the log
     */
    record Choice(Candidate pick, boolean mismatch, double nearestSeconds, String why) {
    }

    /**
     * Scores every candidate instead of taking the first that passes. A candidate is the SAME SONG when its title,
     * normalized ({@link #titleKey}: "Song - X Remix" and "Song (X Remix)" alike, "(feat. ...)", "- Remastered 2011",
     * "Explicit" and the like dropped), equals Spotify's, and its artist credit names Spotify's artist. Among those, one
     * within {@link #SAME_VERSION_SECONDS} of Spotify's length is the same RECORDING, the closest length winning. With
     * none that close, the closest same-song listing is still used, with the other-version note kept; with no same-song
     * listing at all, nothing is used. Without Spotify's length, the best name match (exact title first) is used.
     *
     * <p>Found on his log (2026-10-26): Spotify's "High Forever - Marlon Hoffstadt Remix" against lrclib's eight "High
     * Forever (Marlon Hoffstadt Remix)" listings, all 229-230 s: the old exact-title test refused every one, so the
     * right recording was there and the HUD still said "another version".
     */
    static Choice chooseFrom(List<Candidate> candidates, String artist, String title, double spotifySeconds) {
        if (candidates.isEmpty()) {
            return new Choice(null, false, 0, "no listings with synced lyrics");
        }
        String want = titleKey(title);
        StringBuilder why = new StringBuilder();
        Candidate bestSame = null;
        double bestSameDiff = Double.MAX_VALUE;
        Candidate closest = null;
        double closestDiff = Double.MAX_VALUE;
        double nearestAny = 0;
        double nearestAnyDiff = Double.MAX_VALUE;
        Candidate byName = null;
        int n = 0;
        for (Candidate c : candidates) {
            boolean titleOk = want.isEmpty() || titleKey(c.title()).equals(want);
            boolean artistOk = artistListHas(c.artist(), artist);
            double diff = spotifySeconds > 0 && c.durationSeconds() > 0 ? Math.abs(c.durationSeconds() - spotifySeconds)
                    : Double.MAX_VALUE;
            if (n++ < 12) {
                why.append(n == 1 ? "" : "; ").append('\'').append(c.title()).append("' / '").append(c.artist())
                        .append("' ").append(c.durationSeconds()).append(" s")
                        .append(titleOk ? "" : " (other title)").append(artistOk ? "" : " (other artist)");
            }
            if (diff < nearestAnyDiff) {
                nearestAnyDiff = diff;
                nearestAny = c.durationSeconds();
            }
            if (!titleOk || !artistOk) {
                continue;
            }
            if (byName == null) {
                byName = c;
            }
            if (diff <= SAME_VERSION_SECONDS && diff < bestSameDiff) {
                bestSameDiff = diff;
                bestSame = c;
            }
            if (diff < closestDiff) {
                closestDiff = diff;
                closest = c;
            }
        }
        if (candidates.size() > 12) {
            why.append("; and ").append(candidates.size() - 12).append(" more");
        }
        if (spotifySeconds <= 0) {
            Candidate pick = byName != null ? byName : candidates.get(0);
            return new Choice(pick, false, 0, why + " -> '" + pick.title() + "' " + pick.durationSeconds()
                    + " s: the best name match (Spotify's length not known)");
        }
        if (bestSame != null) {
            return new Choice(bestSame, false, 0, why + " -> '" + bestSame.title() + "' " + bestSame.durationSeconds()
                    + " s: same song, " + Math.round(bestSameDiff * 10) / 10.0 + " s from Spotify's length");
        }
        if (closest != null && closestDiff != Double.MAX_VALUE) {
            return new Choice(closest, true, closest.durationSeconds(), why + " -> '" + closest.title() + "' "
                    + closest.durationSeconds() + " s: same song but no listing within " + SAME_VERSION_SECONDS
                    + " s; the closest is used and the HUD says it is another version");
        }
        return new Choice(null, true, nearestAny, why + " -> none: no listing is this song by this artist");
    }

    /** The title as Spotify gives it, and with a trailing " - X" written as " (X)" when it has one. */
    static List<String> titleSpellings(String title) {
        int dash = title == null ? -1 : title.lastIndexOf(" - ");
        if (dash <= 0 || dash + 3 >= title.length()) {
            return title == null ? List.of() : List.of(title);
        }
        return List.of(title, title.substring(0, dash) + " (" + title.substring(dash + 3).trim() + ")");
    }

    /** The current track's album from Windows' media session, or "" (set by {@link #poll}). */
    private volatile String lastAlbum = "";

    /** The first candidate that is the same recording as Spotify's, or null (see {@link #chooseFrom}). */
    static Candidate pickVersion(List<Candidate> candidates, String artist, String title, double spotifySeconds) {
        Choice c = chooseFrom(candidates, artist, title, spotifySeconds);
        return c.mismatch() ? null : c.pick();
    }

    private static final Pattern FEAT = Pattern.compile(
            "[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]{0,120}[)\\]]|\\s-\\s*(?:feat\\.?|ft\\.?|featuring)\\s.{0,120}$"
                    + "|\\s(?:feat\\.?|ft\\.?|featuring)\\s.{0,120}$");
    /** Tags that name the same recording (a remaster, an explicit or clean label, a year), dropped before comparing. */
    private static final Pattern SAME_RECORDING_TAG = Pattern.compile(
            "\\b(?:\\d{4}\\s+)?(?:digital(?:ly)?\\s+)?remaster(?:ed)?(?:\\s+(?:version|edition))?(?:\\s+\\d{4})?\\b"
                    + "|\\b(?:explicit|clean)(?:\\s+version)?\\b|\\balbum version\\b|\\bmono\\b|\\bstereo\\b");

    /**
     * A title reduced to what tells songs and recordings apart: lower case, any "(feat. X)" / "feat. X" dropped, remaster,
     * explicit, clean, album-version, mono and stereo tags dropped, then only letters and digits kept as single-spaced
     * words. So "Song - Remastered 2011", "Song (Remastered)" and "Song" are one key, and "Song - X Remix" and
     * "Song (X Remix)" are one key, but "Song (Sped Up)", "Song - Live" and "Song (X Remix)" each stay apart from "Song".
     */
    static String titleKey(String title) {
        if (title == null) {
            return "";
        }
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        String key = words(SAME_RECORDING_TAG.matcher(FEAT.matcher(lower).replaceAll(" ")).replaceAll(" "));
        return key.isEmpty() ? words(lower) : key;   // a song CALLED "Clean" keeps its name
    }

    private static String words(String t) {
        StringBuilder b = new StringBuilder(t.length());
        boolean space = true;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                b.append(ch);
                space = false;
            } else if (!space) {
                b.append(' ');
                space = true;
            }
        }
        return b.toString().trim();
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
            long now = System.currentTimeMillis();
            lastAlbum = session == null ? "" : session.album();
            pollAt(nowPlaying, session == null ? null : session.latest(now), now, fullLyricsMode);
        } catch (Exception e) {
            SpotifyLyricsFeature.LOGGER.warn("Poll failed: {}", String.valueOf(e), e);
        }
    }

    /**
     * One poll with what the window title and the media session said, at wall time {@code now}. {@link #poll} calls it
     * with the real readings; the testkit calls it with made-up ones (a drifting, seeking, pausing Spotify) and checks
     * {@link #snapshot()} against the true position.
     */
    public synchronized void pollAt(NowPlaying nowPlaying, SpotifyMediaSession.Timeline timeline, long now,
                                    boolean fullLyricsMode) {
        {
            lastNowPlaying = nowPlaying;
            if (timeline != null) {
                lastSeenUpdatedMs = timeline.updatedMs();
            }

            if (nowPlaying.kind() == NowPlaying.Kind.PAUSED) {
                // The clock stops; the track is REMEMBERED. Forgetting it would announce the same song again
                // as a brand new track every time he paused and un-paused.
                if (clock.running()) {
                    resyncPending = true;   // Spotify's reading of the pause is exact: take the next one as it is
                }
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
                wordTimedLines = null;
                wordLookupTried = false;
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
                resyncPending = true;   // the resume was seen up to a poll late: Spotify's reading of it fixes that
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
                    if (clock.running()) {
                        resyncPending = true;
                    }
                    clock.pause(now);
                    pausedBySession = true;
                    // Spotify paused before this poll saw it: its paused position is where the line is held.
                    if (timeline.updatedMs() >= lastEventMs - EVENT_SLACK_MS) {
                        follow(timeline, now);
                    }
                } else if (timeline.playing() && timeline.updatedMs() >= lastEventMs - EVENT_SLACK_MS) {
                    follow(timeline, now);
                }
                maybeLookUpWordTimed(trackKey, nowPlaying.artist(), nowPlaying.title());
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
            Fetched fetched;
            synchronized (FETCH_CACHE) {
                fetched = FETCH_CACHE.get(forTrackKey);
            }
            if (fetched == null) {
                fetched = fetchLyrics(artist, title);
                if (!fetched.candidates().isEmpty()) {
                    synchronized (FETCH_CACHE) {
                        FETCH_CACHE.put(forTrackKey, fetched);
                    }
                }
            }
            final Fetched result = fetched;
            synchronized (this) {
                if (forTrackKey.equals(lastTrackKey)) {
                    candidates = result.candidates();
                    durationSeconds = result.durationSeconds();
                    lyricsAnswered = true;
                    choose();
                    publish(snapshot.kind());
                    int timed = 0;
                    for (LyricLine l : syncedLyrics) {
                        if (l.parsed().timed()) {
                            timed++;
                        }
                    }
                    SpotifyLyricsFeature.LOGGER.info("Lyrics for '{}': {} version(s) with synced lines, using {} line(s){}, {}",
                            title, candidates.size(), syncedLyrics.size(), versionMismatch ? " (none is Spotify's length)" : "",
                            syncedLyrics.isEmpty() ? "no words" : timed == 0
                                    ? "line times only (the lit word is an estimate within each line)"
                                    : timed + " line(s) with real word times");
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
        String album = lastAlbum;
        LYRIC_FETCH.submit(() -> {
            // lrclib's /api/get matches artist, title, album and length; Spotify's "Song - X Remix" is listed there as
            // "Song (X Remix)", so that spelling is tried too, and the album only when Windows gave one.
            Candidate found = null;
            for (String t : titleSpellings(title)) {
                if (found == null && !album.isBlank()) {
                    found = fetchByLength(artist, t, album, seconds);
                }
                if (found == null) {
                    found = fetchByLength(artist, t, "", seconds);
                }
            }
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

    /**
     * Keeps the clock on Spotify's own position (caller holds the lock). The position is Spotify's at the reading's own
     * time ({@code updatedMs}, Windows' LastUpdatedTime) carried forward to {@code now}, never "the position when we read
     * it", so a reading that sat in the pipe for half a second is still exact. The clock is set to it:
     * <ul>
     *   <li>on the first reading of a track (the stopwatch started when the title change was SEEN, up to a poll late);</li>
     *   <li>on any disagreement over {@link PlaybackClock#JUMP_MS} (a seek, a restart);</li>
     *   <li>on Spotify's first new reading after a pause or resume this engine saw (those readings are exact);</li>
     *   <li>for drift, when the median of the last three NEW readings (a changed {@code updatedMs}) is over
     *       {@link PlaybackClock#DRIFT_MS} off, eased in by at most {@link #SLEW_MS} a poll (2026-10-27: it used to step
     *       on any single reading over 60 ms, so one odd reading moved the lit word back and forth). Spotify refreshes its reading every few
     *       seconds while playing and at once on a seek, pause or resume (measured 2026-10-26: a seek's reading arrived
     *       130 ms after it), so the clock is re-synced that often.</li>
     * </ul>
     * A correction over {@value #LOG_DRIFT_MS} ms is logged with its size, so his log shows when and how far it drifted.
     * Right after a seek this mod asked for ({@link #nudge}) a reading Spotify took before the seek is not believed.
     */
    private void follow(SpotifyMediaSession.Timeline timeline, long now) {
        if (now < nudgeUntilMs && timeline.updatedMs() <= nudgeBeforeUpdatedMs) {
            return;   // taken before the seek we just asked for: it would undo the seek on screen until the next reading
        }
        long before = clock.elapsedMs(now);
        long real = timeline.positionAt(now);
        long diff = real - before;
        boolean fresh = timeline.updatedMs() != lastSyncUpdatedMs;
        boolean first = !positionFromSpotify;
        if (fresh) {
            lastSyncUpdatedMs = timeline.updatedMs();
        }
        if (first || Math.abs(diff) > PlaybackClock.JUMP_MS || (fresh && resyncPending)) {
            // A new track, a seek, or Spotify's own reading of a pause or resume: those readings are exact, so the
            // clock is SET to them at once, and the drift history starts over.
            clock.set(real, now);
            resyncPending = false;
            recentCount = 0;
            slewLeftMs = 0;
            corrections++;
            lastCorrectionMs = diff;
            if (Math.abs(diff) > LOG_DRIFT_MS) {
                String why = first ? "first reading of the track" : Math.abs(diff) > PlaybackClock.JUMP_MS
                        ? "seek" : "pause or resume seen late";
                SpotifyLyricsFeature.LOGGER.info("Spotify lyric clock corrected by {} ms ({}): {} -> {}",
                        (diff > 0 ? "+" : "") + diff, why, mmss(before), mmss(real));
            }
        } else {
            if (fresh) {
                // Drift: only when the MEDIAN of the last three new readings is off by more than DRIFT_MS, so one odd
                // reading never moves the clock, and then by at most SLEW_MS a poll, so the lit word never jumps.
                noteOffset(diff, now);
                recent[recentCount % recent.length] = diff;
                recentCount++;
                long med = median();
                if (recentCount >= 2 && Math.abs(med) > PlaybackClock.DRIFT_MS) {
                    slewLeftMs = med;
                    driftTotalMs = med;
                }
            }
            if (slewLeftMs != 0 && clock.running()) {
                long step = Math.max(-SLEW_MS, Math.min(SLEW_MS, slewLeftMs));
                clock.set(clock.elapsedMs(now) + step, now);
                slewLeftMs -= step;
                for (int i = 0; i < recent.length; i++) {
                    recent[i] -= step;   // the stored offsets were measured against the clock before this step
                }
                if (slewLeftMs == 0) {
                    corrections++;
                    lastCorrectionMs = driftTotalMs;
                    if (Math.abs(driftTotalMs) > LOG_DRIFT_MS) {
                        SpotifyLyricsFeature.LOGGER.info("Spotify lyric clock eased {} ms back onto Spotify (drift)",
                                (driftTotalMs > 0 ? "+" : "") + driftTotalMs);
                    }
                }
            }
        }
        positionFromSpotify = true;
    }

    /** Drift is eased in by at most this much a poll (250 ms), so a lit word never jumps back more than this. */
    static final long SLEW_MS = 30;
    /** A pause, resume or track start was just seen: the next new reading is taken exactly, not filtered. */
    private boolean resyncPending;
    private final long[] recent = new long[3];
    private int recentCount;
    private long slewLeftMs;
    private long driftTotalMs;

    private long median() {
        int n = Math.min(recentCount, recent.length);
        if (n == 0) {
            return 0;
        }
        long a = recent[0];
        long b = n > 1 ? recent[1] : a;
        long c = n > 2 ? recent[2] : b;
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    // ---- what Spotify's readings looked like, for his log (2026-10-27: "it's now nowhere near the right word") -----
    private long checkSinceMs;
    private int checkReadings;
    private long checkMaxAbs;
    private long checkSumAbs;

    /**
     * Every new reading's disagreement with the clock is gathered, and once a minute, if any was over 40 ms, one line
     * says how far off they were. The word highlight is only as good as these readings, so his next log shows whether
     * Spotify's position is steady (offsets near 0) or noisy.
     */
    private void noteOffset(long diff, long now) {
        if (checkSinceMs == 0) {
            checkSinceMs = now;
        }
        checkReadings++;
        checkMaxAbs = Math.max(checkMaxAbs, Math.abs(diff));
        checkSumAbs += Math.abs(diff);
        if (now - checkSinceMs >= 60_000) {
            if (checkMaxAbs > 40) {
                SpotifyLyricsFeature.LOGGER.info("Spotify clock check: {} reading(s) in {} s, off by {} ms on average, {} ms at most",
                        checkReadings, (now - checkSinceMs) / 1000, checkSumAbs / Math.max(1, checkReadings), checkMaxAbs);
            }
            checkSinceMs = now;
            checkReadings = 0;
            checkMaxAbs = 0;
            checkSumAbs = 0;
        }
    }

    /** Corrections at least this big are logged. */
    static final long LOG_DRIFT_MS = 300;
    /** How long after a {@link #nudge} readings from before it are ignored. */
    private static final long NUDGE_HOLD_MS = 2000;

    private long lastSyncUpdatedMs = Long.MIN_VALUE;
    private long lastSeenUpdatedMs = Long.MIN_VALUE;
    private long nudgeBeforeUpdatedMs = Long.MIN_VALUE;
    private long nudgeUntilMs = 0;
    // Test hooks (read by the testkit).
    private volatile long corrections;
    private volatile long lastCorrectionMs;

    /**
     * Moves the clock by {@code deltaMs} at once, for a Rewind or Skip Ahead this mod just sent to Spotify, so the lyric
     * jumps with the press instead of a reading later; Spotify's own reading of the seek (about 130 ms later) then
     * corrects it to the exact spot. Readings taken before the press are ignored for {@value #NUDGE_HOLD_MS} ms.
     */
    public synchronized void nudge(long deltaMs, long now) {
        if (lastTrackKey == null || lastWasAd) {
            return;
        }
        long target = Math.max(0, clock.elapsedMs(now) + deltaMs);
        if (spotifyDurationSeconds > 0) {
            target = Math.min(target, Math.max(0, (long) (spotifyDurationSeconds * 1000) - 1000));
        }
        clock.set(target, now);
        nudgeBeforeUpdatedMs = lastSeenUpdatedMs;
        nudgeUntilMs = now + NUDGE_HOLD_MS;
        publish(snapshot.kind());
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

    /**
     * lrclib's answers per track (artist and title), the last 64: playing a song again, or the same song coming round in
     * a playlist, asks lrclib nothing (his log showed one song looked up three times in a minute, 2026-10-26). The
     * by-length lookups land in the same entry. A failed lookup is not cached, so the next play tries again.
     */
    private static final java.util.Map<String, Fetched> FETCH_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Fetched> eldest) {
            return size() > 64;
        }
    };

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
    private Candidate fetchByLength(String artist, String title, String album, long seconds) {
        try {
            String url = LRCLIB_GET_URL + "?artist_name=" + urlEncode(artist) + "&track_name=" + urlEncode(title)
                    + (album.isBlank() ? "" : "&album_name=" + urlEncode(album)) + "&duration=" + seconds;
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
