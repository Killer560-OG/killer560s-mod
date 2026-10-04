package com.killer560.hub.roomsim;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;

import com.killer560.hub.util.KeyUtil;

import org.lwjgl.glfw.GLFW;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ActionGate;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Random;
import com.killer560.hub.compat.McCompat;

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
        /**
         * Sent the join, waiting on Hypixel's "Undersized party!" confirm menu.
         *
         * <p>killer560 (2026-09-28): "it does need to click this after running the command." Joining F7 solo
         * does not drop you into the floor - Hypixel opens a menu warning that the instance wants five players,
         * and nothing happens until something clicks "Click to play anyway!". Without this the loop queued a
         * dungeon it never entered.
         */
        CONFIRM,
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
        PAUSED,
        /**
         * Capture only: read whatever dungeon he is standing in and never touch the controls.
         *
         * <p>Added 2026-09-28, when Caleb gave him access to Ashfall and its dungeon maker turned out to build a
         * real Catacombs floor in a local world. This mod already reads that world correctly with no changes at
         * all - the crash log from it shows floor detection landing on M7 and the Live Map identifying all
         * twenty rooms with their rotations and clay corners - so the rooms can be captured straight out of it.
         *
         * <p>None of the Hypixel loop applies there: there is no instance to join, no undersized-party menu, no
         * hub to return to and no cooldown. So this stage sends NOTHING. He places rooms and walks; it reads.
         * That also makes it the safe way to fill the library, because nothing is automated on a real server.
         */
        CAPTURE
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
        RoomLibrary.loadAsync();
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

    /**
     * Starts capture-only mode: scan, never drive.
     *
     * <p>Deliberately NOT behind {@link #inRecorderInstance()}. That gate exists because the normal loop enters
     * dungeons on a repeat, which is not something to have happen in an instance he plays in - and this mode
     * enters nothing. It reads the world it is given, wherever that is.
     */
    public static void startCaptureOnly() {
        if (!BuildVariant.DEV_TOOLS) {
            return;
        }
        RoomLibrary.loadAsync();
        // Capture names every room through the room database, and in a solo practice room nothing else loads it
        // (the live map only does inside a real dungeon) - so Ashfall's single rooms read 0 columns (2026-10-04).
        com.killer560.hub.roomdatabase.RoomDatabase.ensureLoading();
        stage = Stage.CAPTURE;
        waitTicks = 1;
        roomsAddedThisRun = 0;
        capturedSinceSave = 0;
        alertTicks = 0;
        // Not inherited from the last floor: a stale {done,total} that happened to read full would fire "FLOOR
        // DONE" the instant he arrived in the next one, before anything had been captured at all.
        floorDone = new int[]{0, 0};
        // Same reason as start(): the Return that submitted the command is still down next tick.
        suppressKeyStop = true;
        say("Room Recorder: CAPTURE ONLY. It will not join, walk or type anything - it reads the rooms you are "
                + "standing in. Run it again to stop.");
    }

    public static boolean isCaptureOnly() {
        return stage == Stage.CAPTURE;
    }

    /** The world capture last armed itself in, so re-entering a world arms it again but a single world does not
     *  re-announce every tick. */
    private static Object autoArmedLevel;

    /**
     * Arms capture-only by itself in a LOCAL dungeon that is not our own sim.
     *
     * <p>killer560 generated a night of floors in Ashfall to harvest rooms (2026-09-29) and nothing was written,
     * because capture-only existed but is a command he had to remember. The mod had identified 73 distinct rooms
     * across those floors and threw every one away. A feature that only works when you remember it is the
     * failure, not him, so this arms itself.
     *
     * <p>Three conditions, and all three matter. It requires an INTEGRATED server, so it can never arm on
     * Hypixel or p3sim - and capture-only sends nothing anyway, but this keeps the two facts independent. It
     * requires a dungeon, which in a local world means Ashfall's practice floor put up a Catacombs sidebar. And
     * it refuses our own sim, because the sim is BUILT from this library: capturing there would re-ingest our
     * own paste and quietly launder a bad room back in as if it had been seen for real.
     */
    private static void autoArmCapture(Minecraft client) {
        if (!BuildVariant.DEV_TOOLS
                || client.level == null
                || client.getSingleplayerServer() == null
                || SimState.isActive()) {
            return;
        }
        // A single-room practice world counts, and leaving it out was the bug.
        //
        // killer560 (2026-09-30) went through every room on the missing list in Ashfall's Dungeon Rooms, one
        // world each, and captured NOTHING - the log for that session holds the library loading and not one
        // capture line. Those worlds are named "Dungeon Room: Three Weirdos" and put up "Practice Room /
        // Room: X" with no "The Catacombs" line, so isInDungeon() is correctly false and this refused to arm.
        // scan() has handled exactly this case since 2026-09-29 via captureSingleRoom, but it only runs once
        // the stage is CAPTURE, and nothing ever set it. Two halves of one feature, each written as though the
        // other worked.
        String practiceRoom = DungeonState.isInDungeon() ? null : DungeonState.sidebarRoomName();
        if (!DungeonState.isInDungeon() && (practiceRoom == null || practiceRoom.isBlank()
                || "Unknown".equals(practiceRoom))) {
            return;
        }
        if (autoArmedLevel == client.level) {
            return;
        }
        // The previous world's last second of capture, before this one replaces it. Capture saves once a
        // second, and a practice room he leaves after ten seconds could otherwise lose the tail of it.
        if (autoArmedLevel != null) {
            RoomLibrary.saveDirty();
        }
        autoArmedLevel = client.level;
        singleRoomAnnounced = null;
        startCaptureOnly();
        say(practiceRoom == null
                ? "armed automatically - this is a local dungeon, so the rooms are being read as you walk."
                : "armed automatically - practice room \"" + practiceRoom + "\", reading it now.");
    }

    /** The practice room already reported complete, so the alert fires once per world rather than per tick. */
    private static String singleRoomAnnounced;

    /** New columns since the last write, so a long session is not one unsaved buffer. */
    private static int capturedSinceSave;

    /** The world the "floor done" alert has already fired in, so it says it once and not every tick. */
    private static Object doneAnnouncedLevel;

    /** A second between writes, and between recounts. Both were per tick and both cost real frames. */
    private static final int SAVE_INTERVAL_TICKS = 20;
    private static final int PROGRESS_INTERVAL_TICKS = 20;

    /** Last {@code {done, total}} for the floor he is standing in, recounted on the interval above. */
    private static int[] floorDone = new int[]{0, 0};

    private static int alertTicks;
    private static boolean resumeKeyWasDown;
    /** Quarter of a second, his figure. */
    private static final int TOGGLE_DEBOUNCE_TICKS = 5;
    private static int lastToggleTick = -1000;
    /** Ticks since the client started - only ever compared against itself, for the toggle debounce. */
    private static int tickCounter;
    private static boolean suppressKeyStop;
    private static int confirmTicks;
    private static int scanCursor;

    /**
     * New columns to read per tick, across all rooms.
     *
     * <p>256 columns is about 21k block reads a tick, small beside what the client already does each frame, and
     * over a 25 second window it is far more than a whole map needs - so the cap costs no coverage at all.
     */
    private static final int COLUMNS_PER_TICK = 256;

    /**
     * Tick to click the undersized-party confirm on, or -1 when the menu is not up.
     *
     * <p>killer560 (2026-09-28): "make sure it doesnt insta click the join button give it a random delay
     * between .5s and 1s." A click on the same tick the menu renders is not a thing a hand does - nobody has
     * reacted to a window before it has drawn - and this loop opens that menu every thirty seconds all night,
     * so the one perfectly-zero reaction time would be the most repeated signal in the session.
     */
    private static int confirmClickTick = -1;

    /** Reaction-time window for that click, in ticks: 0.5s to 1.0s. */
    private static final int CONFIRM_DELAY_MIN_TICKS = 10;
    private static final int CONFIRM_DELAY_MAX_TICKS = 20;

    /** How long to wait for the undersized-party menu before giving up on it and scanning anyway. */
    private static final int CONFIRM_TIMEOUT_TICKS = 100;

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
        if (McCompat.screen(client) != null) {
            // Typing, not commanding. Held down as far as the edge detector is concerned, so the key has to be
            // released and pressed again after the screen closes - otherwise the very keystroke that closes
            // chat arrives on the next tick as a fresh press.
            resumeKeyWasDown = true;
            return;
        }
        if (!pressed) {
            return;
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
        // Before anything else: a held movement key outliving the feature would walk him into a wall.
        RoomEntryWalk.reset();
        // Whatever the save interval has not flushed yet. The saveAll below is a full write and covers this
        // too, but doing it here means the order stops mattering if that ever changes.
        RoomLibrary.saveDirty();
        if (stage == Stage.OFF) {
            return;
        }
        stage = Stage.OFF;
        RoomLibrary.saveAll();
        say("Room Recorder OFF (" + reason + "). " + RoomLibrary.completeCount() + " of "
                + RoomLibrary.expectedCount() + " rooms complete.");
    }

    private static void tick(Minecraft client) {
        if (client == null) {
            return;
        }
        tickCounter++;
        pollResumeKey(client);
        if (stage == Stage.OFF) {
            autoArmCapture(client);
            return;
        }
        // Tracked in EVERY stage, paused included: the instance cooldown keeps running while he walks a
        // five-puzzle run by hand, and resuming into a stale clock would fire /f7 straight into a refusal.
        DungeonInstanceCooldown.tick(client);
        // The key that started or resumed it is still held on the next tick, and the any-key stop would
        // instantly undo it. Wait for a clean keyboard before arming that again.
        if (McCompat.screen(client) != null) {
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
        } else if (stage != Stage.PAUSED && stage != Stage.CAPTURE && firstKeyDown(client) != -1) {
            // Not while paused, and not while capture-only: both exist so he can walk the run himself, and
            // stopping the moment he touches a movement key would make the feature impossible to use for the
            // thing it is for.
            stop(KeyUtil.bindDisplayName(firstKeyDown(client)) + " pressed");
            return;
        }
        if (client.player == null || client.level == null) {
            // Between worlds: that is normal here, it is changing servers constantly.
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            if (stage == Stage.SCAN || stage == Stage.PAUSED || stage == Stage.CAPTURE) {
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
                stage = Stage.CONFIRM;
                confirmTicks = 0;
                confirmClickTick = -1;
                RoomEntryWalk.reset();
                waitTicks = 2;
            }
            case CONFIRM -> {
                if (confirmUndersizedParty(client)) {
                    // Clicked. The floor load follows, and the scan clock starts from the join rather than from
                    // the click, so a slow menu does not eat the 25 seconds of scanning.
                    stage = Stage.SCAN;
                    waitTicks = seconds(25);
                    return;
                }
                confirmTicks++;
                if (confirmTicks > CONFIRM_TIMEOUT_TICKS) {
                    // No menu and no floor: either the join was refused or the party is big enough that Hypixel
                    // never asked. Carry on into the scan rather than stalling here forever - a run that turns
                    // out to be empty costs one cycle, a stuck loop costs the night.
                    stage = Stage.SCAN;
                    waitTicks = seconds(25);
                    return;
                }
                waitTicks = 1;
            }
            case SCAN -> {
                ServerCommands.toServer("dh");
                runs++;
                RoomLibrary.saveAll();
                say(String.format(Locale.US, "run %d: %d new column(s), %d/%d rooms complete",
                        runs, roomsAddedThisRun, RoomLibrary.completeCount(), RoomLibrary.expectedCount()));
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
            case CAPTURE -> {
                com.killer560.hub.roomdatabase.RoomDatabase.ensureLoading();   // no-op once loaded
                scan(client);
                // Saved as it goes so a crash cannot lose a session - but NOT every tick.
                //
                // killer560 (2026-09-29): "something about this is making it lag my game out when I load in."
                // That was this. saveAll() rewrites all 47 rooms, about 13 MB of gzipped block arrays, and the
                // condition above it was true on essentially every tick of an active capture: twenty full
                // library writes a second, on the render thread, holding RoomLibrary's own lock against the
                // capture that was filling it. Now it writes only the rooms that changed, once a second.
                if (alertTicks % SAVE_INTERVAL_TICKS == 0) {
                    RoomLibrary.saveDirty();
                }
                // Likewise not per tick: floorProgress walks every room's seenColumn under the same lock, which
                // on a 22-room floor of 93x93 rooms is about 190,000 reads a tick for a number that moves
                // slowly. Once a second is still far more often than he can read it.
                if (alertTicks % PROGRESS_INTERVAL_TICKS == 0) {
                    floorDone = RoomLibrary.floorProgress(DungeonLayout.current());
                }
                // "Does it send a chat message once I can go to a new map" - it does now, and it is an alert
                // rather than a chat line, because the whole point is that he is not reading chat while waiting
                // for a floor to finish. Announced once per floor: the flag clears when the world changes, and
                // in Ashfall every generated dungeon is a fresh world load.
                if (floorDone[1] > 0 && floorDone[0] >= floorDone[1] && doneAnnouncedLevel != client.level) {
                    doneAnnouncedLevel = client.level;
                    RoomLibrary.saveDirty();
                    alert(client, String.format(Locale.US,
                            "FLOOR DONE - all %d room(s) here are fully captured. Generate the next one. "
                            + "(%d/%d rooms complete overall)",
                            floorDone[1], RoomLibrary.completeCount(), RoomLibrary.expectedCount()));
                }
                alertTicks++;
                if (alertTicks % 200 == 0) {
                    // Progress on THIS floor as well as the library total, because the library total barely
                    // moves and tells him nothing about whether it is worth standing here any longer.
                    say(String.format(Locale.US,
                            "capture: this floor %d/%d done, %d new column(s) this session, %d/%d rooms "
                            + "complete overall",
                            floorDone[0], floorDone[1], roomsAddedThisRun,
                            RoomLibrary.completeCount(), RoomLibrary.expectedCount()));
                }
                waitTicks = 1;
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
     * Captures the one room in a single-room practice world.
     *
     * <p>The name comes off the sidebar, because it is the only identification such a world offers. The extent
     * is found by probing every ROOM cell of the standard 11x11 grid for a roof: the world is otherwise empty,
     * so whatever has a roof is this room, and that handles a 1x2 or an L exactly as it handles a 1x1 without
     * needing to know the shape in advance.
     *
     * <p>Requires a local world. It cannot run on Hypixel, where isInDungeon() being false means the hub and
     * probing 36 columns of it would find buildings and call them a room.
     *
     * @return true if this looked like a practice room, whether or not anything new was read
     */
    private static boolean captureSingleRoom(Minecraft client) {
        if (client.getSingleplayerServer() == null || client.level == null) {
            return false;
        }
        String name = DungeonState.sidebarRoomName();
        if (name == null || name.isBlank() || "Unknown".equals(name)) {
            return false;
        }
        return sweepLattice(client) > 0 || true;
    }

    /**
     * Says when the practice room in this world is fully captured, so he knows when to load the next one.
     *
     * <p>Without it the only way to judge a single-room world is to guess how long to stand there. On
     * 2026-09-30 he gave each room about ten seconds, which would have been enough - but nothing was arming,
     * and nothing said that either. An alert rather than a chat line, for the same reason the floor-done one
     * is: he is looking at the world, not at chat.
     */
    private static void announceSingleRoomDone(Minecraft client) {
        String name = DungeonState.sidebarRoomName();
        if (name == null || name.equals(singleRoomAnnounced)) {
            return;
        }
        RoomLibrary.Room room = RoomLibrary.get(name);
        if (room == null || !room.complete()) {
            return;
        }
        singleRoomAnnounced = name;
        RoomLibrary.saveDirty();
        alert(client, String.format(Locale.US,
                "\"%s\" CAPTURED - load the next room. (%d/%d rooms complete overall)",
                name, RoomLibrary.completeCount(), RoomLibrary.expectedCount()));
    }

    /**
     * Captures every room standing on the dungeon lattice near the player, ignoring the 11x11 grid.
     *
     * <p>Rooms always sit on the same lattice - centres at {@code -185 + 32k} on both axes - whether they are
     * inside {@link DungeonLayout}'s window or 1500 blocks east of it. So this walks that lattice across the
     * loaded area instead of the grid, which is what lets a 47-cell Ashfall preset be captured at all.
     *
     * <p>Each cell is identified on its own through {@link RoomDatabase}: core hash to a name, and the clay
     * corner to tell one PLACEMENT from another. Grouping on the corner rather than on the name is the whole
     * trick - three Altars in a row share a name, and merging them would produce one 3-tile "Altar" made of
     * three different rooms, which is the same class of bug that put half of two rooms in one file before.
     */
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-roomrecorder");

    /** The world the sweep diagnostic was last logged for, so it says it once per world. */
    private static Object sweepReportedLevel;

    private static int sweepLattice(Minecraft client) {
        if (client.level == null || client.player == null) {
            return 0;
        }
        final int step = RoomLibrary.TILE + 1;
        final int reach = 160;
        int px = client.player.blockPosition().getX();
        int pz = client.player.blockPosition().getZ();
        // Snap onto the lattice the dungeon grid defines, so this and DungeonLayout agree about where a room
        // can start even though this is not limited to its window.
        int originX = DungeonLayout.cellCenter(0).getX();
        int originZ = DungeonLayout.cellCenter(0).getZ();
        int firstX = originX + Math.floorDiv(px - reach - originX, step) * step;
        int firstZ = originZ + Math.floorDiv(pz - reach - originZ, step) * step;

        // corner key -> {name, minCentreX, minCentreZ, maxCentreX, maxCentreZ}
        java.util.Map<Long, Object[]> placements = new java.util.LinkedHashMap<>();
        int cells = 0, roofed = 0, unknownCore = 0, noCorner = 0;
        StringBuilder seen = new StringBuilder();
        for (int cz = firstZ; cz <= pz + reach; cz += step) {
            for (int cx = firstX; cx <= px + reach; cx += step) {
                cells++;
                int roof = LiveMapFeature.roofAt(client, cx, cz);
                if (roof <= 0) {
                    continue;
                }
                roofed++;
                int core = RoomDatabase.getCore(client.level, cx, cz);
                RoomEntry entry = RoomDatabase.lookup(core);
                if (entry == null || entry.name == null || entry.name.isBlank()) {
                    unknownCore++;
                    if (seen.length() < 300) {
                        seen.append(" (").append(cx).append(',').append(cz).append(" roof ").append(roof)
                                .append(" core ").append(core).append(" unknown)");
                    }
                    continue;
                }
                int[] rot = RoomDatabase.findRotationAndCorner(client.level, cx, cz, roof);
                if (rot == null) {
                    // No clay corner: cannot tell this placement from another of the same room, so leave it.
                    noCorner++;
                    if (seen.length() < 300) {
                        seen.append(" (").append(cx).append(',').append(cz).append(' ').append(entry.name)
                                .append(" no clay corner)");
                    }
                    continue;
                }
                long key = ((long) rot[1] << 32) ^ (rot[2] & 0xffffffffL);
                Object[] p = placements.get(key);
                if (p == null) {
                    placements.put(key, new Object[]{entry.name, cx, cz, cx, cz});
                } else {
                    p[1] = Math.min((int) p[1], cx);
                    p[2] = Math.min((int) p[2], cz);
                    p[3] = Math.max((int) p[3], cx);
                    p[4] = Math.max((int) p[4], cz);
                }
            }
        }

        // Once per world: why a solo room reads nothing. Ashfall's single rooms captured 0 columns on 2026-10-04
        // with no clue which step refused them.
        if (sweepReportedLevel != client.level) {
            sweepReportedLevel = client.level;
            LOGGER.info("Room Recorder sweep: player {},{} lattice origin {},{} - {} cell(s) probed, {} roofed, {} with an "
                    + "unknown core, {} without a clay corner, {} placement(s) [room db ready={}]{}",
                    px, pz, originX, originZ, cells, roofed, unknownCore, noCorner, placements.size(),
                    RoomDatabase.isReady(), seen);
            for (Object[] p : placements.values()) {
                LOGGER.info("Room Recorder sweep: {} at centres {},{}..{},{}", p[0], p[1], p[2], p[3], p[4]);
            }
        }
        int budget = COLUMNS_PER_TICK;
        int added = 0;
        for (Object[] p : placements.values()) {
            if (budget <= 0) {
                break;
            }
            String rn = (String) p[0];
            int tilesX = ((int) p[3] - (int) p[1]) / step + 1;
            int tilesZ = ((int) p[4] - (int) p[2]) / step + 1;
            int got = RoomLibrary.captureAt(client.level, rn, (int) p[1], (int) p[2], tilesX, tilesZ, budget);
            budget -= got;
            added += got;
        }
        roomsAddedThisRun += added;
        return added;
    }

    /**
     * Reads whatever of the current room is loaded.
     *
     * <p>Every tick of the scan window rather than once at the end, because the rooms that load are the ones
     * being walked past - waiting until the end would only ever capture wherever it happened to stop.
     */
    private static void scan(Minecraft client) {
        if (!DungeonState.isInDungeon()) {
            // A single-room practice world has no dungeon and no layout, but it does have a room.
            //
            // killer560 (2026-09-29) went room by room through Ashfall's Dungeon Rooms list - Rare Pillars,
            // Tombstone, Redstone Warrior, all off the missing list - and captured NOTHING, because those
            // worlds put up "Practice Room / Room: X" with no "The Catacombs" line. isInDungeon() is correctly
            // false, so scan() bailed here on every tick and the recorder cheerfully reported "this floor 0/0".
            if (stage == Stage.CAPTURE && captureSingleRoom(client)) {
                announceSingleRoomDone(client);
                return;
            }
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
        // The walk-in runs alongside the scan rather than before it: the whole reason to move is to load more
        // rooms, and the sweep should be picking them up as they arrive rather than waiting for the walk to end.
        if (RoomRecorderConfig.getInstance().isWalkInOnEntry() && stage == Stage.SCAN) {
            if (RoomEntryWalk.isWalking()) {
                RoomEntryWalk.tick(client);
            } else {
                RoomEntryWalk.begin(client);
            }
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout == null) {
            return;
        }
        // killer560 (2026-09-28): "make sure it doesnt just scan the room i am in but every room in my render
        // distance." Standing in a doorway already loads four rooms, and a loop that only recorded the tile
        // underfoot threw away most of what the client had in memory.
        //
        // Nothing here decides what is "in render distance". RoomLibrary.capture already tests every column
        // against the chunk cache and skips the ones that are not loaded, so an out-of-range room contributes
        // nothing rather than a room-shaped block of air. Keeping that the single place load state is judged is
        // what keeps this honest - asking the question twice, in two ways, is how you store air and call it seen.
        // Beyond the grid as well: an Ashfall preset can be far wider than DungeonLayout's window, and the
        // rooms out there are identified and captured exactly the same way.
        sweepLattice(client);
        int rooms = layout.roomCount();
        if (rooms > 0) {
            int budget = COLUMNS_PER_TICK;
            for (int i = 0; i < rooms && budget > 0; i++) {
                // Rotated start, so the room the budget runs out on is not the same one every tick and the far
                // side of the map is not permanently starved by the near side.
                int added = RoomLibrary.capture(client.level, layout, (scanCursor + i) % rooms, budget);
                budget -= added;
                roomsAddedThisRun += added;
            }
            scanCursor = (scanCursor + 1) % rooms;
        }
        captureMobSpawns(client, layout);
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
    /**
     * Records every starred mob in the loaded world, into whichever room it actually stands in.
     *
     * <p>One sweep for the whole map rather than one per room. It used to take a room and walk the entity list
     * for it, which is a full sweep per room now that every room is scanned - and it decided whether a stand
     * belonged to that room by subtracting the room's origin and rejecting negatives, with NO UPPER BOUND. A
     * stand in the room to the +x or +z side produced positive coordinates that landed inside the array and was
     * filed under the wrong room. Asking {@code roomAtWorld} where the stand is cannot make that mistake, and
     * costs one sweep instead of one per room.
     */
    private static void captureMobSpawns(Minecraft client, DungeonLayout layout) {
        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) {
                continue;
            }
            var custom = stand.getCustomName();
            if (custom == null || !custom.getString().contains(STAR)) {
                continue;
            }
            int room = layout.roomAtWorld(stand.getX(), stand.getZ());
            if (room < 0) {
                continue;
            }
            String name = layout.name(room);
            if (name == null || name.isBlank() || "Unknown".equals(name)) {
                continue;
            }
            int[] tiles = layout.tiles(room);
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            int minGx = Integer.MAX_VALUE;
            int minGz = Integer.MAX_VALUE;
            for (int idx : tiles) {
                minGx = Math.min(minGx, idx % DungeonLayout.GRID);
                minGz = Math.min(minGz, idx / DungeonLayout.GRID);
            }
            var origin = DungeonLayout.cellCenter(minGz * DungeonLayout.GRID + minGx);
            int lx = (int) Math.floor(stand.getX()) - (origin.getX() - RoomLibrary.TILE / 2);
            int ly = (int) Math.floor(stand.getY());
            int lz = (int) Math.floor(stand.getZ()) - (origin.getZ() - RoomLibrary.TILE / 2);
            if (lx < 0 || lz < 0 || ly < RoomLibrary.MIN_Y || ly > RoomLibrary.MAX_Y) {
                continue;
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
     * Clicks through Hypixel's "Undersized party!" warning, if it is open.
     *
     * <p>Found by the ITEM'S OWN TEXT rather than by a slot number. A slot index is a guess read off one
     * screenshot that breaks silently the day Hypixel moves it; "Click to play anyway!" is the thing the menu
     * exists to offer and is what a person reads to find it too.
     *
     * <p>Restricted to the container's own slots - never the player's inventory - so a coincidentally named
     * item in his hotbar can never be the thing this clicks.
     *
     * @return whether the confirm was clicked
     */
    private static boolean confirmUndersizedParty(Minecraft client) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            confirmClickTick = -1;
            return false;
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString();
        if (!title.toLowerCase(Locale.ROOT).contains("undersized party")) {
            confirmClickTick = -1;
            return false;
        }
        // Wait out a human-looking reaction before touching it, drawn fresh each time the menu appears so the
        // delay is never the same twice.
        if (confirmClickTick < 0) {
            confirmClickTick = tickCounter + CONFIRM_DELAY_MIN_TICKS
                    + JITTER.nextInt(CONFIRM_DELAY_MAX_TICKS - CONFIRM_DELAY_MIN_TICKS + 1);
            return false;
        }
        if (tickCounter < confirmClickTick) {
            return false;
        }

        AbstractContainerMenu menu = screen.getMenu();
        for (Slot slot : menu.slots) {
            if (client.player != null && slot.container == client.player.getInventory()) {
                continue;
            }
            if (!looksLikePlayAnyway(slot.getItem())) {
                continue;
            }
            if (!ActionGate.tryAct(ActionGate.Actor.ROOM_RECORDER_MENU, screen)) {
                return false;
            }
            client.gameMode.handleContainerInput(menu.containerId, slot.index, 0,
                    ContainerInput.PICKUP, client.player);
            confirmClickTick = -1;
            say("undersized party - playing anyway");
            return true;
        }
        return false;
    }

    /** The confirm item: its name or lore offers to play anyway. */
    private static boolean looksLikePlayAnyway(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (CheatUtils.plainName(stack).toLowerCase(Locale.ROOT).contains("play anyway")) {
            return true;
        }
        for (String line : CheatUtils.lore(stack)) {
            if (line.toLowerCase(Locale.ROOT).contains("play anyway")) {
                return true;
            }
        }
        return false;
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
