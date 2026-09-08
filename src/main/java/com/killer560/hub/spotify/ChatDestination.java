package com.killer560.hub.spotify;

public enum ChatDestination {
    PARTY("/pc ", "Party Chat"),
    ALL("/ac ", "All Chat"),
    GUILD("/gc ", "Guild Chat"),
    COOP("", "Co-op / Plain");

    public final String prefix;
    public final String displayName;

    ChatDestination(String prefix, String displayName) {
        this.prefix = prefix;
        this.displayName = displayName;
    }

    public ChatDestination next() {
        ChatDestination[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
