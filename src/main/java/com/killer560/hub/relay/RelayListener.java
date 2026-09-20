package com.killer560.hub.relay;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * Callbacks from {@link RelayClient}. <b>Every one of these runs on the relay's own worker thread, never on the
 * render or client thread</b> - hop to {@code Minecraft.getInstance().execute(...)} before touching anything in
 * the game. All methods default to doing nothing so a feature only implements the packets it cares about.
 */
public interface RelayListener {

    /** A chat line from the room. {@code from} is stamped by the relay from the sender's verified token. */
    default void onChat(String from, String message) {
    }

    /** {@code event} is "join" or "leave"; {@code online} is the room's full current member list. */
    default void onPresence(String event, String name, List<String> online) {
    }

    /** Opaque per-run data another member shared (mimic found, dragon spawns...). Not used by Mod Chat. */
    default void onData(String from, String key, JsonElement value) {
    }

    /** The socket came up. {@code online} is who else was already in the room. */
    default void onConnected(String room, List<String> online) {
    }

    /** The socket went away, or never came up. {@code reason} is short and human-readable. */
    default void onDisconnected(String reason) {
    }
}
