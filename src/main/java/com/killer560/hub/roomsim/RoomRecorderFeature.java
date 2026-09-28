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

    private enum Stage {
        /** Not running. */
        OFF,
        /** Waiting to send /f7. */
        ENTER,
        /** Inside the run, reading rooms. */
        SCAN,
        /** Sent /dh, waiting to arrive. */
        LEAVING,
        /** In the hub, waiting before the next /f7. */
        HUB,
        /** Dropped to limbo: the long wait before /skyblock. */
        LIMBO,
        /** Sent /skyblock, waiting before /f7. */
        REJOIN
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
        say("Room Recorder ON. It will run F7 on a loop. Press any key to stop.");
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
        if (stage == Stage.OFF || client == null) {
            return;
        }
        if (anyKeyDown(client)) {
            stop("key pressed");
            return;
        }
        if (client.player == null || client.level == null) {
            // Between worlds: that is normal here, it is changing servers constantly.
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            if (stage == Stage.SCAN) {
                scan(client);
            }
            return;
        }
        switch (stage) {
            case ENTER, REJOIN -> {
                ServerCommands.toServer("f7");
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
                waitTicks = seconds(7);
            }
            case LEAVING, HUB -> {
                stage = Stage.ENTER;
                waitTicks = seconds(1);
            }
            case LIMBO -> {
                ServerCommands.toServer("skyblock");
                stage = Stage.REJOIN;
                waitTicks = seconds(5);
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
            if (client.level != null && client.player != null && client.player.position().y < 0) {
                stage = Stage.LIMBO;
                waitTicks = seconds(65);
                say("limbo - waiting 65s then rejoining");
            }
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
    }

    /**
     * Whether the user has touched the keyboard.
     *
     * <p>Polled across the whole keyboard rather than hooking one key, because the promise is "any key" and a
     * list of keys is a list someone will press something missing from. Modifier keys are included: holding
     * shift to sprint is still a person taking the controls back.
     */
    private static boolean anyKeyDown(Minecraft client) {
        var window = client.getWindow();
        if (window == null) {
            return false;
        }
        // KeyUtil rather than raw GLFW: glfwGetKey rejects codes outside its keyboard range, and sweeping the
        // whole range is exactly how you hand it an invalid one.
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (KeyUtil.isKeyDown(window, key)) {
                return true;
            }
        }
        return false;
    }

    /** Ticks for a wait, with a few per cent of jitter so the loop is not a metronome. */
    private static int seconds(double s) {
        double wobble = 1.0 + (JITTER.nextDouble() * 2.0 - 1.0) * VARIANCE;
        return Math.max(1, (int) Math.round(s * 20.0 * wobble));
    }

    private static void say(String message) {
        try {
            ModChat.send("Room Recorder", ModChat.text(message));
        } catch (Throwable ignored) {
            // no player yet
        }
    }
}
