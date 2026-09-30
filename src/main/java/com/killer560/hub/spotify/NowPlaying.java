package com.killer560.hub.spotify;

/**
 * What Spotify is doing right now.
 *
 * @param kind       a song, an advert, or nothing
 * @param artist     the performer, or "" when the source cannot separate it from the title
 * @param title      the track title - blank unless {@code kind} is {@link Kind#TRACK}
 * @param positionMs how far into the track playback is, or null when the source cannot say. The desktop
 *                   window title carries no clock, so it is always null there and the lyric timer runs its
 *                   own stopwatch from the moment the track changed. A Spotify login is what would fill it in.
 */
public record NowPlaying(Kind kind, String artist, String title, Long positionMs) {

    public enum Kind {
        /** A real song, with an artist and a title. */
        TRACK,
        /**
         * An advert.
         *
         * <p>killer560 (2026-09-30): "if it is playing an ad then it says I'm listening to an ad now for the
         * song title portion." Spotify Free puts "Advertisement" in the window title, which used to read as
         * "nothing playing" - so the lyric line just froze on the last line of the previous song for thirty
         * seconds and then a new song appeared out of nowhere.
         */
        AD,
        /** Paused, closed, or between tracks. The lyric clock stops rather than running on. */
        PAUSED,
    }

    public static NowPlaying track(String artist, String title) {
        return new NowPlaying(Kind.TRACK, artist, title, null);
    }

    public static NowPlaying ad() {
        return new NowPlaying(Kind.AD, "", "", null);
    }

    public static NowPlaying paused() {
        return new NowPlaying(Kind.PAUSED, "", "", null);
    }

    /** The key a track change is detected on. Artist and title together, because titles repeat. */
    public String key() {
        return kind + "|||" + artist + "|||" + title;
    }
}
