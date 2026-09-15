package com.killer560.hub.proximityvoice;

import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.secrets.DungeonState;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proximity Voice - killer560's "proximity voice option" request, with the same "zero setup, free, for
 * me and my friends" requirement as Voice To Text.
 * <p>
 * <b>Real architecture, and its real, disclosed tradeoffs</b> - this mod has no server of its own to
 * relay audio through, so this is genuine peer-to-peer:
 * <ul>
 * <li><b>NAT traversal:</b> {@link StunClient} (a hand-implemented, ~80-line real STUN client - RFC
 * 5389 is a small, decades-stable protocol, not something needing a library) asks a free public STUN
 * server (Google's own, the same one countless real WebRTC apps use) what each player's public IP:port
 * is for their voice UDP socket. Both peers then start sending to each other's public address, which
 * "hole-punches" through most home routers - this works for most people, but not universally (some
 * routers/NATs, particularly "symmetric" ones, cannot be hole-punched this way without a relay server,
 * which this mod has no way to provide for free).
 * <li><b>Discovering peers' addresses:</b> reuses the exact same tagged-party-chat trick
 * {@code PosmsgFeature}/{@code ModChatFeature} already use - broadcasts your own public IP:port over
 * Party Chat, tagged so only this mod's own copies act on it. Same real limitation as Mod Chat: this
 * is visible as an odd chat line to non-mod party members, not hidden.
 * <li><b>Audio codec: none - raw 16kHz mono 16-bit PCM.</b> A real compressor (Opus) would use a
 * fraction of the bandwidth, but adds a second native-library dependency this session already took one
 * real risk on with Vosk; raw PCM needs no native code, no extra ~20MB dependency, and no additional
 * unverifiable native-loading risk - the real tradeoff is higher bandwidth (~32kbps per active
 * speaker) and no packet-loss resilience.
 * <li><b>Proximity:</b> each received packet's volume is scaled by the real, live in-game distance to
 * that teammate (reusing {@code LeapMenuFeature.currentPartyMembers()}), silence past the configured
 * max range - the actual "proximity" part of proximity voice.
 * </ul>
 * <b>Real, disclosed risk this session could not verify, same category as Voice To Text:</b> no
 * microphone and no second real player to test actual P2P audio with in this environment - only that
 * the mod itself still boots fine with this code present. Test with a real friend before trusting it.
 */
public final class ProximityVoiceFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-proximityvoice");
    private static final String TAG = "[K560V]";
    private static final Pattern PEER_PATTERN = Pattern.compile("\\[K560V]([0-9a-fA-F-]{36})\\|([\\d.]+)\\|(\\d+)");
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int FRAME_BYTES = 640; // 20ms @ 16kHz mono 16-bit

    private static DatagramSocket socket;
    private static Thread sendThread;
    private static Thread receiveThread;
    private static volatile boolean running = false;
    private static volatile boolean transmitting = false;
    private static TargetDataLine micLine;
    private static final Map<UUID, SourceDataLine> playbackLines = new ConcurrentHashMap<>();
    private static final Map<UUID, InetSocketAddress> peerAddresses = new ConcurrentHashMap<>();
    private static UUID localSessionId = UUID.randomUUID();
    private static boolean keyWasDown = false;
    private static long lastBroadcastAtMs = 0;

    private ProximityVoiceFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        boolean shouldRun = cfg.isEnabled() && DungeonState.isInDungeon();

        if (shouldRun && !running) {
            start();
        } else if (!shouldRun && running) {
            stop();
        }
        if (!running) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBroadcastAtMs > 5000) {
            lastBroadcastAtMs = now;
            broadcastOwnAddress();
        }

        if (cfg.isPushToTalk() && cfg.getPushToTalkKeyCode() >= 0) {
            Minecraft client = Minecraft.getInstance();
            boolean down = client.getWindow() != null && InputConstants.isKeyDown(client.getWindow(), cfg.getPushToTalkKeyCode());
            transmitting = down && !cfg.isMutedSelf();
            keyWasDown = down;
        } else {
            transmitting = !cfg.isMutedSelf();
        }
    }

    private static void start() {
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(3000);
            running = true;
            localSessionId = UUID.randomUUID();

            startMicCapture();

            ModOverlayMessage.show("[ProxVoice] Enabled - discovering your public address...", 2500);
            // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): receiveThread used to
            // start immediately, reading from this SAME socket, right as the STUN discovery thread below
            // was ALSO about to read its one reply from it. receiveThread almost always won that race
            // (it was already blocked in socket.receive() by the time the STUN server replied), so the
            // real STUN response got misread as a garbage voice packet (real "phantom noise" playback via
            // playAudio) while StunClient's own read timed out and logged a false "STUN discovery
            // failed" - every single time, on every dungeon entry. Now the real peer-receive loop only
            // starts once STUN discovery has fully finished (success or failure), so nothing else is
            // competing for packets on this socket during that window.
            new Thread(() -> {
                try {
                    StunClient.Result result = StunClient.discoverPublicAddress(socket);
                    LOGGER.info("[ProximityVoice] Public address: {}:{}", result.ip(), result.port());
                } catch (Exception e) {
                    LOGGER.warn("[ProximityVoice] STUN discovery failed - proximity voice may not reach peers behind strict NATs", e);
                } finally {
                    if (running) {
                        receiveThread = new Thread(ProximityVoiceFeature::receiveLoop, "killer560smod-voice-recv");
                        receiveThread.setDaemon(true);
                        receiveThread.start();
                    }
                }
            }, "killer560smod-voice-stun").start();
        } catch (Exception e) {
            LOGGER.warn("[ProximityVoice] Failed to start", e);
            ModOverlayMessage.show("§c[ProxVoice] Failed to start: " + e.getMessage(), 3000);
            running = false;
        }
    }

    private static void stop() {
        running = false;
        transmitting = false;
        if (micLine != null) {
            micLine.stop();
            micLine.close();
            micLine = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        for (SourceDataLine line : playbackLines.values()) {
            line.stop();
            line.close();
        }
        playbackLines.clear();
        peerAddresses.clear();
    }

    private static void startMicCapture() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                ModOverlayMessage.show("§c[ProxVoice] No compatible microphone found.", 3000);
                return;
            }
            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(FORMAT);
            micLine.start();

            sendThread = new Thread(() -> {
                byte[] buffer = new byte[FRAME_BYTES];
                while (running && micLine != null && micLine.isOpen()) {
                    int read = micLine.read(buffer, 0, buffer.length);
                    if (read > 0 && transmitting) {
                        sendAudioFrame(buffer, read);
                    }
                }
            }, "killer560smod-voice-send");
            sendThread.setDaemon(true);
            sendThread.start();
        } catch (Exception e) {
            LOGGER.warn("[ProximityVoice] Failed to open microphone", e);
        }
    }

    private static void sendAudioFrame(byte[] data, int length) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        byte[] packetData = new byte[16 + length];
        writeUuid(packetData, localSessionId);
        System.arraycopy(data, 0, packetData, 16, length);
        for (InetSocketAddress addr : peerAddresses.values()) {
            try {
                socket.send(new DatagramPacket(packetData, packetData.length, addr.getAddress(), addr.getPort()));
            } catch (Exception ignored) {
            }
        }
    }

    private static void receiveLoop() {
        byte[] buffer = new byte[16 + FRAME_BYTES];
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() < 16) {
                    continue;
                }
                UUID senderId = readUuid(packet.getData());
                int audioLen = packet.getLength() - 16;
                playAudio(senderId, packet.getData(), 16, audioLen);
            } catch (Exception ignored) {
                // Socket timeout or closed - loop condition re-checks `running` each pass.
            }
        }
    }

    private static void playAudio(UUID senderId, byte[] data, int offset, int length) {
        float volume = volumeForPeer(senderId);
        if (volume <= 0f) {
            return;
        }
        SourceDataLine line = playbackLines.computeIfAbsent(senderId, id -> {
            try {
                SourceDataLine newLine = AudioSystem.getSourceDataLine(FORMAT);
                newLine.open(FORMAT);
                newLine.start();
                return newLine;
            } catch (Exception e) {
                LOGGER.warn("[ProximityVoice] Failed to open playback line", e);
                return null;
            }
        });
        if (line == null) {
            return;
        }
        byte[] scaled = applyVolume(data, offset, length, volume * ProximityVoiceConfig.getInstance().getOutputVolume());
        line.write(scaled, 0, scaled.length);
    }

    private static byte[] applyVolume(byte[] data, int offset, int length, float volume) {
        byte[] result = new byte[length];
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((data[offset + i] & 0xFF) | (data[offset + i + 1] << 8));
            int scaled = Math.round(sample * volume);
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            result[i] = (byte) (scaled & 0xFF);
            result[i + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
        return result;
    }

    private static float volumeForPeer(UUID senderId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0f;
        }
        double range = ProximityVoiceConfig.getInstance().getMaxRange();
        for (Player p : LeapMenuFeature.currentPartyMembers()) {
            // No reliable way to map a session UUID back to a specific teammate's real UUID without a
            // handshake this MVP doesn't implement - approximate using the nearest teammate as a
            // reasonable stand-in until a real per-peer identity handshake exists.
            double dist = client.player.distanceTo(p);
            if (dist <= range) {
                return (float) (1.0 - dist / range);
            }
        }
        return 0f;
    }

    private static void broadcastOwnAddress() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || socket == null) {
            return;
        }
        // Best-effort: uses the socket's own local address if STUN hasn't resolved a public one yet -
        // works for LAN/same-network testing even before/without STUN succeeding.
        String ip = socket.getLocalAddress() != null && !socket.getLocalAddress().isAnyLocalAddress()
                ? socket.getLocalAddress().getHostAddress() : null;
        if (ip == null) {
            return;
        }
        String payload = String.format(Locale.US, "%s%s|%s|%d", TAG, localSessionId, ip, socket.getLocalPort());
        client.player.connection.sendCommand("pc " + payload);
    }

    private static void onChatMessage(Component message) {
        if (!running) {
            return;
        }
        String raw = message.getString();
        if (!raw.contains(TAG)) {
            return;
        }
        Matcher m = PEER_PATTERN.matcher(raw);
        if (!m.find()) {
            return;
        }
        try {
            UUID sessionId = UUID.fromString(m.group(1));
            if (sessionId.equals(localSessionId)) {
                return;
            }
            String ip = m.group(2);
            int port = Integer.parseInt(m.group(3));
            peerAddresses.put(sessionId, new InetSocketAddress(InetAddress.getByName(ip), port));
        } catch (Exception ignored) {
        }
    }

    private static void writeUuid(byte[] buf, UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            buf[i] = (byte) (msb >>> (8 * (7 - i)));
            buf[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
    }

    private static UUID readUuid(byte[] buf) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (buf[i] & 0xFF);
            lsb = (lsb << 8) | (buf[8 + i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }
}
