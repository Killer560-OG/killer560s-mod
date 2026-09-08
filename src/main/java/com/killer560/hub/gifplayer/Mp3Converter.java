package com.killer560.hub.gifplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts an .mp3 file to a plain PCM .wav file, so the rest of {@link GifAudioFeature} only ever
 * has to deal with {@code javax.sound.sampled.Clip}, which has no native MP3 support at all. Decoding
 * is done by mp3spi/jlayer/tritonus-share (bundled Jar-in-Jar in build.gradle, since the JDK ships
 * zero MP3 support on its own) - they register real {@code javax.sound.sampled.spi} providers, so
 * {@link AudioSystem#getAudioInputStream(java.io.File)} works directly on an .mp3 file once they're
 * on the classpath, no manual MP3 frame parsing needed here.
 */
public final class Mp3Converter {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-gifplayer-mp3");

    /** Converts {@code mp3File} to a .wav with the same base name under {@code cacheDir}, overwriting
     *  any previous conversion, and returns the resulting path. */
    public static Path convertToWav(Path mp3File, Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir);
        String base = mp3File.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        Path outFile = cacheDir.resolve(stem + ".wav");

        try (AudioInputStream mp3Stream = AudioSystem.getAudioInputStream(mp3File.toFile())) {
            AudioFormat source = mp3Stream.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(),
                    16,
                    source.getChannels(),
                    source.getChannels() * 2,
                    source.getSampleRate(),
                    false);
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, mp3Stream)) {
                AudioSystem.write(pcmStream, AudioFileFormat.Type.WAVE, outFile.toFile());
            }
        } catch (Exception e) {
            throw new IOException("Failed to convert " + mp3File.getFileName() + " to WAV", e);
        }

        LOGGER.info("Converted {} -> {}", mp3File.getFileName(), outFile.getFileName());
        return outFile;
    }

    private Mp3Converter() {
    }
}
