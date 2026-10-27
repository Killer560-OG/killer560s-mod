package com.killer560.hub.spotify;

/**
 * How far into the current track Spotify is: a stopwatch that stops while Spotify is paused and is corrected to
 * Spotify's own position whenever that is known and disagrees by more than {@link #JUMP_MS}.
 *
 * <p>killer560 (2026-10-20): "make sure it realizes if I go back in time or something from spotify." Without a real
 * position the stopwatch can only start at a track change, so a scrub, or starting the game part-way through a song,
 * left every lyric line wrong for the rest of the track. {@link SpotifyMediaSession} reads Spotify's position from
 * Windows' media controls, and {@link #follow} snaps the clock to it on a jump. Small differences are left alone, so the
 * word highlight does not twitch on each poll.
 *
 * <p>Not thread-safe; {@link LyricsEngine} holds its lock around every call. No Minecraft types, so it can be checked
 * on its own.
 */
final class PlaybackClock {

    /** A disagreement with Spotify's position larger than this is a seek (or a late start), and the clock snaps to it. */
    static final long JUMP_MS = 1000;
    /**
     * A disagreement larger than this, on a reading Spotify took since the last one, is drift and is corrected
     * (killer560, 2026-10-26: "sometimes the highlighted lyric still gets off by a bit"). Below it the reading is left
     * alone, so the highlight does not twitch. Before 2026-10-26 only {@link #JUMP_MS} corrected anything, so an error
     * under a second (a track change seen a poll late, a resume seen late) stayed for the rest of the track.
     */
    static final long DRIFT_MS = 40;

    private long playedMs;
    /** Wall time playback last resumed, or -1 while paused. */
    private long playingSinceMs = -1;

    /** A new track: zero, running from {@code nowMs}. */
    void start(long nowMs) {
        playedMs = 0;
        playingSinceMs = nowMs;
    }

    /** Freezes the clock where it is. */
    void pause(long nowMs) {
        if (playingSinceMs >= 0) {
            playedMs += Math.max(0, nowMs - playingSinceMs);
            playingSinceMs = -1;
        }
    }

    /** Runs the clock on from where it was frozen. */
    void resume(long nowMs) {
        if (playingSinceMs < 0) {
            playingSinceMs = nowMs;
        }
    }

    boolean running() {
        return playingSinceMs >= 0;
    }

    long elapsedMs(long nowMs) {
        return playedMs + (playingSinceMs < 0 ? 0 : Math.max(0, nowMs - playingSinceMs));
    }

    /**
     * Believes Spotify's position {@code realMs} when it differs from the clock by more than {@link #JUMP_MS}.
     *
     * @return true when the clock jumped (a seek, a restart of the track, or the first reading part-way in)
     */
    boolean follow(long realMs, long nowMs) {
        long diff = realMs - elapsedMs(nowMs);
        if (Math.abs(diff) <= JUMP_MS) {
            return false;
        }
        playedMs = Math.max(0, realMs);
        if (playingSinceMs >= 0) {
            playingSinceMs = nowMs;
        }
        return true;
    }

    /** Sets the clock to {@code realMs} at {@code nowMs}, running or not as it was. */
    void set(long realMs, long nowMs) {
        playedMs = Math.max(0, realMs);
        if (playingSinceMs >= 0) {
            playingSinceMs = nowMs;
        }
    }

    /** The two parts the HUD's {@link LyricsEngine.Snapshot} carries: time played before {@link #sinceMs()}. */
    long playedMs() {
        return playedMs;
    }

    /** Wall time playback last resumed, or 0 while paused (the Snapshot's convention). */
    long sinceMs() {
        return playingSinceMs < 0 ? 0 : playingSinceMs;
    }
}
