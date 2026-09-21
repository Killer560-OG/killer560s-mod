package com.killer560.hub.bridge;

import com.killer560.hub.util.ModChat;

/** What one adapter is doing right now - the per-mod status line on the settings tab. */
public enum BridgeStatus {

    OFF("off", ModChat.DIM),
    /** The real mod is installed in this game: it already holds this connection, so we never open a second
     *  one (the protocols cannot rule out a duplicate login kicking the real mod). {@code LocalModBridge}
     *  reads that mod's state instead. */
    SKIPPED_INSTALLED("skipped - installed here, it uses its own connection", ModChat.DIM),
    NOT_ON_HYPIXEL("idle - Hypixel only", ModChat.DIM),
    /** On, on Hypixel, but the grouping this socket needs does not apply yet (no party / not in a run / no
     *  server code / not in P3). The detail says which. */
    WAITING("idle", ModChat.DIM),
    AUTHENTICATING("signing in", ModChat.LIGHT_ORANGE),
    CONNECTING("connecting", ModChat.LIGHT_ORANGE),
    CONNECTED("connected", ModChat.GOOD),
    RETRYING("reconnecting", ModChat.LIGHT_ORANGE),
    /** Repeated authentication failures: stays down for the rest of the session (toggle the mod's setting off
     *  and on to try again). */
    AUTH_FAILED("sign-in failed - gave up for this session", ModChat.BAD);

    public final String label;
    public final int colour;

    BridgeStatus(String label, int colour) {
        this.label = label;
        this.colour = colour;
    }
}
