package com.killer560.hub.gifplayer;

import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loops every enabled companion audio file found in {@link GifPlayerFeature#folder()} at once, each
 * independently toggleable (see {@link GifPlayerConfig#isAudioFileEnabled}), sharing one volume
 * slider. Played through Java Sound ({@link Clip}), a completely separate audio path from
 * Minecraft's own OpenAL sound engine (which is built around resource-pack-registered sound events,
 * not arbitrary files on disk), so it runs independently and won't conflict with game sounds.
 * WAV/AIFF/AU are decoded natively by the JDK; .mp3 files are transparently converted to a cached
 * .wav first (see {@link Mp3Converter}) using bundled mp3spi/jlayer/tritonus-share, since the JDK has
 * no MP3 support of its own.
 */
public final class GifAudioFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-gifplayer-audio");
    private static final List<String> NATIVE_EXTENSIONS = List.of(".wav", ".wave", ".aiff", ".aif", ".au");
    private static final String MP3_EXTENSION = ".mp3";

    private static final Path CACHE_FOLDER =
            GifPlayerFeature.folder().resolveSibling("killer560smod-gifs-cache");

    private record Loaded(Clip clip, long stamp) {
    }

    /** Filename (as found in the gifs folder, .mp3 included) -> its open, looping Clip and the file it came from. */
    private static final Map<String, Loaded> clips = new ConcurrentHashMap<>();

    /** Opening a clip (and converting an .mp3 first) reads and decodes the whole file, so it runs here, one reload at a
     *  time, instead of on the render thread. */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-gif-audio");
        t.setDaemon(true);
        return t;
    });

    /** Skyblock Only: true while audio is paused because you're outside Skyblock/p3sim. Uses the gate's
     *  location state directly (not {@code SkyblockGate.allows()}) so opening the mod menu elsewhere doesn't
     *  start the loops back up. */
    private static volatile boolean gateBlocked = false;

    private static boolean computeGateBlocked() {
        return com.killer560.hub.util.SkyblockGate.isEnabled() && !com.killer560.hub.util.SkyblockGate.isOnSkyblock();
    }

    /** Called every client tick (registered by {@link GifPlayerFeature#register()}): pauses the looping clips
     *  when Skyblock Only blocks them and resumes them on return, without touching the saved audio toggle. */
    public static void tickSkyblockGate() {
        boolean blocked = computeGateBlocked();
        if (blocked == gateBlocked) {
            return;
        }
        gateBlocked = blocked;
        if (GifPlayerConfig.getInstance().isAudioEnabled()) {
            for (Loaded l : clips.values()) {
                if (blocked) {
                    l.clip().stop();
                } else {
                    l.clip().loop(Clip.LOOP_CONTINUOUSLY);
                }
            }
        }
    }

    /** Re-scans the folder for every supported audio file and syncs {@link #clips} to match which
     *  ones are currently enabled, converting any .mp3 to a cached .wav first. A clip whose file has not changed is
     *  left playing. Runs on a background thread. */
    public static void reload() {
        LOADER.execute(() -> {
            try {
                reloadNow();
            } catch (Exception | OutOfMemoryError e) {
                LOGGER.error("Failed to reload GIF audio", e);
            }
        });
    }

    private static void reloadNow() {
        List<Path> found;
        try (Stream<Path> stream = Files.list(GifPlayerFeature.folder())) {
            found = stream.filter(Files::isRegularFile)
                    .filter(GifAudioFeature::isSupportedAudio)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to list GIF folder for audio", e);
            return;
        }

        java.util.Set<String> foundNames = found.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());
        for (String loadedName : List.copyOf(clips.keySet())) {
            if (!foundNames.contains(loadedName)) {
                closeAndRemove(loadedName);
            }
        }

        GifPlayerConfig cfg = GifPlayerConfig.getInstance();
        for (Path file : found) {
            String name = file.getFileName().toString();
            boolean shouldBeLoaded = cfg.isAudioFileEnabled(name);
            if (!shouldBeLoaded) {
                closeAndRemove(name);
                continue;
            }
            long stamp = GifPlayerFeature.fileStamp(file);
            Loaded current = clips.get(name);
            if (current != null && current.stamp() == stamp) {
                continue;
            }
            loadClip(file, name, stamp);
        }
    }

    private static void loadClip(Path file, String name, long stamp) {
        closeAndRemove(name);
        try {
            Path playablePath = name.toLowerCase(Locale.US).endsWith(MP3_EXTENSION)
                    ? Mp3Converter.convertToWav(file, CACHE_FOLDER)
                    : file;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(playablePath.toFile())) {
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                clips.put(name, new Loaded(clip, stamp));
                applyVolumeTo(clip);
                if (GifPlayerConfig.getInstance().isAudioEnabled() && !computeGateBlocked()) {
                    clip.loop(Clip.LOOP_CONTINUOUSLY);
                }
                LOGGER.info("Loaded GIF audio: {}", name);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load GIF audio: {}", name, e);
        }
    }

    /** Starts/stops looping every currently-loaded clip, without re-reading anything from disk. */
    public static void setEnabled(boolean enabled) {
        for (Loaded l : clips.values()) {
            if (enabled && !computeGateBlocked()) {
                l.clip().loop(Clip.LOOP_CONTINUOUSLY);
            } else {
                l.clip().stop();
            }
        }
    }

    public static void applyVolume() {
        for (Loaded l : clips.values()) {
            applyVolumeTo(l.clip());
        }
    }

    private static void applyVolumeTo(Clip clip) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        float volume = Math.max(0.0001f, Math.min(1.0f, GifPlayerConfig.getInstance().getVolume()));
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float db = (float) (20.0 * Math.log10(volume));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
    }

    private static void closeAndRemove(String name) {
        Loaded l = clips.remove(name);
        if (l != null) {
            l.clip().stop();
            l.clip().close();
        }
    }

    private static boolean isSupportedAudio(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.US);
        return name.endsWith(MP3_EXTENSION) || NATIVE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /** Every supported audio filename currently in the folder, regardless of its enabled state -
     *  for the tab's per-file toggle list. */
    public static List<String> discoverFileNames() {
        try (Stream<Path> stream = Files.list(GifPlayerFeature.folder())) {
            return stream.filter(Files::isRegularFile)
                    .filter(GifAudioFeature::isSupportedAudio)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    public static List<String> playingFileNames() {
        return List.copyOf(clips.keySet());
    }

    private GifAudioFeature() {
    }
}
