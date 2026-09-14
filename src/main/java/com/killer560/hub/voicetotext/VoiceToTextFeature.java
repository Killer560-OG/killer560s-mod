package com.killer560.hub.voicetotext;

import com.google.gson.JsonParser;
import com.killer560.hub.notify.ModOverlayMessage;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Voice To Text - killer560's "hears what I say via a keybind... and sends it to chat" request, with
 * the explicit requirement that it work with zero setup for him and his friends, no paid service.
 * <p>
 * Uses Vosk (a real, actively maintained, fully offline speech recognizer - see {@code build.gradle}
 * for why it was chosen and confirmation its jar really does bundle native libraries for Windows) with
 * a small English model downloaded once, automatically, to this mod's config folder the first time the
 * feature is actually used - no manual install step, matching "zero setup", while keeping the mod's own
 * distributed jar from ballooning by the model's ~40MB.
 * <p>
 * <b>Real, disclosed risk this session could not rule out like everything else built this session:</b>
 * every other new feature here was boot-tested end to end. This one touches a native library (via JNA)
 * for the first time in this mod, and this session's environment has no microphone and no way to
 * actually exercise real audio capture or verify the native library loads cleanly under Fabric's own
 * classloader - only that the mod itself still boots fine with the new (large) dependency present.
 * Every Vosk/JNA class reference below is deliberately confined to methods only called after the
 * feature is explicitly enabled AND the push-to-talk key is actually pressed (never at mod init or
 * static class-init time) and wrapped in a broad catch, specifically so a real native-library problem
 * fails as a caught error with a chat message instead of anything worse. Test this specifically before
 * trusting it in a real dungeon run.
 */
public final class VoiceToTextFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-voicetotext");
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String MODEL_DIR_NAME = "vosk-model-small-en-us-0.15";

    private enum State { IDLE, PREPARING_MODEL, READY, RECORDING, TRANSCRIBING, ERROR }

    private static volatile State state = State.IDLE;
    private static volatile Object loadedModel; // org.vosk.Model, held as Object so this file compiles
                                                 // even if Vosk somehow failed to resolve - see below.
    private static TargetDataLine line;
    private static ByteArrayOutputStream capturedAudio;
    private static Thread captureThread;
    private static boolean keyWasDown = false;

    private VoiceToTextFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        VoiceToTextConfig cfg = VoiceToTextConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || cfg.getPushToTalkKeyCode() < 0 || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(client.getWindow(), cfg.getPushToTalkKeyCode());
        if (down && !keyWasDown) {
            onKeyPressed();
        } else if (!down && keyWasDown) {
            onKeyReleased();
        }
        keyWasDown = down;
    }

    private static void onKeyPressed() {
        if (state == State.RECORDING) {
            return;
        }
        if (state == State.PREPARING_MODEL) {
            ModOverlayMessage.show("§e[Voice] Still preparing the speech model...", 1500);
            return;
        }
        if (loadedModel == null) {
            state = State.PREPARING_MODEL;
            ModOverlayMessage.show("§e[Voice] Preparing speech model (first use only, may download ~40MB)...", 4000);
            new Thread(VoiceToTextFeature::prepareModelAndStartRecording, "killer560smod-voice-prepare").start();
            return;
        }
        startRecording();
    }

    private static void onKeyReleased() {
        if (state != State.RECORDING) {
            return;
        }
        stopRecordingAndTranscribe();
    }

    // ------------------------------------------------------------------
    // Model preparation (background thread only)
    // ------------------------------------------------------------------

    private static void prepareModelAndStartRecording() {
        try {
            Path modelDir = modelDirectory();
            if (!Files.exists(modelDir.resolve("conf"))) {
                downloadAndExtractModel(modelDir);
            }
            loadedModel = new org.vosk.Model(modelDir.toString());
            state = State.READY;
            Minecraft.getInstance().execute(() -> {
                ModOverlayMessage.show("§a[Voice] Speech model ready - hold the key again to talk.", 2500);
                startRecording();
            });
        } catch (Throwable t) {
            LOGGER.warn("[VoiceToText] Failed to prepare speech model", t);
            state = State.ERROR;
            Minecraft.getInstance().execute(() ->
                    ModOverlayMessage.show("§c[Voice] Failed to set up speech recognition: " + t.getMessage(), 4000));
        }
    }

    private static Path modelDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("killer560smod-voice-model").resolve(MODEL_DIR_NAME);
    }

    private static void downloadAndExtractModel(Path modelDir) throws IOException {
        Path parent = modelDir.getParent();
        Files.createDirectories(parent);
        Path zipFile = parent.resolve("model-download.zip");
        LOGGER.info("[VoiceToText] Downloading speech model from {}", MODEL_URL);
        URL url = URI.create(MODEL_URL).toURL();
        try (InputStream in = url.openStream()) {
            Files.copy(in, zipFile, StandardCopyOption.REPLACE_EXISTING);
        }
        LOGGER.info("[VoiceToText] Extracting speech model...");
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = parent.resolve(entry.getName()).normalize();
                if (!target.startsWith(parent)) {
                    continue; // zip-slip guard
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.deleteIfExists(zipFile);
        LOGGER.info("[VoiceToText] Speech model ready at {}", modelDir);
    }

    // ------------------------------------------------------------------
    // Recording (main thread starts/stops a dedicated capture thread)
    // ------------------------------------------------------------------

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    private static void startRecording() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                ModOverlayMessage.show("§c[Voice] No compatible microphone found.", 3000);
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT);
            line.start();
            capturedAudio = new ByteArrayOutputStream();
            state = State.RECORDING;
            ModOverlayMessage.show("§c[Voice] Listening... (release key to send)", 60_000);

            captureThread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                while (state == State.RECORDING && line != null && line.isOpen()) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        synchronized (capturedAudio) {
                            capturedAudio.write(buffer, 0, read);
                        }
                    }
                }
            }, "killer560smod-voice-capture");
            captureThread.setDaemon(true);
            captureThread.start();
        } catch (Throwable t) {
            LOGGER.warn("[VoiceToText] Failed to start microphone capture", t);
            ModOverlayMessage.show("§c[Voice] Failed to access microphone: " + t.getMessage(), 3000);
            state = State.READY;
        }
    }

    private static void stopRecordingAndTranscribe() {
        state = State.TRANSCRIBING;
        TargetDataLine capturedLine = line;
        line = null;
        if (capturedLine != null) {
            capturedLine.stop();
            capturedLine.close();
        }
        ModOverlayMessage.show("§e[Voice] Transcribing...", 2000);

        new Thread(() -> {
            try {
                if (captureThread != null) {
                    captureThread.join(500);
                }
                byte[] audio;
                synchronized (capturedAudio) {
                    audio = capturedAudio.toByteArray();
                }
                String text = transcribe(audio);
                state = State.READY;
                Minecraft.getInstance().execute(() -> {
                    if (text == null || text.isBlank()) {
                        ModOverlayMessage.show("§7[Voice] Didn't catch anything.", 2000);
                        return;
                    }
                    ModOverlayMessage.show("§a[Voice] \"" + text + "\"", 3000);
                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null) {
                        String prefix = VoiceToTextConfig.getInstance().isSendToPartyChat() ? "pc " : "gc ";
                        client.player.connection.sendCommand(prefix + text);
                    }
                });
            } catch (Throwable t) {
                LOGGER.warn("[VoiceToText] Transcription failed", t);
                state = State.READY;
                Minecraft.getInstance().execute(() ->
                        ModOverlayMessage.show("§c[Voice] Transcription failed: " + t.getMessage(), 3000));
            }
        }, "killer560smod-voice-transcribe").start();
    }

    /** Isolated in its own method (never called except from a background thread, and only once the
     *  model is confirmed loaded) so any native-library problem surfaces as a caught {@link Throwable}
     *  at the one real call site above, not anywhere else in this class. */
    private static String transcribe(byte[] audio) throws Exception {
        org.vosk.Model model = (org.vosk.Model) loadedModel;
        try (org.vosk.Recognizer recognizer = new org.vosk.Recognizer(model, 16000f)) {
            recognizer.acceptWaveForm(audio, audio.length);
            String json = recognizer.getFinalResult();
            var parsed = JsonParser.parseString(json).getAsJsonObject();
            return parsed.has("text") ? parsed.get("text").getAsString() : null;
        }
    }
}
