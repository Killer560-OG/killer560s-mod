package com.killer560.hub.proximityvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.TargetDataLine;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs the Proximity Voice tab's "Test Mic" live input-level readout - killer560, 2026-09-20: a device
 * picker "would make [proximity voice] genuinely usable" with a way to check the pick actually works.
 * <p>
 * Two real constraints shaped this:
 * <ul>
 * <li>If {@link ProximityVoiceFeature} already has the mic open (a real dungeon run in progress), opening
 * a second {@link TargetDataLine} on the same device would at best fight it for frames and at worst just
 * fail to open - so while the feature is active, {@link #level()} mirrors its live level instead of
 * opening anything of its own.
 * <li>{@code BaseTab}/{@code ModScreen} has no "this tab/screen just closed" callback this package can
 * hook, so {@link #touch} - called once per render frame the meter widget is on screen - doubles as a
 * heartbeat. A watchdog thread closes the test line on its own once the widget stops calling in (tab
 * switched, menu closed, game window loses the mod screen entirely), giving the same "always closes"
 * guarantee {@link ProximityVoiceFeature#stop()} gives the real mic line - this repo has a real bug history
 * (2026-09) of a {@code TargetDataLine} left open forever, so a test line must never be able to outlive the
 * GUI that opened it.
 * </ul>
 */
public final class MicTester {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-proximityvoice");
    private static final long IDLE_TIMEOUT_NANOS = 750_000_000L; // no touch() in 0.75s = tab left/closed

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-voice-mictest");
        t.setDaemon(true);
        return t;
    });

    private static volatile TargetDataLine testLine;
    private static volatile String openedForDevice;
    private static volatile boolean opening = false;
    private static volatile float ownLevel = 0f;
    private static volatile long lastTouchNanos = 0;
    private static volatile boolean watchdogStarted = false;

    private MicTester() {
    }

    /** Call once per render frame the level meter widget is visible. Cheap: records a timestamp, starts
     *  the watchdog the first time, and (only off-thread, via the executor) opens or swaps a test line
     *  when needed. Never blocks the render thread. Public: called from the tab, a different package. */
    public static void touch(String deviceName) {
        lastTouchNanos = System.nanoTime();
        startWatchdogOnce();

        if (ProximityVoiceFeature.isMicActive()) {
            // The real feature owns the mic now - drop any test line of our own so the two never compete
            // for the same device.
            if (testLine != null) {
                EXECUTOR.execute(MicTester::closeLine);
            }
            return;
        }

        if (testLine != null && !Objects.equals(openedForDevice, deviceName)) {
            EXECUTOR.execute(MicTester::closeLine);
        }
        if (testLine == null && !opening) {
            opening = true;
            EXECUTOR.execute(() -> {
                try {
                    openLine(deviceName);
                } finally {
                    opening = false;
                }
            });
        }
    }

    /** Current level, 0-1. Mirrors {@link ProximityVoiceFeature}'s live level while it owns the mic.
     *  Public: read by the tab, a different package. */
    public static float level() {
        return ProximityVoiceFeature.isMicActive() ? ProximityVoiceFeature.currentInputLevel() : ownLevel;
    }

    private static void openLine(String deviceName) {
        try {
            MicrophoneDevices.OpenedLine opened = MicrophoneDevices.openLine(deviceName);
            opened.line().start();
            testLine = opened.line();
            openedForDevice = deviceName;
            Thread t = new Thread(() -> readLoop(opened.line()), "killer560smod-voice-mictest-read");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            LOGGER.warn("[ProximityVoice] Test Mic failed to open a line", e);
            ownLevel = 0f;
        }
    }

    private static void readLoop(TargetDataLine line) {
        byte[] buffer = new byte[640];
        try {
            while (line == testLine && line.isOpen()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) {
                    ownLevel = ProximityVoiceFeature.computeLevel(buffer, read);
                }
            }
        } catch (Exception ignored) {
            // Line closed out from under us (device changed/unplugged/watchdog fired) - just stop reading.
        }
    }

    private static void closeLine() {
        TargetDataLine line = testLine;
        testLine = null;
        openedForDevice = null;
        ownLevel = 0f;
        if (line != null) {
            try {
                line.stop();
            } catch (Exception ignored) {
            }
            try {
                line.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void startWatchdogOnce() {
        if (watchdogStarted) {
            return;
        }
        watchdogStarted = true;
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
                boolean idle = System.nanoTime() - lastTouchNanos > IDLE_TIMEOUT_NANOS;
                if (testLine != null && (idle || ProximityVoiceFeature.isMicActive())) {
                    EXECUTOR.execute(MicTester::closeLine);
                }
            }
        }, "killer560smod-voice-mictest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
