package com.killer560.hub.spotify;

public enum ProfanityLevel {
    ALL("Block All Profanity"),
    MINIMAL("Block Minimal"),
    NONE("Block None");

    public final String displayName;

    ProfanityLevel(String displayName) {
        this.displayName = displayName;
    }

    /** The short name the Display Censor row shows (killer560, 2026-11-01: "a all minimal or none"). */
    public String shortName() {
        return switch (this) {
            case ALL -> "All";
            case MINIMAL -> "Minimal (Slurs Only)";
            case NONE -> "None";
        };
    }

    public ProfanityLevel next() {
        ProfanityLevel[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }

    public ProfanityLevel previous() {
        ProfanityLevel[] vals = values();
        return vals[(vals.length + this.ordinal() - 1) % vals.length];
    }
}
