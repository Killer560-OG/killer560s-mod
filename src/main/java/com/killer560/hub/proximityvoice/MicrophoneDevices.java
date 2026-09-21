package com.killer560.hub.proximityvoice;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microphone device enumeration and resolution for {@link ProximityVoiceFeature} - killer560, 2026-09-20:
 * "it needs a way to select microphones". Before this, {@code startMicCapture} just asked
 * {@link AudioSystem} for any line matching the format and took whatever it handed back, which on a
 * machine with several inputs is not necessarily the one the user wants.
 * <p>
 * Devices are always identified by {@link Mixer.Info#getName()}, never by list position - an index would
 * silently point at a different physical device the next time a USB mic/headset is plugged in or unplugged
 * and the OS re-numbers its device list.
 * <p>
 * {@link Mixer.Info#getName()} is not guaranteed unique in theory, but Windows' real mixer names in
 * practice are (driver + port string); treating the first name match as authoritative is the same
 * assumption every other name-keyed lookup in this mod already makes.
 */
public final class MicrophoneDevices {

    /** Shown in the GUI, and stored as {@code ""} in config, for "let the OS pick". Public: read by
     *  {@link com.killer560.hub.gui.tab.ProximityVoiceTab}, a different package. */
    public static final String DEFAULT_LABEL = "System Default";

    private static final ExecutorService ENUM_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-voice-enum");
        t.setDaemon(true);
        return t;
    });

    /** Enumerating {@code AudioSystem.getMixerInfo()} and probing each one can be slow on Windows with
     *  several devices - never call {@link #enumerateNow()} from the render thread. The GUI instead reads
     *  this cache (updated by {@link #refreshAsync()}) every frame, which is free. */
    private static volatile List<String> cachedNames = List.of();
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);

    private MicrophoneDevices() {
    }

    /** Last-known device names, cheap to call every frame. Empty until the first {@link #refreshAsync()}
     *  finishes at least once. Public: read by the tab, a different package. */
    public static List<String> cachedDeviceNames() {
        return cachedNames;
    }

    /** Kicks off a background re-enumeration if one isn't already running; safe to call every frame the
     *  tab is open. Public: called from the tab, a different package. */
    public static void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        ENUM_EXECUTOR.execute(() -> {
            try {
                cachedNames = enumerateNow();
            } finally {
                refreshing.set(false);
            }
        });
    }

    /** Real, blocking enumeration - only call this off the render thread. */
    private static List<String> enumerateNow() {
        List<String> result = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, ProximityVoiceFeature.FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    result.add(mixerInfo.getName());
                }
            } catch (Exception ignored) {
                // A mixer that throws on query (unplugged mid-scan, a driver quirk) just doesn't show up.
            }
        }
        return result;
    }

    /** What actually got opened - the requested device's real name isn't necessarily {@code requested}
     *  when it had to fall back (missing, or unusable), so the caller has something honest to show as
     *  "in use". */
    record OpenedLine(TargetDataLine line, String deviceName) {
    }

    /**
     * Opens a {@link TargetDataLine} in the mod's voice format for the device named {@code requested}, or
     * the system default when {@code requested} is blank or no longer present (unplugged, renamed) - never
     * silently fails to open a mic at all just because a saved device vanished. Does its own live
     * {@code AudioSystem.getMixerInfo()} scan rather than trusting {@link #cachedDeviceNames()}, so a
     * device that appeared after the last GUI refresh (or before the very first one) still resolves - this
     * is only ever called off the render thread anyway (see {@link ProximityVoiceFeature}'s mic executor).
     */
    static OpenedLine openLine(String requested) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, ProximityVoiceFeature.FORMAT);
        if (requested != null && !requested.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!requested.equals(mixerInfo.getName())) {
                    continue;
                }
                try {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                        line.open(ProximityVoiceFeature.FORMAT);
                        return new OpenedLine(line, mixerInfo.getName());
                    }
                } catch (Exception ignored) {
                    // Falls through to the default below.
                }
                break;
            }
            // Saved device is gone or unusable - fall back instead of leaving the mic closed entirely.
        }
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("No compatible microphone found");
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(ProximityVoiceFeature.FORMAT);
        return new OpenedLine(line, DEFAULT_LABEL);
    }
}
