package com.killer560.hub.sessionrecorder;

import com.killer560.hub.autoupdate.BuildInfo;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SessionRecorderSink;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The Session Recorder (killer560, 2026-10-19): while it is switched on, the lines the dungeon features already log
 * (Auto Routes, Auto Puzzles, Breaker and Breaker Aura, the auras and Arrow Align, Blood Camp, the map, see
 * {@link SessionRecorderSink#SOURCES}) are written to one file per session,
 * {@code config/killer560/diagnostics/killer560smod-sessionlog-<date>_<time>.log}, so he can send it back for
 * analysis. It is a recorder only: it never plays, clicks, moves or logs in anything, and adds no game hook of its
 * own beyond a client tick counter and the "Recording" HUD tag.
 *
 * <p>A session starts when the switch goes on (or the game starts with it on), and ends when it goes off or the game
 * closes. A file stops growing at 20 MB, with one last line saying it was cut.
 *
 * <p>It never runs in his dungeon instance: "It should only be on my instance. Do not put it in my dungeon instance.
 * The rest can have it." deploy-to-instances.py writes {@link #DISABLED_MARKER} into that instance's
 * {@code config/killer560/} on every deploy, and the recorder refuses to start wherever that marker exists, whatever
 * the setting (or an applied profile) says.
 */
public final class SessionRecorder {

    /** Its presence in {@code config/killer560/} (or {@code config/}, or the diagnostics folder) blocks the recorder. */
    public static final String DISABLED_MARKER = "killer560smod-session-recorder-disabled";

    private static final Logger LOGGER = ModLog.get("killer560smod-sessionrecorder");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static boolean registered;
    /** The setting as last seen by {@link #tick}: a session starts on its rising edge and ends on its falling one. */
    private static boolean lastWanted;
    private static int flushCountdown;

    private SessionRecorder() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SessionRecorder.tick", SessionRecorder::tick));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SessionRecorderSink.close("game closed"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> SessionRecorderSink.close("JVM shutdown"),
                "killer560smod-sessionrecorder-exit"));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SessionRecorderSink.note("world",
                "joined " + (client.getSingleplayerServer() != null ? "a singleplayer world (sim)" : "a server")));
        // Runs on the Netty thread; the sink is thread-safe and this touches nothing in the game.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SessionRecorderSink.note("world", "left"));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "session_recorder"),
                (graphics, deltaTracker) -> drawStatus(graphics));
    }

    /** True when this instance carries {@link #DISABLED_MARKER} - the recorder will not start here. */
    public static boolean blockedHere() {
        try {
            Path root = ModPaths.root();
            if (Files.exists(root.resolve(DISABLED_MARKER))
                    || Files.exists(root.resolve(ModPaths.folderFor(DISABLED_MARKER)).resolve(DISABLED_MARKER))) {
                return true;
            }
            Path configDir = root.getParent();
            return configDir != null && Files.exists(configDir.resolve(DISABLED_MARKER));
        } catch (Exception e) {
            // Cannot tell: treat as blocked, the safe side of his rule.
            return true;
        }
    }

    /**
     * True when the switch is on, the instance allows it, and yet no session is open and none was cut at 20 MB: the file
     * could not be opened, or a write failed and closed it. Lets the tab say so instead of "Starting on the next tick".
     */
    public static boolean failed() {
        return lastWanted && !SessionRecorderSink.active() && !SessionRecorderSink.wasCut() && !blockedHere();
    }

    private static void tick(Minecraft client) {
        SessionRecorderSink.advanceTick();
        boolean wanted = SessionRecorderConfig.getInstance().isEnabled();
        if (wanted != lastWanted) {
            lastWanted = wanted;
            if (wanted) {
                start();
            } else {
                stop();
            }
        }
        if (--flushCountdown <= 0) {
            flushCountdown = 20;
            SessionRecorderSink.flush();
        }
    }

    private static void start() {
        if (blockedHere()) {
            LOGGER.info("[SessionRecorder] not started: {} is in this instance", DISABLED_MARKER);
            ModChat.send("Session Recorder", ModChat.bad("Not started: recording is disabled in this instance."));
            return;
        }
        String name = "killer560smod-sessionlog-" + LocalDateTime.now().format(FILE_TIME) + ".log";
        Path file = ModPaths.root().resolve(ModPaths.folderFor(name)).resolve(name);
        String header = "mod " + modVersion() + ", minecraft " + BuildInfo.minecraft() + ", " + BuildInfo.variant()
                + ", commit " + BuildInfo.shortCommit();
        if (!SessionRecorderSink.open(file, header)) {
            LOGGER.warn("[SessionRecorder] could not open {}", file);
            ModChat.send("Session Recorder", ModChat.bad("Could not open the session file."));
            return;
        }
        LOGGER.info("[SessionRecorder] recording to {}", file);
        ModChat.send("Session Recorder", ModChat.text("Recording to "), ModChat.value(name));
    }

    private static void stop() {
        Path file = SessionRecorderSink.file();
        boolean wasOpen = SessionRecorderSink.active();
        SessionRecorderSink.close("switched off");
        if (wasOpen && file != null) {
            LOGGER.info("[SessionRecorder] stopped: {} ({} lines)", file, SessionRecorderSink.linesWritten());
            ModChat.send("Session Recorder", ModChat.text("Stopped. "),
                    ModChat.value(SessionRecorderSink.linesWritten() + " lines"), ModChat.text(" in "),
                    ModChat.value(file.getFileName().toString()));
        }
    }

    /** "Recording" in the top right while a session is open; "Recording cut at 20 MB" once the cap stopped it. */
    private static void drawStatus(GuiGraphicsExtractor graphics) {
        boolean recording = SessionRecorderSink.active();
        boolean cut = !recording && lastWanted && SessionRecorderSink.wasCut();
        if (!recording && !cut) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String label = recording ? "● Recording" : "Recording cut at 20 MB";
        int x = graphics.guiWidth() - font.width(label) - 4;
        graphics.text(font, label, x, 4, 0xFF000000 | (recording ? ModChat.ORANGE : ModChat.BAD), true);
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("killer560smod")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
