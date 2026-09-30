package com.killer560.hub.spotify;

/** Which now-playing source the user has picked. */
public enum SourceKind {

    /**
     * Read the Spotify app if it is running, and fall back to Last.fm only if it is not and Last.fm is set up.
     * The default, because it is the setting that needs nothing done to it.
     */
    AUTO("Automatic"),

    /** The Spotify desktop app on this PC, and nothing else. No account, no key, no website. */
    SPOTIFY_APP("Spotify App"),

    /** Last.fm scrobbles. For playing from a phone or the web player. */
    LASTFM("Last.fm");

    public final String displayName;

    SourceKind(String displayName) {
        this.displayName = displayName;
    }

    public SourceKind next() {
        SourceKind[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }

    public SourceKind previous() {
        SourceKind[] vals = values();
        return vals[(vals.length + this.ordinal() - 1) % vals.length];
    }
}
