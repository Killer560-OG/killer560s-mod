package com.killer560.hub.autoupdate;

import com.google.gson.JsonObject;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Auto Update (killer560, 2026-10-11). On launch, off the render thread, {@link AutoUpdater} asks GitHub for the
 * channel's release and stages the jar matching this one's Minecraft version and variant; a chat line says it is
 * ready; when the game closes, {@link SwapHelper} (a separate process) waits for it to exit and swaps the jar; the
 * next launch shows an "Updated to" popup with the release notes.
 *
 * <p>Never runs in a {@code -PtestingBuild} jar, in an instance deploy-to-instances.py manages (its marker file), with
 * the channel Off, in a jar without a build commit, or when the mod is not a jar directly in this game's
 * {@code mods/} folder (a dev run, or the testkit's {@code fabric.addMods}) - there would be nothing safe to replace.
 * The variant and Minecraft version are never changed: the asset is picked from this jar's own.
 */
public final class AutoUpdateFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoupdate");
    public static final String MOD_ID = "killer560smod";

    private static final AtomicBoolean checking = new AtomicBoolean();
    private static volatile AutoUpdater.Outcome lastOutcome;
    private static volatile String pendingChat;
    private static volatile JsonObject pendingPopup;
    private static boolean registered;

    private AutoUpdateFeature() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        if (BuildVariant.TESTING) {
            return;
        }
        readInstalledNote();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> flushChat());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> installOnExit());
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            JsonObject popup = pendingPopup;
            if (popup != null && screen instanceof TitleScreen) {
                pendingPopup = null;
                client.execute(() -> McCompat.setScreen(client, new UpdatedScreen(screen,
                        AutoUpdater.shortOf(AutoUpdater.str(popup, "commit")), AutoUpdater.str(popup, "tag"),
                        AutoUpdater.str(popup, "notes"))));
            }
        });
        String gate = launchGate();
        if (gate != null) {
            LOGGER.info("[AutoUpdate] not checking: {}", gate);
            return;
        }
        checkAsync(null);
    }

    // ---- state the menu shows -------------------------------------------------------------------------------------

    public static boolean isChecking() {
        return checking.get();
    }

    public static AutoUpdater.Outcome lastOutcome() {
        return lastOutcome;
    }

    // ---- gates ---------------------------------------------------------------------------------------------------

    static AutoUpdater.Request currentRequest() {
        return new AutoUpdater.Request(AutoUpdateConfig.getInstance().getChannel(), BuildVariant.TESTING,
                FabricLoader.getInstance().getGameDir(), BuildInfo.minecraft(), BuildInfo.variant(),
                BuildInfo.commit(), AutoUpdateConfig.stagingDir());
    }

    /** Why this game must not auto-update, or {@code null} when it may. Touches no network. */
    public static String launchGate() {
        String reason = AutoUpdater.blockedReason(currentRequest());
        if (reason != null) {
            return reason;
        }
        Path jar = installedJar();
        if (jar == null) {
            return "the mod is not a jar in this game's mods folder (a dev or test run)";
        }
        int copies = modJarsIn(jar.getParent()).size();
        if (copies != 1) {
            return copies + " killer560smod jars in mods/ - sort that out by hand";
        }
        return null;
    }

    /** This mod's own jar, when it is a regular {@code .jar} directly inside {@code <game dir>/mods}; else null. */
    public static Path installedJar() {
        try {
            var container = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
            if (container == null) {
                return null;
            }
            List<Path> paths = container.getOrigin().getPaths();
            if (paths.size() != 1) {
                return null;
            }
            Path jar = paths.get(0).toAbsolutePath().normalize();
            Path mods = FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")
                    || !mods.equals(jar.getParent())) {
                return null;
            }
            return jar;
        } catch (Throwable t) {
            // getOrigin() throws for a mod with no origin (a dev classpath).
            return null;
        }
    }

    static List<Path> modJarsIn(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "killer560smod*.jar")) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- checking ------------------------------------------------------------------------------------------------

    /**
     * Runs a check on its own thread ("Check Now" and the launch check). {@code callback}, when given, runs on the
     * render thread with the outcome. Gated exactly like the launch check.
     */
    public static void checkAsync(Consumer<AutoUpdater.Outcome> callback) {
        if (!checking.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            AutoUpdater.Outcome o;
            try {
                String gate = launchGate();
                o = gate != null ? AutoUpdater.Outcome.of(AutoUpdater.Status.BLOCKED, gate)
                        : AutoUpdater.check(currentRequest());
            } catch (Throwable e) {
                o = AutoUpdater.Outcome.of(AutoUpdater.Status.ERROR, e.toString());
            }
            lastOutcome = o;
            checking.set(false);
            LOGGER.info("[AutoUpdate] {}", o);
            if (o.status() == AutoUpdater.Status.STAGED || o.status() == AutoUpdater.Status.ALREADY_STAGED) {
                pendingChat = AutoUpdater.shortOf(o.remoteCommit()) + " ("
                        + AutoUpdateConfig.getInstance().getChannel().label + ")";
                Minecraft.getInstance().execute(AutoUpdateFeature::flushChat);
            }
            if (callback != null) {
                AutoUpdater.Outcome done = o;
                Minecraft.getInstance().execute(() -> callback.accept(done));
            }
        }, "killer560smod-auto-update");
        t.setDaemon(true);
        t.start();
    }

    private static void flushChat() {
        String what = pendingChat;
        Minecraft client = Minecraft.getInstance();
        if (what == null || client.player == null) {
            return;
        }
        pendingChat = null;
        ModChat.send("Auto Update", ModChat.text("Update "), ModChat.value(what),
                ModChat.text(" is ready. It installs when you close the game."));
    }

    // ---- installing ----------------------------------------------------------------------------------------------

    /** CLIENT_STOPPING: hands a verified staged jar to {@link SwapHelper}. Never throws. */
    static void installOnExit() {
        try {
            String gate = launchGate();
            if (gate != null) {
                return;
            }
            Path staging = AutoUpdateConfig.stagingDir();
            JsonObject staged = AutoUpdater.readJson(staging.resolve(AutoUpdater.STAGED_FILE));
            String asset = AutoUpdater.str(staged, "asset");
            if (asset == null || AutoUpdater.sameCommit(AutoUpdater.str(staged, "commit"), BuildInfo.commit())) {
                return;
            }
            Path newJar = staging.resolve(asset);
            String sha = AutoUpdater.sha256(newJar);
            if (sha == null || !sha.equals(AutoUpdater.str(staged, "sha256"))
                    || !AutoUpdater.assetMatches(asset, BuildInfo.minecraft(), BuildInfo.variant())) {
                LOGGER.warn("[AutoUpdate] staged {} failed its re-check - not installing", asset);
                return;
            }
            long pid = spawnHelper(ProcessHandle.current().pid(), installedJar().toString(), newJar.toString(),
                    staging.toString());
            LOGGER.info("[AutoUpdate] installer started (pid {}) for {}", pid, asset);
        } catch (Throwable t) {
            LOGGER.error("[AutoUpdate] could not start the installer", t);
        }
    }

    /**
     * Starts {@link SwapHelper} for {@code parentPid} with this game's java binary, from a copy of its one class file
     * in {@code <staging>/helper} (never from the mod jar, which it is about to delete). Returns the helper's PID.
     * Public for the testkit, which runs it against a dummy parent process.
     */
    public static long spawnHelper(long parentPid, String oldJar, String newJar, String stagingDir) throws Exception {
        Path staging = Path.of(stagingDir);
        long pid = spawnStandalone(SwapHelper.class, staging.resolve("helper"), staging.resolve("swap-helper-out.log"),
                List.of(Long.toString(parentPid), oldJar, newJar, stagingDir));
        try {
            // /reboot's relauncher (reboot/RebootHelper) reads this to wait for the swap before starting the game.
            Files.writeString(staging.resolve(INSTALLER_PID_FILE), Long.toString(pid));
        } catch (Exception e) {
            LOGGER.warn("[AutoUpdate] could not write {}: {}", INSTALLER_PID_FILE, e.toString());
        }
        return pid;
    }

    /** The file {@link #spawnHelper} writes the installer's PID to, in the staging folder. */
    public static final String INSTALLER_PID_FILE = "installer.pid";

    /**
     * Runs a standalone one-class helper ({@link SwapHelper}, {@code reboot/RebootHelper}) as its own process with this
     * game's java binary, from a copy of its class file in {@code classDir} (never from the mod jar, which Auto Update
     * may be about to delete). Its output goes to {@code outLog}. Returns the helper's PID.
     */
    public static long spawnStandalone(Class<?> helper, Path classDir, Path outLog, List<String> args) throws Exception {
        String rel = helper.getName().replace('.', '/') + ".class";
        Path classFile = classDir.resolve(rel);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, helperClassBytes(helper, rel));
        List<String> cmd = new ArrayList<>(List.of(javaBinary(), "-cp", classDir.toString(), helper.getName()));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Files.createDirectories(outLog.toAbsolutePath().getParent());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outLog.toFile()));
        return pb.start().pid();
    }

    private static byte[] helperClassBytes(Class<?> helper, String rel) throws Exception {
        try (InputStream in = helper.getResourceAsStream("/" + rel)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        // Fallback: read it out of the mod's own jar.
        var container = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
        for (Path p : container.getOrigin().getPaths()) {
            if (Files.isRegularFile(p)) {
                try (ZipFile z = new ZipFile(p.toFile())) {
                    ZipEntry e = z.getEntry(rel);
                    if (e != null) {
                        try (InputStream in = z.getInputStream(e)) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("cannot find " + rel);
    }

    /** This game's java, preferring javaw.exe on Windows so the helper opens no console window. */
    static String javaBinary() {
        String cmd = ProcessHandle.current().info().command().orElse(null);
        Path bin = cmd != null ? Path.of(cmd).getParent()
                : Path.of(System.getProperty("java.home"), "bin");
        for (String name : new String[]{"javaw.exe", "java.exe", "java"}) {
            Path p = bin.resolve(name);
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return cmd != null ? cmd : "java";
    }

    // ---- after an install ----------------------------------------------------------------------------------------

    /** installed.json, written by SwapHelper: shown once as a popup when it names this jar's commit. */
    private static void readInstalledNote() {
        try {
            Path staging = AutoUpdateConfig.stagingDir();
            Path installed = staging.resolve(AutoUpdater.INSTALLED_FILE);
            JsonObject note = AutoUpdater.readJson(installed);
            if (note != null) {
                Files.deleteIfExists(installed);
                if (AutoUpdater.sameCommit(AutoUpdater.str(note, "commit"), BuildInfo.commit())) {
                    pendingPopup = note;
                    LOGGER.info("[AutoUpdate] now on {}", BuildInfo.shortCommit());
                } else {
                    LOGGER.warn("[AutoUpdate] installed.json names {} but this jar is {} - the install did not take",
                            AutoUpdater.str(note, "commit"), BuildInfo.commit());
                }
            }
            // A staged copy of the jar that is already running is not an update.
            JsonObject staged = AutoUpdater.readJson(staging.resolve(AutoUpdater.STAGED_FILE));
            if (staged != null && AutoUpdater.sameCommit(AutoUpdater.str(staged, "commit"), BuildInfo.commit())) {
                AutoUpdater.clearStaging(staging);
            }
        } catch (Exception e) {
            LOGGER.warn("[AutoUpdate] could not read the install note: {}", e.toString());
        }
    }
}
