package com.killer560.hub.roomsim;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * A full run: locked in the entrance until a countdown finishes, then timed.
 *
 * <p>killer560 (2026-09-28): "if I want to do a full run, then I should always start in green room with that
 * door locked in a five second countdown for it to start".
 *
 * <p>The countdown is the point of it. Practising a clear means practising the opening seconds, and a run that
 * begins the instant you load is a run whose first room you were already walking through before you were ready.
 * Locking the door makes the start line real rather than an instruction to wait.
 *
 * <p>The clock starts when the door OPENS, not when the countdown begins, so a five second countdown does not
 * appear as five seconds of run time - the number has to mean the same thing as the timer on a real run or it
 * is worse than no timer.
 */
public final class SimRun {

    /** His figure. */
    private static final int COUNTDOWN_SECONDS = 5;
    private static final int TICKS_PER_SECOND = 20;

    private static boolean armed;
    private static int ticksLeft;
    private static int lastSecondAnnounced = -1;
    private static long startedAtMs;
    private static boolean running;
    private static BlockPos entranceDoor;

    private SimRun() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimRun.tick", SimRun::tick));
    }

    /** Registers the /start command. Kept apart from the tick registration, which already exists. */
    public static void registerStartCommand() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, access) -> dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("start")
                                // Sim-only, like /map and /fly: an ungated /start would claim the name on
                                // Hypixel, where the server has its own idea of what it means.
                                .requires(src -> SimState.canAct(Minecraft.getInstance()))
                                .executes(ctx -> {
                                    // The door the builder recorded, so this finds the right one rather than
                                    // the nearest one - the nearest is whichever he happens to be standing by.
                                    begin(Minecraft.getInstance(), SimBuilder.entranceDoor());
                                    return 1;
                                })));
    }

    /**
     * Starts the countdown, with the entrance door shut.
     *
     * @param door the entrance door to hold closed, or null if the map has none yet
     */
    public static void begin(Minecraft client, BlockPos door) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Not in the sim."));
            return;
        }
        entranceDoor = door;
        armed = true;
        running = false;
        ticksLeft = COUNTDOWN_SECONDS * TICKS_PER_SECOND;
        lastSecondAnnounced = -1;
        SimClass.applyMageOnly(client);
        ModChat.send("Sim", ModChat.text("Run starting in "), ModChat.value(String.valueOf(COUNTDOWN_SECONDS)),
                ModChat.text("..."));
    }

    /** Stops a run and forgets the clock, for leaving the sim or restarting. */
    public static void reset() {
        armed = false;
        running = false;
        ticksLeft = 0;
        lastSecondAnnounced = -1;
        entranceDoor = null;
    }

    public static boolean isRunning() {
        return running;
    }

    /** Milliseconds since the door opened, or 0 when no run is under way. */
    public static long elapsedMs() {
        return running ? System.currentTimeMillis() - startedAtMs : 0L;
    }

    private static void tick(Minecraft client) {
        if (!armed || !SimState.canAct(client)) {
            return;
        }
        if (ticksLeft > 0) {
            ticksLeft--;
            int second = (ticksLeft + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
            // Announced on the change, not every tick: a countdown that says "3" twenty times is noise.
            if (second != lastSecondAnnounced && second > 0) {
                lastSecondAnnounced = second;
                ModChat.send("Sim", ModChat.value(String.valueOf(second)));
            }
            return;
        }
        if (!running) {
            running = true;
            startedAtMs = System.currentTimeMillis();
            if (entranceDoor != null) {
                SimDoors.openForTest(client, entranceDoor);
            }
            ModChat.send("Sim", ModChat.text("GO"));
        }
    }

    /** A readable run time, for whatever ends up showing it. */
    public static String elapsedText() {
        long ms = elapsedMs();
        return String.format(Locale.US, "%d:%05.2f", ms / 60000, (ms % 60000) / 1000.0);
    }
}
