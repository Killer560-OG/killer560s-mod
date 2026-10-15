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
                return decode(in);
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

    /** Forget a file's decoded copy (it was just replaced). */
    static void forget(Path file) {
        CACHE.remove(file);
    }
}
