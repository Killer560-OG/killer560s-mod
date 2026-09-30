package com.killer560.hub.spotify;

/** Where the mod finds out what is playing. */
public interface NowPlayingSource {

    /** Short name for the settings tab and the status line. */
    String displayName();

    /**
     * Whether this source can be used at all right now.
     *
     * <p>Separate from "is it playing anything": the desktop reader is unavailable when Spotify is not running,
     * and Last.fm is unavailable until an API key and username are entered. The tab shows which, so "no lyrics"
     * stops being a mystery.
     */
    boolean available();

    /** Why it is not available, for the status line. Empty when it is. */
    String unavailableReason();

    /** @return what is playing, or null when nothing is. Never throws; a failure reads as nothing playing. */
    NowPlaying fetch();
}
