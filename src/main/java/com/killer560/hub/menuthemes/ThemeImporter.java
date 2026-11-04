package com.killer560.hub.menuthemes;

import com.killer560.hub.clicksound.ClickSounds;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** The import thread, status line and ffmpeg run that Theme Backgrounds and Theme Music share. */
final class ThemeImporter {

    /** The import itself; blocking, import thread only. */
    interface Job {
        String run(Path source) throws IOException;
    }

    private final ExecutorService executor;
    private final Logger logger;
    private final String tag;
    private final String what;
    private final String clipboardHint;
    private final Job job;
    private final Function<String, String> label;
    private volatile boolean importing;
    private volatile String lastImport;

    ThemeImporter(String threadName, Logger logger, String tag, String what, String clipboardHint, Job job,
                  Function<String, String> label) {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        this.logger = logger;
        this.tag = tag;
        this.what = what;
        this.clipboardHint = clipboardHint;
        this.job = job;
        this.label = label;
    }

    boolean importing() { return importing; }

    String lastImport() { return lastImport; }

    void importClipboardAsync(String clip, Consumer<String> done) {
        Path path;
        try {
            path = ClickSounds.clipboardPath(clip, clipboardHint);
        } catch (IOException e) {
            lastImport = "§c" + e.getMessage();
            done.accept(null);
            return;
        }
        importAsync(path, done);
    }

    void importAsync(Path source, Consumer<String> done) {
        if (importing) {
            lastImport = "§cAn import is already running.";
            done.accept(null);
            return;
        }
        importing = true;
        lastImport = "§7Importing " + source.getFileName() + "...";
        executor.execute(() -> {
            String id = null;
            try {
                id = job.run(source);
                lastImport = "§aImported " + label.apply(id) + " and set it as this theme's " + what + ".";
            } catch (IOException | RuntimeException e) {
                lastImport = "§c" + e.getMessage();
                logger.warn("[{}] import of {} failed: {}", tag, source, e.getMessage());
            } finally {
                importing = false;
            }
            done.accept(id);
        });
    }

    /** Starts ffmpeg with its output in {@code log} and waits up to ten minutes; the caller reads the exit value. */
    static Process runFfmpeg(List<String> command, Path log, String name, String notFoundTail) throws IOException {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new IOException(name + notFoundTail);
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg took too long on " + name + ".");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
        return p;
    }
}
