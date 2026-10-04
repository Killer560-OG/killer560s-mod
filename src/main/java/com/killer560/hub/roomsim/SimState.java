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

    /**
     * May a sim world be opened right now?
     *
     * <p>Deliberately NOT {@link #canAct}, which requires a singleplayer server to already exist - useless to
     * anything whose job is to create one, and to anything running from the main menu. The only question here
     * is whether he is attached to somebody else's server, and if he is the answer is no.
     */
    public static boolean canOpen(Minecraft client) {
        return client != null && client.getCurrentServer() == null;
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
        generatedFloor = false;
        SimWitherDoors.clear();
        com.killer560.hub.livemap.autoclear.LevelEtherGrid.dropMirror();
        active = false;
        mapCode = "";
        com.killer560.hub.secrets.DungeonState.setRoomSim(false);
    }

    /** The map code this session was built from, for showing and for sharing. */
    /** The floor the current map was generated as, for the sidebar. Defaults to F7, the one he practises. */
    private static String floorLabel = "F7";

    /**
     * Whether what is loaded is a whole generated floor rather than a single room.
     *
     * <p>The two are different things to practise in and the Dungeon Breaker treats them differently -
     * killer560 (2026-09-30): "before the countdown make it so breaker doesnt work on the generated map. If i
     * only choose one room thought then the breaker should work." A single room is a sandbox; a floor is a
     * clear, and letting him cut through its walls before the timer starts would defeat the point of it.
     */
    private static boolean generatedFloor;

    public static boolean isGeneratedFloor() {
        return generatedFloor;
    }

    public static void setGeneratedFloor(boolean value) {
        generatedFloor = value;
    }

    public static String floorLabel() {
        return floorLabel;
    }

    public static void setFloorLabel(String label) {
        floorLabel = label == null || label.isBlank() ? "F7" : label;
    }

    /**
     * The room the player is standing in, worked out from the sim's OWN map code.
     *
     * <p>Not from the Live Map, deliberately. The Live Map only scans while {@code DungeonState} believes it is
     * in a dungeon, and DungeonState decides that by reading the sidebar this feeds - so asking the Live Map
     * here would be a circle that never starts. The sim already knows exactly what it built.
     *
     * @return the room name, or null when outside every room or before a map exists
     */
    public static String currentRoomName() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return null;
        }
        return roomNameAt(client.player.getBlockX(), client.player.getBlockZ());
    }

    /**
     * The room at this block column, worked out the same way as {@link #currentRoomName}.
     *
     * <p>For the sim's SERVER side ({@code SimAbilities}, {@code SimItems}): a rule like "no abilities while
     * standing in a trap room" is checked against where the server has him when the packet arrives, as Hypixel
     * checks it - not against the client's copy of him.
     */
    public static String roomNameAt(int blockX, int blockZ) {
        String code = mapCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        MapCode.Decoded decoded = MapCode.decode(code);
        if (decoded == null) {
            return null;
        }
        // The grid's own origin and spacing, read from DungeonLayout rather than from LiveMapFeature's
        // package-private constants - one public accessor beats widening two fields.
        var origin = com.killer560.hub.livemap.DungeonLayout.cellCenter(0);
        int step = com.killer560.hub.livemap.DungeonLayout.cellCenter(1).getX() - origin.getX();
        if (step == 0) {
            return null;
        }
        int gx = Math.round((blockX - origin.getX()) / (float) step);
        int gz = Math.round((blockZ - origin.getZ()) / (float) step);
        int grid = com.killer560.hub.livemap.DungeonLayout.GRID;
        if (gx < 0 || gz < 0 || gx >= grid || gz >= grid) {
            return null;
        }
        int id = decoded.cellRoom()[gz * grid + gx];
        if (id < 0 || id >= decoded.nameTable().length) {
            return null;
        }
        return decoded.nameTable()[id];
    }

    public static String mapCode() {
        return mapCode;
    }
}
