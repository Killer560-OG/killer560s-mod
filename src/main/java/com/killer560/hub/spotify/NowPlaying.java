package com.killer560.hub.spotify;

/**
 * What is playing right now, from whichever source found it.
 *
 * @param artist    the performer, or "" when the source cannot separate it from the title
 * @param title     the track title - never blank for a real result
 * @param positionMs how far into the track playback is, or null when the source cannot say. Only the Spotify
 *                   Web API can; the desktop window title carries no clock, so the lyric timer falls back to
 *                   "when the title changed, plus the offset slider" for that source.
 * @param playing   false when the source can tell playback is paused. A source that cannot tell says true.
 */
public record NowPlaying(String artist, String title, Long positionMs, boolean playing) {

    public NowPlaying(String artist, String title) {
        this(artist, title, null, true);
    }

    /** The key a track change is detected on. Artist and title together, because titles repeat. */
    public String key() {
        return artist + "|||" + title;
    }
}
