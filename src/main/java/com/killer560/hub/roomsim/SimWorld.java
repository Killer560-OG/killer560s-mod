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
        // Keeps the sim's own loading screen up for as long as the build is running.
        //
        // Showing it once is not enough and that is why killer560 never saw one: opening a world REPLACES the
        // screen with vanilla's own progress and receiving-level screens, and then clears it to null when the
        // level arrives. Re-asserting it every tick while a build is outstanding is the only thing that
        // survives that, and it costs a null check on the ticks when nothing is building.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!buildInProgress) {
                return;
            }
            // Only once the level is actually here. While it is still loading, vanilla owns the screen -
            // fighting it for that every tick means replacing its receiving-level screen over and over, which
            // is its own way to hang the client. Vanilla covers the world load; this covers the build after it.
            if (client.level != null && !(client.screen instanceof SimLoadingScreen)) {
                loadingScreen = SimLoadingScreen.show(client, loadingLabel);
            }
            if (++loadingTicks > LOADING_WATCHDOG_TICKS) {
                LOGGER.warn("Sim build never reported finishing after {} ticks - releasing the loading screen",
                        loadingTicks);
                buildFinished(client, null);
                ModChat.send("Sim", ModChat.dim("The build did not report finishing - letting you in anyway. "
                        + "If the map looks wrong, the log has the detail."));
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> onWorldUnloaded());
    }

    /**
     * A world made of nothing at all.
     *
     * <p>killer560 (2026-09-28): "For the sim world make it so it is literally pure void with only the room i
     * want in it." It was a superflat, so every direction outside the room was grass and stone to the horizon -
     * and a room standing in a field does not read like a dungeon. Clearing a margin around it helped and could
     * never finish the job, because the ground goes on forever and wiping it is unbounded work.
     *
     * <p>Generating nothing is the version that costs nothing: a flat generator with an EMPTY layer list and
     * the void biome. The room is then the only thing in the world, which is exactly what he asked for, and it
     * also makes the earlier clearing pass almost free - there is nothing left to clear.
     *
     * <p>Built by replacing the overworld generator on the flat preset rather than assembling a WorldDimensions
     * by hand, so the nether and end stems stay whatever vanilla says they should be. Nothing goes there, but
     * a world missing a dimension is the kind of thing that breaks much later and somewhere else.
     */
    private static net.minecraft.world.level.levelgen.WorldDimensions voidWorldDimensions(
            net.minecraft.core.HolderLookup.Provider registries) {
        var biomes = registries.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var structures = registries.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET);
        var features = registries.lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE);
        var settings = net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings
                .getDefault(biomes, structures, features)
                .withBiomeAndLayers(java.util.List.of(), java.util.Optional.empty(),
                        biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.THE_VOID));
        return WorldPresets.createFlatWorldDimensions(registries).replaceOverworldGenerator(
                registries, new net.minecraft.world.level.levelgen.FlatLevelSource(settings));
    }

    /**
     * Throws the sim world away so the next open regenerates it.
     *
     * <p>A world's generator is fixed when it is created, so the void setting above does nothing to the
     * superflat one already on disk. This is safe in a way deleting a world usually is not: everything in the
     * sim world is rebuilt from the room library on demand, so there is nothing in it that is not also
     * somewhere else. The room library itself lives in the config folder and is not touched.
     *
     * @return whether a world was actually removed
     */
    public static boolean deleteWorld(Minecraft client) {
        try {
            if (!exists(client)) {
                return false;
            }
            try (var access = client.getLevelSource().createAccess(LEVEL_ID)) {
                access.deleteLevel();
            }
            LOGGER.info("Sim world deleted - it will be recreated as void on the next open");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not delete the sim world", e);
            return false;
        }
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
        open(client, mapCode, null, null);
    }

    /**
     * Opens the sim world and builds something into it before handing it over.
     *
     * <p>killer560 (2026-09-28): "Make sure the room loads before I am actually put into the workd." Callers
     * used to have to be in a world already, and the one that was not - picking a room off the main menu -
     * opened an empty sim and asked him to run a command to finish the job. Now the work is queued here and run
     * the moment the world exists, behind {@link SimLoadingScreen}, so what he walks into is already built.
     *
     * @param build what to build, handed the client once the world is real; may be null for an empty sim
     * @param label what to show on the loading screen while it happens
     */
    public static void open(Minecraft client, String mapCode, java.util.function.Consumer<Minecraft> build,
                            String label) {
        // The sim world is generated, so rebuilding it costs nothing but the seconds it takes - and a world
        // made before the void change is still a superflat, which is the whole complaint. Done once, tracked
        // by a flag next to the other sim settings rather than by guessing at the world's generator.
        if (!SimWorldVersion.isVoidWorld()) {
            deleteWorld(client);
            SimWorldVersion.markVoidWorld();
        }
        pendingCode = mapCode == null ? "" : mapCode;
        pendingBuild = build;
        loadingLabel = label == null ? "Opening the sim" : label;
        // Held from here until the builder reports done. The tick hook above is what actually keeps it on
        // screen through the world load; this call just gets it up for the frames before that starts.
        buildInProgress = true;
        loadingTicks = 0;
        loadingScreen = SimLoadingScreen.show(client, loadingLabel);
        try {
            if (exists(client)) {
                client.createWorldOpenFlows().openWorld(LEVEL_ID, () -> {
                    pendingCode = null;
                    pendingBuild = null;
                    buildFinished(client, null);
                    ModChat.send("Sim", ModChat.text("Could not open the sim world"));
                });
                return;
            }
            LevelSettings settings = new LevelSettings(
                    LEVEL_NAME,
                    // killer560 (2026-09-28): "make my gamemode survival not creative." Survival is also the
                    // mode the thing being practised happens in - creative flight would let a route cheat past
                    // exactly the jumps and drops it exists to rehearse.
                    GameType.SURVIVAL,
                    // No difficulty, no hardcore: the sim is for practising routes, and being killed by a
                    // zombie that wandered in is not the exercise.
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true,
                    WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel(
                    LEVEL_ID,
                    settings,
                    new WorldOptions(0L, false, false),
                    SimWorld::voidWorldDimensions,
                    null);
        } catch (Throwable t) {
            pendingCode = null;
            pendingBuild = null;
            buildFinished(client, null);
            LOGGER.error("Could not open the sim world", t);
            ModChat.send("Sim", ModChat.text("Could not open the sim world - see the log"));
        }
    }

    /** The code this session is being opened with, held only between the request and the world arriving. */
    private static String pendingCode;

    /** What to build once the world is real, or null for an empty sim. */
    private static java.util.function.Consumer<Minecraft> pendingBuild;

    /** The screen covering the gap between the world arriving and the map being built. */
    private static SimLoadingScreen loadingScreen;

    /** True from the moment a build is requested until the builder says it has finished. */
    private static volatile boolean buildInProgress;

    /** What that screen says while it is up. */
    private static String loadingLabel = "";

    /** Client ticks the loading screen has been up for, for the watchdog below. */
    private static int loadingTicks;

    /**
     * Longest the loading screen may stay up before it is taken down regardless.
     *
     * <p>Sixty seconds, and it exists because the contract "every builder remembers to report completion" is one
     * a builder WILL eventually break - {@code buildFlatTest} broke it immediately, and the symptom was being
     * stuck on the loading screen with no way off it, since it deliberately ignores Escape. A screen that
     * outstays a slow build by a few seconds is a much smaller failure than one that never leaves, so the
     * watchdog is generous rather than tight: it is there to bound the worst case, not to time the build.
     */
    private static final int LOADING_WATCHDOG_TICKS = 20 * 60;

    /**
     * Called by whatever finished building, to take the loading screen down.
     *
     * <p>The builders report completion rather than this class guessing at it: the paste runs on the server
     * thread and only the thing doing it knows when the last block landed. A timer here would have to be longer
     * than the biggest room to be safe, and would then make every small room feel broken.
     */
    public static void buildFinished(Minecraft client, String summary) {
        buildInProgress = false;
        loadingTicks = 0;
        loadingScreen = null;
        SimLoadingScreen.dismiss(client);
    }

    /** Progress line for the loading screen, if one is up. */
    public static void buildProgress(String line) {
        loadingLabel = line == null ? loadingLabel : line;
        if (loadingScreen != null) {
            loadingScreen.progress(line);
        }
    }

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
        java.util.function.Consumer<Minecraft> build = pendingBuild;
        pendingBuild = null;
        if (client.getSingleplayerServer() == null || client.getCurrentServer() != null) {
            LOGGER.warn("Sim world request completed on a non-local world - not entering sim mode");
            buildFinished(client, null);
            return;
        }
        SimState.enter(code);
        // Forced every time, not just at creation: the sim world was made in creative before he asked for
        // survival, and a level that already exists keeps the mode it was made with.
        var server = client.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                for (var sp : server.getPlayerList().getPlayers()) {
                    sp.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                }
            });
        }
        SimAbilities.reset();
        // Score starts blank for every session. Totals come from the map once it is built; until then they are
        // zero, which reads as "unknown" rather than as a perfect run.
        SimScore.reset(0, 0);
        SimMimic.reset();
        SimTerminator.reset();
        SimArchitect.reset();
        SimBreakerState.reset();
        SimSurvival.reset();
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
        if (build != null) {
            // The world is real and the sim flag is on, so the builder can do its work. It takes the loading
            // screen down itself when the last block lands.
            build.accept(client);
        } else {
            buildFinished(client, null);
        }
        ModChat.send("Sim", ModChat.text("Dungeon sim ready. "),
                ModChat.dim("/simitem for the toolkit."));
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
