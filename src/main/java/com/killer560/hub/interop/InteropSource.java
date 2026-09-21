package com.killer560.hub.interop;

/**
 * Where a piece of party knowledge came from, ordered by how much we trust it.
 * <p>
 * killer560 (2026-09-20): "The issue with reading their mods based of me having them assumes someone else using
 * my mod also has them. Which I don't want them to have to do." So {@link #SELF} is always the goal - anything
 * this mod can work out on its own from what Hypixel already sends every client is derived here, and the other
 * sources only ever fill gaps a lone client genuinely cannot see (mainly: what a teammate found in a room you
 * were never in).
 * <p>
 * Trust order, highest first:
 * <ol>
 * <li>{@link #SELF} - we saw it ourselves (entity, block, map item, tab list, server chat line).
 * <li>{@link #RELAY} - another killer560s-mod user sent it over our own relay as a structured field.
 * <li>{@link #BRIDGE} - read out of another mod's own state in this JVM (only if that mod is installed here).
 * <li>{@link #SOCKET} - received over another mod's own party websocket (Devonian / NoammAddons / Odin) by
 * {@code com.killer560.hub.bridge}. Below {@link #BRIDGE}: that is the real mod's own merged view (its scan plus
 * its own socket), while this is a single relayed claim - NoammAddons' server does not say which player sent a
 * message, and Odin's socket has no authentication at all. Above {@link #CHAT}: a structured field over an
 * authenticated connection (Devonian, NoammAddons) cannot be produced by a human typing a chat line.
 * <li>{@link #CHAT} - parsed out of a party-chat line another mod announced. Weakest: a human can type the
 * same words by hand, and the formats belong to someone else and can change on their next update.
 * </ol>
 */
public enum InteropSource {

    CHAT(0, "party chat"),
    SOCKET(1, "mod socket"),
    BRIDGE(2, "local mod"),
    RELAY(3, "mod relay"),
    SELF(4, "this client");

    private final int rank;
    private final String label;

    InteropSource(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    /** Higher wins when two sources disagree about the same fact. */
    public int rank() {
        return rank;
    }

    /** Short human-readable name for the Interop tab's status lines. */
    public String label() {
        return label;
    }
}
