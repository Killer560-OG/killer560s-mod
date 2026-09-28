package com.killer560.hub.roomsim;

import net.minecraft.client.Minecraft;

/**
 * Whether the player is currently inside the dungeon sim, and which map they are on.
 *
 * <p>Everything the sim adds has to be gated on this and nothing else. The sim reimplements Hypixel abilities -
 * etherwarp, Wither Impact, superboom - by doing things to the player directly, and every one of those is
 * something this mod must never do on Hypixel. One flag, checked by all of them, is what keeps that line
 * somewhere it can be seen rather than spread across a dozen features.
 *
 * <p>It is deliberately not "am I in a singleplayer world": a normal singleplayer world is not the sim, and an
 * ability that fired in one would be a surprise at best.
 */
public final class SimState {

    private static boolean active;
    private static String mapCode = "";

    private SimState() {
    }

    /** True only inside a sim world the mod itself opened. */
    public static boolean isActive() {
        return active;
    }

    /**
     * Whether sim behaviour may act right now.
     *
     * <p>Also requires a local world. If a sim session were somehow still flagged while connected to a real
     * server, every ability below would start writing positions on Hypixel, which is the one outcome that must
     * be impossible rather than unlikely.
     */
    public static boolean canAct(Minecraft client) {
        return active
                && client != null
                && client.player != null
                && client.level != null
                && client.getSingleplayerServer() != null
                && client.getCurrentServer() == null;
    }

    public static void enter(String code) {
        active = true;
        mapCode = code == null ? "" : code;
        // The dungeon gate is set HERE rather than on the world-load path, so it can never disagree with this
        // flag. Secret routes, auto routes, the map and every other clear feature gate on DungeonState, and a
        // sim where they all sit out is a sim he cannot practise in.
        com.killer560.hub.secrets.DungeonState.setRoomSim(true);
    }

    public static void leave() {
        active = false;
        mapCode = "";
        com.killer560.hub.secrets.DungeonState.setRoomSim(false);
    }

    /** The map code this session was built from, for showing and for sharing. */
    public static String mapCode() {
        return mapCode;
    }
}
