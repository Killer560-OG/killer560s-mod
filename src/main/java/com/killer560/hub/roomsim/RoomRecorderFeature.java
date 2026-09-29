package com.killer560.hub.roomsim;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;

import com.killer560.hub.util.KeyUtil;

import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Random;

/**
 * Fills the room library by running F7 over and over and reading whatever loads.
 *
 * <p>killer560's loop, 2026-09-28: "Every 30s it'll do /f7 and open the f7 run. It gets 25s to scan what it can
 * see then it does /dh. Once it gets to the dh it waits for 7s then does /f7 again. If it gets sent to limbo
 * have it wait 65s then do /skyblock and rejoin then do /f7 after 5s."
 *
 * <p><b>DEV BUILDS ONLY.</b> Gated on {@link BuildVariant#DEV_TOOLS}, which is compiled out of a release, so this
 * cannot reach anyone else's game. It is a tool for building the library that ships, not a feature.
 *
 * <p><b>It stops on any key.</b> Touch the keyboard and it is off, with a message saying so - not paused, off.
 * This drives real dungeon entry on a real server for hours unattended, and the one thing that must always work
 * is the ability to take it back instantly without remembering which key is the right one.
 *
 * <p>Timings carry a few seconds of jitter rather than firing on an exact metronome. A fixed 30.000s period
 * repeated for hours is not something a person produces, and the whole point of this loop is to look like
 * somebody grinding rooms.
 */
public final class RoomRecorderFeature {

    private static final Random JITTER = new Random();

    /** Fractional wobble applied to every wait. Enough to break the metronome, not enough to break the loop. */
    private static final double VARIANCE = 0.12;

    /** Most extra time added on top of a server swap's one-second floor. */
    private static final double SWAP_EXTRA_MAX_SECONDS = 1.5;

    private enum Stage {
        /** Not running. */
        OFF,
        /** Waiting to send the joininstance for F7. */
        ENTER,
        /** Inside the run, reading rooms. */
        SCAN,
        /** Sent /dh, waiting to arrive. */
        LEAVING,
        /** In the hub, waiting before the next floor join. */
        HUB,
        /** Dropped to limbo: the long wait before /skyblock. */
        LIMBO,
        /** Sent /skyblock, waiting before the floor join. */
        REJOIN,
        /** Five-puzzle run: handed over to him, still capturing whatever he walks into. */
        PAUSED
    }

    private static Stage stage = Stage.OFF;
    private static int waitTicks;
    private static int roomsAddedThisRun;
    private static int runs;

    private RoomRecorderFeature() {
    }

    public static void register() {
        if (!BuildVariant.DEV_TOOLS) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("RoomRecorderFeature.tick", RoomRecorderFeature::tick));
    }

    public static boolean isRunning() {
        return stage != Stage.OFF;
    }

    /**
     * Only arms in the instance made for it.
     *
     * <p>DEV_TOOLS is true in every non-release build, so without this the command exists in his Dungeons
     * instance too - the one he actually plays. This drives real dungeon entry on a loop, and starting it by
     * accident in the wrong instance is the expensive mistake here, so it checks the game directory it is
     * running in rather than trusting the command not to be typed.
     */
    public static boolean inRecorderInstance() {
        try {
            String dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .toAbsolutePath().toString().toLowerCase(Locale.ROOT);
            return dir.contains("map logger");
        } catch (Exception e) {
            return false;
        }
    }

    public static void start() {
        if (!BuildVariant.DEV_TOOLS) {
            return;
        }
        if (!inRecorderInstance()) {
            say("Room Recorder only runs in the Map Logger instance - it enters dungeons on a loop and this is "
                    + "not the instance for that.");
            return;
        }
        RoomLibrary.load();
        stage = Stage.ENTER;
        waitTicks = seconds(2);
        runs = 0;
        // killer560 (2026-09-28): "if i turn it on it auto turns off". This is why, and it made the feature
        // impossible to start from the command: the RETURN key that submits "/killer560 roomrecorder" is still
        // physically down on the next tick, the any-key stop sees it and switches straight back off. The resume
        // key path already armed this guard; start() never did. Cleared once the keyboard is clear, so a key
        // held at the moment of starting never counts as taking the controls back - only a fresh press does.
        suppressKeyStop = true;
        say("Room Recorder ON. It will run F7 on a loop. Press any key to stop.");
    }

    private static int alertTicks;
    private static boolean resumeKeyWasDown;
    /** Quarter of a second, his figure. */
    private static final int TOGGLE_DEBOUNCE_TICKS = 5;
    private static int lastToggleTick = -1000;
    /** Ticks since the client started - only ever compared against itself, for the toggle debounce. */
    private static int tickCounter;
    private static boolean suppressKeyStop;

    /**
     * The rebindable key. One key, three states: running pauses, paused resumes, off starts.
     *
     * <p>killer560 (2026-09-28): "The key bind should pause and unpause it as one key bind along with the auto
     * pause on puzzles." So a manual pause is the same state the five-puzzle detection puts it in - it keeps
     * capturing whatever he walks into, and the any-key stop stays disabled, because a pause he asked for is a
     * pause he intends to be playing through.
     *
     * <p>Edge-triggered so holding it does not fire repeatedly, and it always sets {@link #suppressKeyStop}:
     * the key is by definition still down on the next tick, and the any-key stop would otherwise instantly
     * undo whatever it just did.
     */
    private static void pollResumeKey(Minecraft client) {
        int code = RoomRecorderConfig.getInstance().getResumeKeyCode();
        boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
        boolean pressed = down && !resumeKeyWasDown;
        resumeKeyWasDown = down;
        if (!pressed || client.screen != null) {
            return; // not while a screen is open: he is typing, not commanding
        }
        // A quarter second between toggles, on his request, so a stutter on the key cannot pause and unpause
        // in the same breath - which would look exactly like the key not working.
        if (tickCounter - lastToggleTick < TOGGLE_DEBOUNCE_TICKS) {
            return;
        }
        lastToggleTick = tickCounter;
        suppressKeyStop = true;
        switch (stage) {
            case OFF -> start();
            case PAUSED -> resume();
            default -> pause("key");
        }
    }

    /**
     * Holds the loop where it is, still capturing.
     *
     * <p>Same state the five-puzzle detection uses. It does not send /dh or /f7 while paused, so the run he is
     * standing in stays open for as long as he wants to walk it.
     */
    public static void pause(String reason) {
        if (stage == Stage.OFF || stage == Stage.PAUSED) {
            return;
        }
        stage = Stage.PAUSED;
        alertTicks = 0;
        RoomLibrary.saveAll();
        say("paused (" + reason + ") - still capturing what you walk into. Press the key again to carry on.");
    }


    /** Leaves a five-puzzle pause and carries on with the loop. */
    public static void resume() {
        if (stage != Stage.PAUSED) {
            return;
        }
        RoomLibrary.saveAll();
        stage = Stage.SCAN;
        waitTicks = 1;
        // Same reason as start(): "/killer560 roomrecorder resume" is submitted with Return, and coming back
        // from a pause straight into the any-key stop would look exactly like the resume having done nothing.
        suppressKeyStop = true;
        say("resumed");
    }

    public static boolean isPaused() {
        return stage == Stage.PAUSED;
    }

    /** Chat plus a sound, because he will not be looking at chat while walking a run. */
    private static void alert(Minecraft client, String message) {
        say(message);
        try {
            if (client != null && client.getSoundManager() != null) {
                client.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f));
                client.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
            }
        } catch (Throwable ignored) {
            // a missing sound must never stop the recorder
        }
    }

    public static void stop(String reason) {
        if (stage == Stage.OFF) {
            return;
        }
        stage = Stage.OFF;
        RoomLibrary.saveAll();
        say("Room Recorder OFF (" + reason + "). " + RoomLibrary.completeCount() + " of "
                + RoomLibrary.roomCount() + " rooms complete.");
    }

    private static void tick(Minecraft client) {
        if (client == null) {
            return;
        }
        tickCounter++;
        pollResumeKey(client);
        if (stage == Stage.OFF) {
            return;
        }
        // Tracked in EVERY stage, paused included: the instance cooldown keeps running while he walks a
        // five-puzzle run by hand, and resuming into a stale clock would fire /f7 straight into a refusal.
        DungeonInstanceCooldown.tick(client);
        // The key that started or resumed it is still held on the next tick, and the any-key stop would
        // instantly undo it. Wait for a clean keyboard before arming that again.
        if (client.screen != null) {
            // A screen is open, so every key belongs to it, not to the world. This is the other half of the
            // "if i turn it on it auto turns off" bug (killer560, 2026-09-28): Return submits the command that
            // starts it, and Escape closes the settings tab that starts it, and both were being read as him
            // taking the controls back. Re-armed rather than merely skipped, so the tick after the screen
            // closes still waits for a clean keyboard instead of seeing the key that closed it.
            suppressKeyStop = true;
        } else if (suppressKeyStop) {
            if (!anyKeyDown(client)) {
                suppressKeyStop = false;
            }
        } else if (stage != Stage.PAUSED && firstKeyDown(client) != -1) {
            // Not while paused: the pause exists so he can walk the run himself, and stopping the moment he
            // touches a movement key would make the feature impossible to use for the thing it is for.
            stop(KeyUtil.bindDisplayName(firstKeyDown(client)) + " pressed");
            return;
        }
        if (client.player == null || client.level == null) {
            // Between worlds: that is normal here, it is changing servers constantly.
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            if (stage == Stage.SCAN || stage == Stage.PAUSED) {
                // Paused included: he is walking the run to load rooms, and that is exactly when capturing
                // every tick matters most.
                scan(client);
            }
            return;
        }
        switch (stage) {
            case ENTER, REJOIN -> {
                // Hypixel allows a new instance every 30s. Waiting for the real cooldown rather than counting
                // to 30 ourselves means it never fires early into a refusal, and never sits idle after one.
                long cd = DungeonInstanceCooldown.instanceCooldownRemainingMs();
                if (cd > 0) {
                    waitTicks = (int) Math.max(1, cd / 50);
                    return;
                }
                ServerCommands.toServer(joinFloorCommand());
                roomsAddedThisRun = 0;
                stage = Stage.SCAN;
                waitTicks = seconds(25);
            }
            case SCAN -> {
                ServerCommands.toServer("dh");
                runs++;
                RoomLibrary.saveAll();
                say(String.format(Locale.US, "run %d: %d new column(s), %d/%d rooms complete",
                        runs, roomsAddedThisRun, RoomLibrary.completeCount(), RoomLibrary.roomCount()));
                stage = Stage.LEAVING;
                waitTicks = swapDelay(7);
            }
            case LEAVING, HUB -> {
                stage = Stage.ENTER;
                waitTicks = swapDelay(1);
            }
            case PAUSED -> {
                scan(client);
                // Keep reminding, quietly spaced: a single alert is easy to miss and this run is the one
                // worth not missing.
                alertTicks++;
                if (alertTicks % 200 == 0) {
                    alert(client, "still paused - /killer560 roomrecorder resume when you are done");
                }
                waitTicks = 20;
            }
            case LIMBO -> {
                // Back when the client is somewhere real again, not when a number says so.
                if (!DungeonInstanceCooldown.inPlayableWorld(client)
                        || DungeonInstanceCooldown.looksLikeLimbo(client)) {
                    waitTicks = seconds(2);
                    return;
                }
                ServerCommands.toServer("skyblock");
                stage = Stage.REJOIN;
                waitTicks = swapDelay(5);
            }
            default -> { }
        }
    }

    /**
     * Reads whatever of the current room is loaded.
     *
     * <p>Every tick of the scan window rather than once at the end, because the rooms that load are the ones
     * being walked past - waiting until the end would only ever capture wherever it happened to stop.
     */
    private static void scan(Minecraft client) {
        if (!DungeonState.isInDungeon()) {
            // Not in a run when we expected to be: most often limbo, which has its own long recovery.
            if (DungeonInstanceCooldown.looksLikeLimbo(client)) {
                stage = Stage.LIMBO;
                // 65s is his figure and stays as the floor; the stage itself then waits for a real world
                // rather than assuming 65 was long enough.
                waitTicks = seconds(65);
                say("limbo - waiting, then rejoining when the world is back");
            }
            return;
        }
        if (stage == Stage.SCAN && RoomRecorderConfig.getInstance().isPauseOnFivePuzzles()
                && com.killer560.hub.runsummary.RunSummaryFeature.puzzleCount() == 5) {
            stage = Stage.PAUSED;
            alertTicks = 0;
            alert(client, "FIVE PUZZLE RUN - paused. Walk it yourself to load rare rooms, then "
                    + "/killer560 roomrecorder resume");
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout == null) {
            return;
        }
        int room = layout.roomAtWorld(client.player.getX(), client.player.getZ());
        if (room < 0) {
            return;
        }
        roomsAddedThisRun += RoomLibrary.capture(client.level, layout, room);
        captureMobSpawns(client, layout, room);
        // Measures what the sim is currently guessing at - the wither door's real size and shape. Costs nothing
        // when there is no unlogged door loaded, and never touches the room library.
        SimMeasure.scanDoors(client);
    }

    /**
     * Records where starred mobs are standing, in room-local coordinates.
     *
     * <p>Hypixel puts the star on a separate invisible armour stand rather than on the mob itself, which is the
     * same thing Mob ESP already relies on - so these are the stands, and the stand is where the mob is. Without
     * this a rebuilt room is scenery: the geometry is right and nothing lives in it.
     */
    private static void captureMobSpawns(Minecraft client, DungeonLayout layout, int room) {
        String name = layout.name(room);
        if (name == null || name.isBlank()) {
            return;
        }
        int[] tiles = layout.tiles(room);
        if (tiles == null || tiles.length == 0) {
            return;
        }
        int minGx = Integer.MAX_VALUE;
        int minGz = Integer.MAX_VALUE;
        for (int idx : tiles) {
            minGx = Math.min(minGx, idx % DungeonLayout.GRID);
            minGz = Math.min(minGz, idx / DungeonLayout.GRID);
        }
        var origin = DungeonLayout.cellCenter(minGz * DungeonLayout.GRID + minGx);
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2;

        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) {
                continue;
            }
            var custom = stand.getCustomName();
            if (custom == null || !custom.getString().contains(STAR)) {
                continue;
            }
            int lx = (int) Math.floor(stand.getX()) - worldX0;
            int ly = (int) Math.floor(stand.getY());
            int lz = (int) Math.floor(stand.getZ()) - worldZ0;
            if (lx < 0 || lz < 0 || ly < RoomLibrary.MIN_Y || ly > RoomLibrary.MAX_Y) {
                continue; // a stand belonging to a neighbouring room, or out of the slice we keep
            }
            RoomLibrary.recordMobSpawn(name, lx, ly, lz, "STARRED");
        }
    }

    /** Hypixel's starred-mob marker, the same character Mob ESP matches on. */
    private static final String STAR = "✯";

    /**
     * Whether the user has touched the keyboard.
     *
     * <p>Polled across the whole keyboard rather than hooking one key, because the promise is "any key" and a
     * list of keys is a list someone will press something missing from. Modifier keys are included: holding
     * shift to sprint is still a person taking the controls back.
     */
    private static boolean anyKeyDown(Minecraft client) {
        return firstKeyDown(client) != -1;
    }

    /**
     * The first key found held, or -1.
     *
     * <p>Returns WHICH key rather than a yes/no so the stop message can name it. "Room Recorder OFF (key
     * pressed)" is a report; "OFF (W pressed)" is a diagnosis, and the difference between him having walked and
     * the feature having stopped itself was guesswork without it.
     */
    private static int firstKeyDown(Minecraft client) {
        var window = client.getWindow();
        if (window == null) {
            return -1;
        }
        // KeyUtil rather than raw GLFW: glfwGetKey rejects codes outside its keyboard range, and sweeping the
        // whole range is exactly how you hand it an invalid one.
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (KeyUtil.isKeyDown(window, key)) {
                return key;
            }
        }
        return -1;
    }

    /**
     * The command that actually opens a floor.
     *
     * <p>killer560 (2026-09-28): "it cannot just do /f7 it needs to do the joininstance one." Exactly right, and
     * the bug was mine: {@code /f7} is THIS MOD'S OWN client-side shortcut (see
     * {@link com.killer560.hub.commandshortcuts.CommandShortcutsFeature}), not a Hypixel command.
     * {@code ServerCommands.toServer} sends below the client dispatcher on purpose - that is how it avoids the
     * recursion that forwarding a self-registered name causes - so it put the literal text "/f7" in front of a
     * server that has never heard of it, and the loop sat waiting for a dungeon that was never queued.
     *
     * <p>Read off {@code Shortcut.F7} rather than written out again, so the id has ONE definition. That enum is
     * also where the provenance lives: it was taken from this mod's shipped {@code /joininstance} usage and
     * cross-checked against NoammAddons, not guessed.
     */
    private static String joinFloorCommand() {
        return "joininstance "
                + com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Shortcut.F7.instanceId;
    }

    /** Ticks for a wait, with a few per cent of jitter so the loop is not a metronome. */
    private static int seconds(double s) {
        double wobble = 1.0 + (JITTER.nextDouble() * 2.0 - 1.0) * VARIANCE;
        return Math.max(1, (int) Math.round(s * 20.0 * wobble));
    }

    /**
     * Ticks before a command that changes server, on killer560's rule (2026-09-28): "a minimum of 1s delay with
     * a random amount added after as well".
     *
     * <p>A floor plus an addition rather than a percentage of the wait, because the risk being managed is not
     * the length of the pause but how tightly a swap follows whatever came before it. A percentage of a short
     * wait is still a short wait; a floor is a floor.
     */
    private static int swapDelay(double baseSeconds) {
        double base = Math.max(1.0, baseSeconds);
        return Math.max(20, (int) Math.round((base + JITTER.nextDouble() * SWAP_EXTRA_MAX_SECONDS) * 20.0));
    }

    private static void say(String message) {
        try {
            ModChat.send("Room Recorder", ModChat.text(message));
        } catch (Throwable ignored) {
            // no player yet
        }
    }
}
