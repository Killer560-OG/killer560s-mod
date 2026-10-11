package com.killer560.hub.autoupdate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Auto Update's installer: a separate tiny Java process, started as the game closes, that waits for the game to exit
 * and then swaps the mod jar in {@code mods/}.
 *
 * <p>STANDALONE ON PURPOSE. {@link AutoUpdateFeature} copies only this one class file out of the jar into the staging
 * folder and runs it from there with the game's own java binary - running it from the mod jar would hold that jar
 * open, and on Windows an open jar cannot be deleted. So it may use the JDK and nothing else: no other mod class, no
 * Minecraft, no Fabric, and no nested, anonymous or inner classes (each would be a second class file). Lambdas are
 * fine. It lives outside every mixin package (a helper class in one crashes the game at boot).
 *
 * <p>Order, chosen so that {@code mods/} never holds two copies of the mod (Fabric refuses to start on a duplicate
 * mod id) and never holds none for longer than one rename:
 * <ol>
 *   <li>wait for the parent PID to exit (30 minutes at most; still alive means do nothing);</li>
 *   <li>copy the staged jar into {@code mods/<name>.part} - Fabric loads only {@code *.jar}, so this is inert;</li>
 *   <li>move the old jar OUT of mods/ to {@code previous.jar.bak} in the staging folder, retrying for 30 s in case
 *       something still holds it (if it never moves: delete the .part and stop, leaving the old jar alone);</li>
 *   <li>rename the .part to its jar name (if that fails, put the old jar back);</li>
 *   <li>rename staged.json to installed.json, which the next launch reads for its "Updated to" popup.</li>
 * </ol>
 *
 * <p>Arguments: {@code <parent pid> <old jar> <staged new jar> <staging dir>}. Log: {@code swap.log} in the staging
 * folder. Exit 0 = swapped, anything else = nothing changed in mods/.
 */
public final class SwapHelper {

    private static Path log;

    private SwapHelper() {
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("usage: SwapHelper <parent pid> <old jar> <new jar> <staging dir>");
            System.exit(64);
            return;
        }
        Path staging = Path.of(args[3]).toAbsolutePath();
        log = staging.resolve("swap.log");
        int code;
        try {
            code = run(Long.parseLong(args[0]), Path.of(args[1]).toAbsolutePath(), Path.of(args[2]).toAbsolutePath(),
                    staging);
        } catch (Throwable t) {
            log("failed: " + t);
            code = 70;
        }
        log("exit " + code);
        System.exit(code);
    }

    static int run(long parentPid, Path oldJar, Path newJar, Path staging) throws Exception {
        log("start: parent " + parentPid + ", old " + oldJar + ", new " + newJar);
        Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
        if (parent.isPresent() && parent.get().isAlive()) {
            log("waiting for parent " + parentPid + " to exit");
            // Polled rather than onExit(): onExit completes through the common ForkJoinPool, and a busy or blocked
            // pool would leave this waiting long after the game has gone.
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
            while (parent.get().isAlive()) {
                if (System.nanoTime() > deadline) {
                    log("parent still running after 30 minutes - nothing changed");
                    return 2;
                }
                Thread.sleep(250);
            }
        }
        log("parent " + parentPid + " has exited");

        Path mods = oldJar.getParent();
        String newName = newJar.getFileName().toString();
        Path dest = mods.resolve(newName);
        if (!Files.isRegularFile(newJar)) {
            log("staged jar missing - nothing changed");
            return 3;
        }
        if (!Files.isRegularFile(oldJar)) {
            log("old jar missing - nothing changed");
            return 3;
        }
        if (!dest.equals(oldJar) && Files.exists(dest)) {
            log(dest + " already exists and is not the old jar - nothing changed");
            return 3;
        }

        Path part = mods.resolve(newName + ".part");
        Files.copy(newJar, part, StandardCopyOption.REPLACE_EXISTING);

        Path backup = staging.resolve("previous.jar.bak");
        boolean moved = false;
        for (int i = 0; i < 60 && !moved; i++) {
            try {
                Files.move(oldJar, backup, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (IOException e) {
                if (i == 0) {
                    log("old jar busy (" + e + "), retrying for 30 s");
                }
                Thread.sleep(500);
            }
        }
        if (!moved) {
            Files.deleteIfExists(part);
            log("could not move the old jar out of mods/ - nothing changed");
            return 4;
        }

        try {
            try {
                Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(part, dest);
            }
        } catch (IOException e) {
            log("could not put the new jar in place (" + e + ") - restoring the old jar");
            Files.deleteIfExists(dest);
            Files.move(backup, oldJar, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(part);
            return 5;
        }
        log("swapped: " + oldJar.getFileName() + " -> " + newName);

        Files.deleteIfExists(newJar);
        Path staged = staging.resolve("staged.json");
        if (Files.exists(staged)) {
            Files.move(staged, staging.resolve("installed.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "killer560smod*.jar")) {
            for (Path p : ds) {
                count++;
            }
        }
        log("mod jars in mods/ now: " + count);
        return 0;
    }

    private static void log(String line) {
        String s = LocalDateTime.now() + " [SwapHelper] " + line + System.lineSeparator();
        System.out.print(s);
        try {
            if (log != null) {
                Files.writeString(log, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
        }
    }
}
