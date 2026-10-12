package com.killer560.hub.spotify;

public enum ChatDestination {
    PARTY("/pc ", "Party Chat"),
    ALL("/ac ", "All Chat"),
    GUILD("/gc ", "Guild Chat"),
    COOP("", "Co-op / Plain"),
    /** The mod's own Mod Chat (relay), never Hypixel: senders route it to {@code ModChatChannel.sendLine}. */
    MOD_CHAT("/kc ", "Mod Chat");

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

    public ChatDestination previous() {
        ChatDestination[] vals = values();
        return vals[(vals.length + this.ordinal() - 1) % vals.length];
    }
}
