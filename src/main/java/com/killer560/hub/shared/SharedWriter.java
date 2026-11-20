package com.killer560.hub.shared;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One background thread for shared-store saves whose callers sit on the client thread (a chat line, a tick). A
 * {@link SharedRecords#save} takes an OS file lock (up to 10 s when another game holds it) and reads, merges and
 * writes the file, which is a stall if the client thread does it. The caller builds its JSON snapshot on its own thread
 * and hands the save here; saves run in submit order, and {@link #drain} (also run at JVM exit) waits for them.
 */
public final class SharedWriter {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-shared-writer");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> drain(4000), "killer560smod-shared-writer-exit"));
    }

    private SharedWriter() {
    }

    public static void submit(Runnable save) {
        try {
            EXEC.execute(save);
        } catch (RuntimeException e) {
            save.run();
        }
    }

    /**
     * Writes a small text file off the calling thread: the caller builds the string (a consistent snapshot) and the
     * folder creation and write run here, in submit order, so a later snapshot is never overwritten by an earlier one.
     * A tick or event handler must never touch the disk itself: one stalled write froze the game for 22 s (freezefix-1120).
     */
    public static void writeFile(java.nio.file.Path file, String text) {
        submit(() -> {
            try {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, text, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                com.killer560.hub.util.ModLog.get("killer560smod-shared").warn("[SharedWriter] Couldn't write {}: {}", file.getFileName(), e.toString());
            }
        });
    }

    /** Waits (at most the given milliseconds) until every save queued so far has been written. */
    public static void drain(long ms) {
        try {
            EXEC.submit(() -> { }).get(ms, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }
}
