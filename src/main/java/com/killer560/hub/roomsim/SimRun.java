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
 * door locked in a five second countdown for it to start". Since 2026-10-25 the run starts by itself when a whole
 * floor has been built ({@link #autoStart}), with a three second countdown, and there is no /start command.
 *
 * <p>The countdown is the point of it. Practicing a clear means practicing the opening seconds, and a run that
 * begins the instant you load is a run whose first room you were already walking through before you were ready.
 * Locking the door makes the start line real rather than an instruction to wait.
 *
 * <p>The clock starts when the door OPENS, not when the countdown begins, so a five second countdown does not
 * appear as five seconds of run time - the number has to mean the same thing as the timer on a real run or it
 * is worse than no timer.
 */
public final class SimRun {

    /** His figure: 5 s from 2026-09-28, 3 s since 2026-10-25 when the run began starting by itself. */
    private static final int COUNTDOWN_SECONDS = 3;
    private static final int TICKS_PER_SECOND = 20;

    private static boolean armed;
    private static int ticksLeft;
    private static int lastSecondAnnounced = -1;
    private static long startedAtMs;
    private static boolean running;
    /**
     * Whether the countdown has ever reached GO on this floor.
     *
     * <p>Separate from {@link #running} because anything asking "is this floor still locked down" wants a
     * latch, not the live flag - {@link #begin} sets running back to false every time it re-arms.
     */
    private static boolean everStarted;
    private static BlockPos entranceDoor;

    private SimRun() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimRun.tick", SimRun::tick));
    }

    /**
     * Starts the run by itself once a whole floor has finished building (killer560, 2026-10-25: "make dungeon sim auto
     * start and do a 3s countdown remove the /start command"). Called from {@link SimBuilder} on the client thread
     * after the floor is published and he has been put in the entrance. A single room never starts a run.
     */
    public static void autoStart(Minecraft client) {
        if (!SimState.canAct(client) || !SimState.isGeneratedFloor()) {
            return;
        }
        begin(client, SimBuilder.entranceDoor());
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
        // Checked here as well as in requires(), because requires() only decides whether the command is
        // OFFERED - begin() is also reached from the menu, and a gate in one of two callers is the shape of
        // bug this project has written down more than once.
        if (!SimState.isGeneratedFloor()) {
            ModChat.send("Sim", ModChat.text("A run needs a whole floor - this is a single room."));
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
        everStarted = false;
        ticksLeft = 0;
        lastSecondAnnounced = -1;
        entranceDoor = null;
    }

    public static boolean isRunning() {
        return running;
    }

    /** Whether a run has started on this floor - true from GO until the next map is built. */
    public static boolean hasStarted() {
        return everStarted;
    }

    /** Ticks left on the countdown, 0 once the door has opened. Also what the HUD reads. */
    public static int countdownTicks() {
        return ticksLeft;
    }

    /** True between {@link #begin} and the end of the run - the countdown is only ticked while armed. */
    public static boolean isArmed() {
        return armed;
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
            everStarted = true;
            startedAtMs = System.currentTimeMillis();
            // The gate, not just the one door that was registered. killer560 (2026-09-29): "Once the start
            // finishes it needs to remove the blocks for the gate. Those infested chizzledd blocks."
            SimDoors.openEntranceGate(client, entranceDoor);
            ModChat.send("Sim", ModChat.text("GO"));
            SimLiveMobs.onRunStart(client);
        }
    }

    /** A readable run time, for whatever ends up showing it. */
    public static String elapsedText() {
        long ms = elapsedMs();
        return String.format(Locale.US, "%d:%05.2f", ms / 60000, (ms % 60000) / 1000.0);
    }
}
