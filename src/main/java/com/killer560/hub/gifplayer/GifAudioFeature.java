package com.killer560.hub.gifplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-gifplayer-audio");
    private static final List<String> NATIVE_EXTENSIONS = List.of(".wav", ".wave", ".aiff", ".aif", ".au");
    private static final String MP3_EXTENSION = ".mp3";

    private static final Path CACHE_FOLDER =
            GifPlayerFeature.folder().resolveSibling("killer560smod-gifs-cache");

    /** Filename (as found in the gifs folder, .mp3 included) -> its open, looping Clip. */
    private static final Map<String, Clip> clips = new LinkedHashMap<>();

    /** Re-scans the folder for every supported audio file and syncs {@link #clips} to match which
     *  ones are currently enabled, converting any .mp3 to a cached .wav first. */
    public static void reload() {
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
            loadClip(file, name);
        }
    }

    private static void loadClip(Path file, String name) {
        closeAndRemove(name);
        try {
            Path playablePath = name.toLowerCase(Locale.US).endsWith(MP3_EXTENSION)
                    ? Mp3Converter.convertToWav(file, CACHE_FOLDER)
                    : file;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(playablePath.toFile())) {
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                clips.put(name, clip);
                applyVolumeTo(clip);
                if (GifPlayerConfig.getInstance().isAudioEnabled()) {
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
        for (Clip clip : clips.values()) {
            if (enabled) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            } else {
                clip.stop();
            }
        }
    }

    public static void applyVolume() {
        for (Clip clip : clips.values()) {
            applyVolumeTo(clip);
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
        Clip clip = clips.remove(name);
        if (clip != null) {
            clip.stop();
            clip.close();
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
