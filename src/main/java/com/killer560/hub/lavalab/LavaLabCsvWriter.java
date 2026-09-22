package com.killer560.hub.lavalab;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The disk half of one Lava Lab recording session: two CSVs (ticks + bounces) under
 * {@code config/killer560smod-lavalab/}. A SUBFOLDER, deliberately - same reasoning as
 * {@code runsummary/RunHistoryStore}'s {@code killer560smod-runs/}: this is recorded run data, not a
 * setting, so {@code ProfileManager} snapshotting/overwriting every {@code killer560smod-*.json} settings
 * file can never touch it.
 * <p>
 * All actual file IO happens on a single daemon writer thread, never the client thread - {@link
 * LavaLabFeature} only ever builds a CSV line (a String, cheap) and hands it to {@link #addTickLine} or
 * {@link #addBounceLine}, which queue it. Tick rows batch up to {@link #FLUSH_EVERY_ROWS} before a real
 * write goes out; bounce rows are the interesting ones so each is written straight away instead of waiting
 * on a batch. Same single-writer-thread shape as {@code RunHistoryStore}'s {@code WRITER}.
 */
final class LavaLabCsvWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-lavalab");
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-lavalab");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final AtomicInteger SEQ = new AtomicInteger();

    static final String TICK_HEADER = "tick,wallClockMs,x,y,z,vx,vy,vz,yaw,pitch,onGround,inLava,inWater,"
            + "boxIntersectsLava,fluidHeightAtFeet,sprinting,sneaking,keyForward,keyBack,keyLeft,keyRight,"
            + "keyJump,keySneak,blockAtFeet,blockBelow,movementSpeedAttr";
    static final String BOUNCE_HEADER = "bounceTick,wallClockMs,x,y,z,entryVerticalSpeed,"
            + "exitPeakVerticalVelocity,peakHeightGained,horizontalSpeedBefore,horizontalSpeedAfter,"
            + "pitchAtBounce,movementKeyHeldNearby";

    /** Hard ceiling on how many session file pairs Lava Lab keeps - a forgotten armed session on every
     *  dungeon run would otherwise grow this folder forever. Oldest pair deleted first. */
    private static final int MAX_SESSION_PAIRS = 100;
    private static final int FLUSH_EVERY_ROWS = 300;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-lavalab-writer");
        t.setDaemon(true);
        return t;
    });

    private final Path ticksPath;
    private final Path bouncesPath;
    private final List<String> pendingTickLines = new ArrayList<>();

    private LavaLabCsvWriter(Path ticksPath, Path bouncesPath) {
        this.ticksPath = ticksPath;
        this.bouncesPath = bouncesPath;
    }

    /** Opens a fresh session's two files (headers written immediately) and prunes old sessions beyond
     *  {@link #MAX_SESSION_PAIRS}. Returns right away - the actual file creation happens on the writer
     *  thread, same as every other method here. */
    static LavaLabCsvWriter startSession() {
        String stamp = STAMP.format(LocalDateTime.now()) + "-" + SEQ.incrementAndGet();
        Path ticks = DIR.resolve("lavalab-" + stamp + "-ticks.csv");
        Path bounces = DIR.resolve("lavalab-" + stamp + "-bounces.csv");
        LavaLabCsvWriter writer = new LavaLabCsvWriter(ticks, bounces);
        WRITER.submit(() -> {
            try {
                Files.createDirectories(DIR);
                Files.writeString(ticks, TICK_HEADER + "\n", StandardCharsets.UTF_8);
                Files.writeString(bounces, BOUNCE_HEADER + "\n", StandardCharsets.UTF_8);
                pruneOldSessions();
            } catch (Exception e) {
                LOGGER.warn("[LavaLab] Failed to start session files in {}", DIR, e);
            }
        });
        return writer;
    }

    /** Queues one tick's CSV line. Flushes to disk once {@link #FLUSH_EVERY_ROWS} have built up. */
    void addTickLine(String line) {
        pendingTickLines.add(line);
        if (pendingTickLines.size() >= FLUSH_EVERY_ROWS) {
            flushTicks();
        }
    }

    /** Queues one bounce row and writes it immediately - bounces are rare and are exactly the rows the
     *  data-fitting work will care about most, so none should be lost to an unflushed batch. */
    void addBounceLine(String line) {
        List<String> single = List.of(line);
        WRITER.submit(() -> appendLines(bouncesPath, single));
    }

    private void flushTicks() {
        if (pendingTickLines.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(pendingTickLines);
        pendingTickLines.clear();
        WRITER.submit(() -> appendLines(ticksPath, batch));
    }

    private static void appendLines(Path path, List<String> lines) {
        try {
            Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("[LavaLab] Failed to write {}", path.getFileName(), e);
        }
    }

    /** Session ended normally (auto tail expiry or {@code /lavalab off}) - flushes whatever is left. */
    void finish() {
        flushTicks();
    }

    /** {@code /lavalab clear} on an in-progress session: discards it instead of keeping a half-finished
     *  recording no one asked for. */
    void abortAndDelete() {
        pendingTickLines.clear();
        WRITER.submit(() -> {
            deleteQuietly(ticksPath);
            deleteQuietly(bouncesPath);
        });
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    Path ticksPath() {
        return ticksPath;
    }

    Path bouncesPath() {
        return bouncesPath;
    }

    /** Runs on the writer thread (called right after a new session's files are created), so it never
     *  competes with the client thread for anything. */
    private static void pruneOldSessions() {
        try {
            if (!Files.isDirectory(DIR)) {
                return;
            }
            List<Path> ticksFiles;
            try (var stream = Files.list(DIR)) {
                ticksFiles = stream.filter(p -> p.getFileName().toString().endsWith("-ticks.csv"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList());
            }
            while (ticksFiles.size() > MAX_SESSION_PAIRS) {
                Path oldest = ticksFiles.remove(0);
                deleteQuietly(oldest);
                String stampPart = oldest.getFileName().toString().replace("lavalab-", "").replace("-ticks.csv", "");
                deleteQuietly(DIR.resolve("lavalab-" + stampPart + "-bounces.csv"));
            }
        } catch (Exception e) {
            LOGGER.warn("[LavaLab] Failed to prune old sessions in {}", DIR, e);
        }
    }
}
