package com.killer560.hub.clicksound;

import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads imported click sounds for the sound engine. Each file is decoded once per modification time and kept: a
 * {@link SoundBuffer} owns an OpenAL buffer that is only freed when the engine clears its library, so decoding on every
 * click would leak one per click.
 */
public final class ClickSoundBuffers {

    private record Cached(long modified, CompletableFuture<SoundBuffer> buffer) {
    }

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private ClickSoundBuffers() {
    }

    /** True for the Sound path ({@code killer560smod:sounds/imported/<key>.ogg}) an imported click asks the engine for. */
    public static boolean isImported(Identifier id) {
        return id != null && ClickSounds.NAMESPACE.equals(id.getNamespace()) && id.getPath().startsWith("sounds/imported/");
    }

    /** The buffer for the engine's request for one of our imported sounds. */
    public static CompletableFuture<SoundBuffer> bufferFor(Identifier id) {
        String path = id.getPath(); // sounds/imported/<key>.ogg
        String key = path.substring("sounds/".length(), path.length() - (path.endsWith(".ogg") ? 4 : 0));
        Path file = ClickSounds.fileForKey(key);
        if (file == null) {
            return CompletableFuture.failedFuture(new IOException("unknown imported click sound " + key));
        }
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        Cached c = CACHE.get(file);
        if (c != null && c.modified == modified) {
            return c.buffer;
        }
        CompletableFuture<SoundBuffer> fresh = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = Files.newInputStream(file)) {
                return ClickSounds.extension(file.getFileName().toString()).equals("ogg")
                        ? decode(in) : decodeAudio(in, false);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        CACHE.put(file, new Cached(modified, fresh));
        return fresh;
    }

    /** Decodes an Ogg Vorbis stream the way the engine decodes a resource-pack sound. */
    static SoundBuffer decode(InputStream in) throws IOException {
        try (JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            FiniteAudioStream finite = stream;
            ByteBuffer data = finite.readAll();
            return new SoundBuffer(data, finite.getFormat());
        }
    }

    /** The length of an Ogg Vorbis stream in seconds (an import check: the stream is read whole, so a broken one throws). */
    static double oggSeconds(InputStream in) throws IOException {
        try (JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            FiniteAudioStream finite = stream;
            ByteBuffer data = finite.readAll();
            javax.sound.sampled.AudioFormat format = finite.getFormat();
            if (data.remaining() == 0) {
                throw new IOException("empty");
            }
            return data.remaining() / (double) (format.getFrameSize() * format.getSampleRate());
        }
    }

    /** Decodes a sound the JDK or the bundled MP3 decoder reads (wav, aiff, au, mp3) to 16-bit little-endian PCM, the form
     *  the sound engine's buffers take. {@code forImport}: refuse one longer than {@link ClickSounds#MAX_SECONDS}. */
    static SoundBuffer decodeAudio(InputStream in, boolean forImport) throws IOException {
        // The JDK finds the MP3 decoder through the thread's context class loader; the engine's loader threads (the common
        // pool) carry the system one, which cannot see a Jar-in-Jar library: "Stream of unsupported format".
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(ClickSoundBuffers.class.getClassLoader());
        try {
            return decodeAudio0(in, forImport);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static SoundBuffer decodeAudio0(InputStream in, boolean forImport) throws IOException {
        try (javax.sound.sampled.AudioInputStream raw = javax.sound.sampled.AudioSystem.getAudioInputStream(
                new java.io.BufferedInputStream(in))) {
            javax.sound.sampled.AudioFormat src = raw.getFormat();
            if (src.getChannels() < 1 || src.getChannels() > 2) {
                throw new IOException("only mono or stereo");
            }
            javax.sound.sampled.AudioFormat pcm = new javax.sound.sampled.AudioFormat(
                    javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            try (javax.sound.sampled.AudioInputStream converted = javax.sound.sampled.AudioSystem.getAudioInputStream(pcm, raw)) {
                long limit = (long) (pcm.getSampleRate() * pcm.getFrameSize() * ClickSounds.MAX_SECONDS);
                byte[] bytes = converted.readNBytes((int) limit + 1);
                if (bytes.length == 0) {
                    throw new IOException("empty");
                }
                if (bytes.length > limit) {
                    if (forImport) {
                        throw new IOException("over " + ClickSounds.MAX_SECONDS + " seconds");
                    }
                    bytes = java.util.Arrays.copyOf(bytes, (int) limit);
                }
                ByteBuffer data = ByteBuffer.allocateDirect(bytes.length);
                data.put(bytes).flip();
                return new SoundBuffer(data, pcm);
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException(e);
        }
    }

    /** Forget a file's decoded copy (it was just replaced). */
    static void forget(Path file) {
        CACHE.remove(file);
    }
}
