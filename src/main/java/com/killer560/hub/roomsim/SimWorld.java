package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens the singleplayer world the dungeon sim is built in.
 *
 * <p>killer560 wants the sim joined from the Multiplayer screen and "all client side". Those two are only
 * compatible one way: the entry looks like a server, and what it actually opens is a local world this mod owns.
 * Nothing connects anywhere.
 *
 * <p>Flat, not normal. A generated overworld would put terrain through the rooms and cost a lot of generation
 * time for scenery nobody sees - every dungeon room is pasted in at fixed coordinates on the (-185, -185) grid,
 * so the world underneath only has to be empty and quick.
 *
 * <p>The save is reused rather than recreated. Rebuilding it every time would throw away anything placed in the
 * last session, and rooms are pasted over whatever is there anyway.
 */
public final class SimWorld {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    /** The save folder. Distinctive on purpose: this must never collide with one of his own worlds. */
    public static final String LEVEL_ID = "killer560s-dungeon-sim";
    private static final String LEVEL_NAME = "Killer560's Dungeon Sim";

    private SimWorld() {
    }

    /**
     * Hooks the world coming and going.
     *
     * <p>JOIN fires for a singleplayer world too - the client still connects to the integrated server - so it is
     * the right place to learn that the sim world has actually arrived, and DISCONNECT is what guarantees the
     * flag cannot outlive the session however the world ends.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> onWorldLoaded(client));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> onWorldUnloaded());
    }

    /** Whether the sim world has been created at least once. */
    public static boolean exists(Minecraft client) {
        try {
            return client.getLevelSource().levelExists(LEVEL_ID);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Opens the sim world, creating it the first time.
     *
     * <p>{@link SimState#enter} is deliberately NOT called here. The flag is what allows this mod to write
     * positions, and setting it before the world is actually open would leave a window where it is true and the
     * player is still on whatever server they were on. It is set once the world is loaded - see
     * {@link #onWorldLoaded}.
     */
    public static void open(Minecraft client, String mapCode) {
        pendingCode = mapCode == null ? "" : mapCode;
        try {
            if (exists(client)) {
                client.createWorldOpenFlows().openWorld(LEVEL_ID, () -> {
                    pendingCode = null;
                    ModChat.send("Sim", ModChat.text("Could not open the sim world"));
                });
                return;
            }
            LevelSettings settings = new LevelSettings(
                    LEVEL_NAME,
                    GameType.CREATIVE,
                    // No difficulty, no hardcore: the sim is for practising routes, and being killed by a
                    // zombie that wandered in is not the exercise.
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true,
                    WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel(
                    LEVEL_ID,
                    settings,
                    new WorldOptions(0L, false, false),
                    WorldPresets::createFlatWorldDimensions,
                    null);
        } catch (Throwable t) {
            pendingCode = null;
            LOGGER.error("Could not open the sim world", t);
            ModChat.send("Sim", ModChat.text("Could not open the sim world - see the log"));
        }
    }

    /** The code this session is being opened with, held only between the request and the world arriving. */
    private static String pendingCode;

    /**
     * Called once the client is actually in a world.
     *
     * <p>This is the moment the sim is real, so it is the moment the flag goes on. If the world that arrived is
     * not a local one, the flag stays off and the pending code is dropped - a sim session must never be able to
     * begin on a server.
     */
    public static void onWorldLoaded(Minecraft client) {
        if (pendingCode == null) {
            return;
        }
        String code = pendingCode;
        pendingCode = null;
        if (client.getSingleplayerServer() == null || client.getCurrentServer() != null) {
            LOGGER.warn("Sim world request completed on a non-local world - not entering sim mode");
            return;
        }
        SimState.enter(code);
        SimAbilities.reset();
        // Score starts blank for every session. Totals come from the map once it is built; until then they are
        // zero, which reads as "unknown" rather than as a perfect run.
        SimScore.reset(0, 0);
        SimMimic.reset();
        SimTerminator.reset();
        SimArchitect.reset();
        // The saved hotbar goes back every time a dungeon opens - muscle memory for a route is partly muscle
        // memory for which slot things are in.
        SimLoadout.onSimEntered(client);
        // The last starred mob in a wither-door room drops the key. Wiring it here keeps the two features
        // ignorant of each other: mobs know when the last star died, doors know what a key is, and neither
        // needs to import the other.
        SimMobs.setOnLastStarredDeath(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                SimDoors.dropKeyAt(mc, mc.player.position());
            }
        });
        ModChat.send("Sim", ModChat.text("Dungeon sim ready. "),
                ModChat.dim("/simitem all for the toolkit."));
    }

    /** Called when the player leaves a world, so the flag can never outlive the session. */
    public static void onWorldUnloaded() {
        pendingCode = null;
        if (SimState.isActive()) {
            SimState.leave();
            SimAbilities.reset();
            // Doors belong to the map that was open. Leaving them registered would have the next session's
            // key-click open a door that is no longer there.
            SimDoors.clear();
            SimMobs.clear(Minecraft.getInstance());
            SimRun.reset();
            com.killer560.hub.roomsim.puzzles.SimPuzzles.resetAll();
        }
    }
}
