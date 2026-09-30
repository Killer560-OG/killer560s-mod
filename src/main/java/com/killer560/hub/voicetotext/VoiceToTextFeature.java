package com.killer560.hub.voicetotext;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.JsonParser;
import com.killer560.hub.notify.ModOverlayMessage;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.killer560.hub.compat.McCompat;

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
 * feature is explicitly enabled AND it actually starts listening - the push-to-talk key pressed, or (2026-09-27)
 * Open Mic arming itself - never at mod init or static class-init time, and wrapped in a broad catch,
 * specifically so a real native-library problem fails as a caught error with a chat message instead of
 * anything worse. Test this specifically before trusting it in a real dungeon run.
 */
public final class VoiceToTextFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-voicetotext");
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
    /** Open Mic only: whether this "enabled" session has already been armed - see {@link #tick()}. Reset
     *  whenever the feature/window drops out so turning it back on gets exactly one fresh attempt, same as
     *  the disclosed native-library risk above says to keep any failure a single caught error, not a loop. */
    private static boolean openMicArmed = false;

    private VoiceToTextFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("VoiceToTextFeature", client -> tick()));
    }

    private static void tick() {
        VoiceToTextConfig cfg = VoiceToTextConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        boolean pushToTalk = cfg.getMode() == VoiceToTextConfig.Mode.PUSH_TO_TALK;
        if (!cfg.isEnabled() || client.getWindow() == null || (pushToTalk && cfg.getPushToTalkKeyCode() < 0)) {
            // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to just
            // return here with no regard for an in-progress recording - disabling the feature, or
            // rebinding the push-to-talk key away, WHILE the key was physically still held down left the
            // microphone (TargetDataLine) open forever and the capture thread spinning forever, with no
            // way back to a working state short of restarting the game (onKeyPressed() itself no-ops
            // while state == RECORDING). Now stops recording the same way releasing the key normally
            // would, so the mic always gets closed regardless of why tick() stopped polling it.
            if (state == State.RECORDING) {
                stopRecordingAndTranscribe(false);
            }
            keyWasDown = false;
            openMicArmed = false;
            return;
        }
        if (!pushToTalk) {
            // Open Mic (killer560, 2026-09-27): listens continuously instead of waiting on a held key. Armed
            // once per "enabled" session (not every tick, which would spam a "no microphone" chat message
            // forever if startRecording() keeps failing) - see startRecording()'s capture thread for how one
            // utterance ends and the next one starts on its own via silence detection.
            if (!openMicArmed && state != State.RECORDING && state != State.TRANSCRIBING && state != State.PREPARING_MODEL) {
                openMicArmed = true;
                ModOverlayMessage.show("[Voice] Open Mic: listening...", 2000);
                onKeyPressed(); // reused: preps the speech model on first use, then starts recording
            }
            return;
        }
        openMicArmed = false;
        // Real bug (2026-09-20 tooltip/config sweep): the push-to-talk key was read from the raw window
        // state even with a screen open, so typing the bound letter into chat started recording and
        // then sent whatever the mic heard straight to /pc or /gc. A push-to-talk press only counts
        // with no screen open; opening one mid-recording reads as a release, so the mic closes and what
        // was already captured is transcribed, exactly as letting go of the key would.
        boolean down = McCompat.screen(client) == null
                && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getPushToTalkKeyCode());
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
            ModOverlayMessage.show("[Voice] Still preparing the speech model...", 1500);
            return;
        }
        if (loadedModel == null) {
            state = State.PREPARING_MODEL;
            ModOverlayMessage.show("[Voice] Preparing speech model (first use only, may download ~40MB)...", 4000);
            new Thread(VoiceToTextFeature::prepareModelAndStartRecording, "killer560smod-voice-prepare").start();
            return;
        }
        startRecording();
    }

    private static void onKeyReleased() {
        if (state != State.RECORDING) {
            return;
        }
        stopRecordingAndTranscribe(false);
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
                boolean openMic = VoiceToTextConfig.getInstance().getMode() == VoiceToTextConfig.Mode.OPEN_MIC;
                ModOverlayMessage.show(openMic ? "§a[Voice] Speech model ready - listening now."
                        : "§a[Voice] Speech model ready - hold the key again to talk.", 2500);
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

    /** Caps for the Vosk model archive (2026-09-16 security pass). The real small-English model is about
     *  50 MB zipped; these only ever fire on a hostile or broken response. */
    private static final long MAX_MODEL_ZIP_BYTES = 512L * 1024 * 1024;
    private static final long MAX_MODEL_EXTRACTED_BYTES = 1024L * 1024 * 1024;

    private static void downloadAndExtractModel(Path modelDir) throws IOException {
        Path parent = modelDir.getParent();
        Files.createDirectories(parent);
        Path zipFile = parent.resolve("model-download.zip");
        LOGGER.info("[VoiceToText] Downloading speech model from {}", MODEL_URL);
        java.net.URLConnection conn = URI.create(MODEL_URL).toURL().openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        try (InputStream in = conn.getInputStream()) {
            com.killer560.hub.util.BoundedDownload.toFile(in, zipFile, MAX_MODEL_ZIP_BYTES, "speech model download");
        }
        LOGGER.info("[VoiceToText] Extracting speech model...");
        long extracted = 0;
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
                    try (java.io.OutputStream out = Files.newOutputStream(target)) {
                        extracted += com.killer560.hub.util.BoundedDownload.copyCapped(zip, out,
                                MAX_MODEL_EXTRACTED_BYTES - extracted, "speech model extraction");
                    }
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

    /** Every input device that can record in {@link #FORMAT}, by its Java Sound mixer name (killer560, 2026-09-21:
     *  "for voice to text also allow me to select the microphone"). */
    public static List<String> microphones() {
        List<String> out = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mi).isLineSupported(info) && !out.contains(mi.getName())) {
                    out.add(mi.getName());
                }
            } catch (Exception ignored) {
                // a device that errors on query is just not offered
            }
        }
        return out;
    }

    /** The chosen microphone if it is still plugged in, otherwise the system default. */
    private static TargetDataLine openMicrophone(DataLine.Info info) throws Exception {
        String wanted = VoiceToTextConfig.getInstance().getMicrophone();
        if (!wanted.isEmpty()) {
            for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(wanted)) {
                    javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(info)) {
                        return (TargetDataLine) mixer.getLine(info);
                    }
                }
            }
            ModOverlayMessage.show("§e[Voice] \"" + wanted + "\" isn't available - using the default microphone.", 3000);
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    /** Open Mic silence detection (killer560, 2026-09-27): not a real voice-activity detector, just a cheap
     *  RMS-over-threshold check on each 4096-byte chunk - good enough to tell "someone's talking" from
     *  "background noise" without pulling in a whole VAD library for it. */
    private static final int VOICE_RMS_THRESHOLD = 500;
    private static final long SILENCE_CUTOFF_MS = 1200;
    private static final long MIN_UTTERANCE_MS = 400;

    private static void startRecording() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                ModOverlayMessage.show("§c[Voice] No compatible microphone found.", 3000);
                state = State.READY;
                return;
            }
            line = openMicrophone(info);
            line.open(FORMAT);
            line.start();
            capturedAudio = new ByteArrayOutputStream();
            state = State.RECORDING;
            boolean openMic = VoiceToTextConfig.getInstance().getMode() == VoiceToTextConfig.Mode.OPEN_MIC;
            if (!openMic) {
                ModOverlayMessage.show("[Voice] Listening... (release key to send)", 60_000);
            }

            captureThread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                long segmentStart = System.currentTimeMillis();
                long lastVoiceAt = segmentStart;
                boolean hadVoice = false;
                while (state == State.RECORDING && line != null && line.isOpen()) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read <= 0) {
                        continue;
                    }
                    synchronized (capturedAudio) {
                        capturedAudio.write(buffer, 0, read);
                    }
                    if (!openMic) {
                        continue; // Push To Talk: the key release stops it, no auto-cut needed
                    }
                    long now = System.currentTimeMillis();
                    if (rms(buffer, read) > VOICE_RMS_THRESHOLD) {
                        lastVoiceAt = now;
                        hadVoice = true;
                    }
                    if (hadVoice && now - segmentStart > MIN_UTTERANCE_MS && now - lastVoiceAt > SILENCE_CUTOFF_MS) {
                        // Said something, then paused - cut the utterance here, send it, and let the
                        // completion callback start the next one listening again.
                        Minecraft.getInstance().execute(() -> stopRecordingAndTranscribe(true));
                        return;
                    }
                    if (!hadVoice && now - segmentStart > SILENCE_CUTOFF_MS) {
                        // Nothing but background noise so far - drop it and keep listening instead of ever
                        // calling Vosk on silence (that would otherwise spam "Didn't catch anything" about
                        // once a second the whole time nobody's talking).
                        synchronized (capturedAudio) {
                            capturedAudio.reset();
                        }
                        segmentStart = now;
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

    /** Root-mean-square of {@code len} bytes of {@link #FORMAT} audio (16-bit signed, little-endian, mono). */
    private static double rms(byte[] buf, int len) {
        long sumSquares = 0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short sample = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
            sumSquares += (long) sample * sample;
        }
        return samples == 0 ? 0 : Math.sqrt((double) sumSquares / samples);
    }

    /** @param restartIfOpenMic whether to start listening again once this utterance is transcribed and sent -
     *  true for Open Mic's own silence-triggered cut, false for a Push-to-Talk release or a shutdown (the
     *  feature getting disabled, the key getting unbound, the window going away - see {@link #tick()}), none
     *  of which should spin the mic back up on their own. */
    private static void stopRecordingAndTranscribe(boolean restartIfOpenMic) {
        state = State.TRANSCRIBING;
        TargetDataLine capturedLine = line;
        line = null;
        if (capturedLine != null) {
            capturedLine.stop();
            capturedLine.close();
        }
        if (!restartIfOpenMic) {
            ModOverlayMessage.show("[Voice] Transcribing...", 2000);
        }

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
                        if (!restartIfOpenMic) {
                            ModOverlayMessage.show("[Voice] Didn't catch anything.", 2000);
                        }
                    } else {
                        ModOverlayMessage.show("[Voice] \"" + text + "\"", 3000);
                        Minecraft client = Minecraft.getInstance();
                        if (client.player != null) {
                            send(client, text);
                        }
                    }
                    VoiceToTextConfig cfg = VoiceToTextConfig.getInstance();
                    if (restartIfOpenMic && cfg.isEnabled() && cfg.getMode() == VoiceToTextConfig.Mode.OPEN_MIC) {
                        startRecording();
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

    /** Sends a finished transcription to whichever {@link com.killer560.hub.spotify.ChatDestination} is
     *  configured. All Chat joined Party and Guild on 2026-09-30, per killer560's "make push to talk have an
     *  option to go to allchat as well"; the destination itself is the same enum the Spotify lyrics feature
     *  already cycles, so there is one list of destinations in the mod rather than two.
     *  <p>
     *  The prefix in that enum is a chat-line prefix ({@code "/pc "}), and this feature has always sent
     *  through {@code sendCommand}, which wants the command WITHOUT its slash - hence the strip. Co-op /
     *  Plain has no command form at all (its prefix is empty), so it goes out as an ordinary chat line. */
    private static void send(Minecraft client, String text) {
        com.killer560.hub.spotify.ChatDestination dest = VoiceToTextConfig.getInstance().getChatDestination();
        String prefix = dest.prefix;
        if (prefix.isEmpty()) {
            client.player.connection.sendChat(text);
            return;
        }
        client.player.connection.sendCommand(prefix.substring(1).trim() + " " + text);
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
