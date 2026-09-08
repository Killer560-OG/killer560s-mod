package com.killer560.hub.spotify;

public enum ProfanityLevel {
    ALL("Block All Profanity"),
    MINIMAL("Block Minimal"),
    NONE("Block None");

    public final String displayName;

    ProfanityLevel(String displayName) {
        this.displayName = displayName;
    }

    public ProfanityLevel next() {
        ProfanityLevel[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
