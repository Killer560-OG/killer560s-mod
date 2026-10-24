package com.killer560.hub.routes;

import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Writes a small JSON file off the render thread. The caller serializes on its own thread (so the data is a
 *  snapshot), the disk write runs on one shared daemon thread, and a burst of saves (a slider drag, typing a
 *  name) collapses into the newest one. {@link #flush()} waits for queued writes, so a load that follows a save
 *  never reads a stale file. */
public final class AsyncFileWriter {

    private static final Logger LOGGER = ModLog.get("killer560smod-routes");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-waypointroutes-io");
        t.setDaemon(true);
        return t;
    });

    private final Path path;
    private final AtomicReference<String> pending = new AtomicReference<>();

    AsyncFileWriter(Path path) {
        this.path = path;
    }

    void write(String content) {
        pending.set(content);
        try {
            IO.execute(this::drain);
        } catch (RuntimeException e) {
            drain();
        }
    }

    private void drain() {
        String content = pending.getAndSet(null);
        if (content == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[WaypointRoutes] Failed to write {}", path.getFileName(), e);
        }
    }

    /** Blocks (at most two seconds) until every write queued so far has reached the disk. Profiles call it before
     *  copying live files, so a queued save is neither missed by the profile being left nor written over the one
     *  being applied, and it lands before the game quits. */
    public static void flush() {
        try {
            IO.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
