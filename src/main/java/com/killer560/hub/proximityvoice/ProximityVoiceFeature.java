package com.killer560.hub.proximityvoice;

import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.proximityvoice.ProximityVoiceConfig.VoiceScope;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <li><b>Discovering peers' addresses: DISABLED (2026-09-20).</b> It used to broadcast this client's own
 * IP:port as a tagged chat line every five seconds, so a home IP address reached every party member and
 * Hypixel's chat log, and the mod auto-sent a chat line continuously. killer560: "I do not want it
 * shipping out anyone's personal info." No address leaves the client now, so no peers are found and the
 * feature announces that rather than looking broken. Note the deeper point: peer-to-peer voice ALWAYS
 * reveals your address to whoever you speak with, so the fix is not a better way to swap addresses - it is
 * to stop being peer-to-peer. Simple Voice Chat relays audio through the Minecraft server (impossible on
 * Hypixel), and the client-side ones that do work on Hypixel (Badlion, LabyMod, Feather) relay it through
 * their own voice servers. This mod now has its own relay, which is where the audio should go.
 * <li><b>Audio codec: none - raw 16kHz mono 16-bit PCM.</b> A real compressor (Opus) would use a
 * fraction of the bandwidth, but adds a second native-library dependency this session already took one
 * real risk on with Vosk; raw PCM needs no native code, no extra ~20MB dependency, and no additional
 * unverifiable native-loading risk - the real tradeoff is higher bandwidth (~32kbps per active
 * speaker) and no packet-loss resilience.
 * <li><b>Proximity and scope:</b> see {@link #volumeForPeer} - a real party member is always full volume;
 * a lobby-only peer (discoverable only when THEY talk with Lobby scope - see below) fades with distance
 * unless Lobby Falloff is turned off, and is only ever heard at all while your own Listen scope is Lobby.
 * </ul>
 * <p>
 * <b>Scope is peer-to-peer, not symmetric</b> (killer560, 2026-09-20 - separate Listen/Talk toggles, and
 * "global" redefined as lobby-only): whether you can discover a given teammate as a peer at all depends on
 * <em>their</em> Talk scope (Party broadcasts only over Party Chat; Lobby broadcasts over plain chat, which
 * Hypixel only ever delivers to players physically inside the same dungeon instance - {@link LeapMenuFeature}'s
 * own doc: "nobody else's player entities are ever sent to your client there"). Your own Listen scope then
 * decides whether you actually play back a lobby-only peer once discovered. This mod cannot make itself
 * discoverable to someone who chose Party-only talk, and that is by design - it never invents any address
 * book/relay beyond what the two peers' own chat-scope choices reach.
 * <p>
 * <b>Real, disclosed risk this session could not verify, same category as Voice To Text:</b> no
 * microphone and no second real player to test actual P2P audio with in this environment - only that
 * the mod itself still boots fine with this code present. Test with a real friend before trusting it.
 */
public final class ProximityVoiceFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-proximityvoice");
    private static final String TAG = "[K560V]";
    private static final Pattern PEER_PATTERN = Pattern.compile("\\[K560V]([0-9a-fA-F-]{36})\\|([\\d.]+)\\|(\\d+)");
    // Same NAME shape PartyTracker's own chat patterns use. Deliberately does NOT match a whisper ("From
    // Name: ...", two tokens before the colon) or a bare system message (no "Name: " at all) - format is
    // what gates trust here, not content, same principle as the 2026-09-16 audit below.
    private static final String CHAT_NAME = "(?:\\[[^]]+] )?([A-Za-z0-9_]{1,16})";
    private static final Pattern PARTY_CHAT_LINE = Pattern.compile("^Party > " + CHAT_NAME + ": (.*)$");
    private static final Pattern PLAIN_CHAT_LINE = Pattern.compile("^" + CHAT_NAME + ": (.*)$");
    /** Package-private (not private): {@link MicrophoneDevices} and {@link MicTester} need the exact same
     *  format to probe/open lines with. */
    static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int FRAME_BYTES = 640; // 20ms @ 16kHz mono 16-bit
    /** A received peer is "talking" for this long after its last frame - long enough to bridge normal
     *  between-word gaps in speech without the HUD indicator flickering off and back on. */
    private static final long TALKING_HOLD_MS = 400L;

    // Opening/closing/restarting the mic line always happens here, never on the caller's thread - AudioSystem
    // device probing can be slow on Windows, and this repo has a real bug history of a TargetDataLine left
    // open forever, so every open/close is serialized through one executor instead of racing on whichever
    // thread happened to trigger it (a tick, a GUI click, dungeon start/stop).
    private static final ExecutorService MIC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-voice-mic");
        t.setDaemon(true);
        return t;
    });

    private static DatagramSocket socket;
    private static Thread sendThread;
    private static Thread receiveThread;
    private static volatile boolean running = false;
    private static volatile boolean transmitting = false;
    private static volatile TargetDataLine micLine;
    /** The device actually in use ({@link MicrophoneDevices#DEFAULT_LABEL} on fallback), or {@code null}
     *  while no line is open - read by the tab to show "which device is currently in use". */
    private static volatile String currentDeviceName;
    /** 0-1, updated on every frame read regardless of push-to-talk/mute state, so Test Mic still shows
     *  activity even while not transmitting. */
    private static volatile float currentInputLevel = 0f;
    private static final Map<UUID, SourceDataLine> playbackLines = new ConcurrentHashMap<>();
    private static final Map<UUID, InetSocketAddress> peerAddresses = new ConcurrentHashMap<>();
    /** The chat-visible name a beacon's sessionId claims to be, taken from the SENDER of the chat line the
     *  beacon rode in on (never from inside the payload itself) - lets talk/listen scope tell a real party
     *  member's session apart from a lobby-only one. */
    private static final Map<UUID, String> peerNames = new ConcurrentHashMap<>();
    /** Wall-clock ms of the last audio frame actually received from each peer - backs {@link #isTalking}. */
    private static final Map<UUID, Long> lastAudioAtMs = new ConcurrentHashMap<>();
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

        // Peer discovery is OFF (killer560, 2026-09-20: "I do not want it shipping out anyone's personal
        // info"). It worked by typing this client's IP and port into party chat every five seconds, so
        // every party member - and Hypixel's own chat logs - got a home IP address, and the mod was
        // auto-sending a chat line every 5s, which is its own way to get muted. Peer-to-peer cannot avoid
        // handing your address to whoever you talk to, so the fix is to stop relaying audio peer-to-peer at
        // all and route it through the mod's own server, the way Simple Voice Chat and the Badlion/LabyMod
        // style clients do. Until that lands the feature finds nobody, on purpose, and says so.
        announceDiscoveryDisabledOnce();

        Minecraft client = Minecraft.getInstance();
        if (cfg.isPushToTalk() && cfg.getPushToTalkKeyCode() != KeyUtil.NONE) {
            // killer560, 2026-09-20: Voice To Text had exactly this bug today - typing the bound letter
            // into an open chat/GUI screen fired the bind and streamed what it "heard" to the party. A
            // screen being open (chat, inventory, this mod's own menu, anything) means keys are for
            // typing/clicking, never for a raw keybind poll - so push-to-talk is forced off whenever one is
            // open, independent of whether the bound key/mouse button is physically held.
            boolean down = client.screen == null && KeyUtil.isBindDown(client.getWindow(), cfg.getPushToTalkKeyCode());
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

            // Off the client tick thread - see MIC_EXECUTOR's doc.
            MIC_EXECUTOR.execute(ProximityVoiceFeature::startMicCapture);

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
                    // Don't write the user's public IP into latest.log - logs get pasted into Discord for support.
                    LOGGER.info("[ProximityVoice] STUN discovery finished (public port {})", result.port());
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
        MIC_EXECUTOR.execute(ProximityVoiceFeature::closeMicLine);
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
        peerNames.clear();
        lastAudioAtMs.clear();
    }

    /** Only ever called on {@link #MIC_EXECUTOR}. Opens the configured device (or falls back to the
     *  default - see {@link MicrophoneDevices#openLine}) and starts the frame-send thread. */
    private static void startMicCapture() {
        try {
            MicrophoneDevices.OpenedLine opened =
                    MicrophoneDevices.openLine(ProximityVoiceConfig.getInstance().getMicrophoneDeviceName());
            TargetDataLine line = opened.line();
            micLine = line;
            currentDeviceName = opened.deviceName();
            line.start();

            // Reads `line`, the local captured at open time, not the static `micLine` field - a close
            // racing this loop (device change, stop()) can null out the field between the while-condition
            // check and a field-based read() call, which used to be a real NPE risk right on this line.
            sendThread = new Thread(() -> {
                byte[] buffer = new byte[FRAME_BYTES];
                while (running && line.isOpen()) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read <= 0) {
                        continue;
                    }
                    currentInputLevel = computeLevel(buffer, read);
                    if (transmitting) {
                        sendAudioFrame(buffer, read);
                    }
                }
            }, "killer560smod-voice-send");
            sendThread.setDaemon(true);
            sendThread.start();
        } catch (Exception e) {
            LOGGER.warn("[ProximityVoice] Failed to open microphone", e);
            ModOverlayMessage.show("§c[ProxVoice] Failed to open microphone: " + e.getMessage(), 3000);
            currentDeviceName = null;
        }
    }

    /** Only ever called on {@link #MIC_EXECUTOR}. Stops/closes whatever mic line is open (if any) and
     *  waits for the send thread reading it to actually exit before returning, so a caller that reopens
     *  right after (see {@link #restartMicCapture}) never has two send threads alive at once. */
    private static void closeMicLine() {
        Thread threadToJoin = sendThread;
        TargetDataLine line = micLine;
        micLine = null;
        currentDeviceName = null;
        currentInputLevel = 0f;
        if (line != null) {
            try {
                line.stop();
            } catch (Exception ignored) {
            }
            try {
                line.close();
            } catch (Exception ignored) {
            }
        }
        if (threadToJoin != null) {
            try {
                threadToJoin.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sendThread = null;
    }

    /** Only ever called on {@link #MIC_EXECUTOR}. Cleanly closes the current line and, if still running
     *  (the dungeon session didn't also end in the meantime), reopens with whatever device is now
     *  configured - killer560, 2026-09-20: switching the mic mid-session must not leak the old line. */
    private static void restartMicCapture() {
        closeMicLine();
        if (running) {
            startMicCapture();
        }
    }

    /** Called by {@link com.killer560.hub.gui.tab.ProximityVoiceTab} when the user picks a different
     *  microphone. Persists instantly; only actually reopens the line (off-thread) if proximity voice is
     *  live right now. Public: the tab lives in a different package. */
    public static void onMicrophoneDeviceChanged() {
        if (running) {
            MIC_EXECUTOR.execute(ProximityVoiceFeature::restartMicCapture);
        }
    }

    /** True while this feature (not {@link MicTester}) has a real mic line open. */
    public static boolean isMicActive() {
        return running && micLine != null;
    }

    /** True whenever proximity voice is live right now (enabled + inside a dungeon) - the same condition
     *  {@link #tick()} uses to start/stop, exposed for {@link PartyVoiceHudElement}'s relevance check. */
    public static boolean isActive() {
        return running;
    }

    /** 0-1 input level from the last frame read, live regardless of push-to-talk/mute state. */
    public static float currentInputLevel() {
        return currentInputLevel;
    }

    /** The device name actually in use right now ({@link MicrophoneDevices#DEFAULT_LABEL} on fallback), or
     *  {@code null} while no line is open - read by the tab to show "which device is currently in use". */
    public static String currentDeviceInUse() {
        return currentDeviceName;
    }

    /** RMS level of a 16-bit mono PCM buffer, normalized to roughly 0-1 (clipping treated as "full bar").
     *  Shared with {@link MicTester} so the Test Mic meter and the real capture path read levels the same
     *  way. */
    static float computeLevel(byte[] data, int length) {
        int samples = length / 2;
        if (samples <= 0) {
            return 0f;
        }
        long sumSquares = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        double rms = Math.sqrt(sumSquares / (double) samples);
        return (float) Math.min(1.0, rms / 12000.0);
    }

    /** Only ever sends to peers {@link ProximityVoiceConfig#getTalkScope()} actually allows - Party scope
     *  drops anyone whose beacon didn't come with a real-party name attached, even if their address is
     *  known (e.g. they broadcast Lobby-wide but you're talking Party-only). */
    private static void sendAudioFrame(byte[] data, int length) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        boolean talkLobby = ProximityVoiceConfig.getInstance().getTalkScope() == VoiceScope.LOBBY;
        byte[] packetData = new byte[16 + length];
        writeUuid(packetData, localSessionId);
        System.arraycopy(data, 0, packetData, 16, length);
        for (Map.Entry<UUID, InetSocketAddress> entry : peerAddresses.entrySet()) {
            if (!talkLobby && !isRealPartyMemberByName(peerNames.get(entry.getKey()))) {
                continue;
            }
            InetSocketAddress addr = entry.getValue();
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
                // Only play audio from a peer we learned about through chat, and only from the address
                // that peer announced - otherwise anyone who learns this socket's IP:port can inject audio
                // into the game (2026-09-16 audit).
                InetSocketAddress known = peerAddresses.get(senderId);
                if (known == null || packet.getAddress() == null || !packet.getAddress().equals(known.getAddress())) {
                    continue;
                }
                // Recorded before the listen-scope volume check below so the "who's talking" HUD reflects
                // that a peer is actually transmitting even while you (locally) have them at 0 volume -
                // same reasoning Discord-style overlays use for a deafened/out-of-range teammate.
                lastAudioAtMs.put(senderId, System.currentTimeMillis());
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

    /**
     * killer560, 2026-09-20: "not based on distance if you are in a party" (now folded into the
     * Listen/Talk scope model - see the class doc) - a real party member is always full volume, a
     * lobby-only peer fades with distance (unless Lobby Falloff is off) and only plays at all while Listen
     * scope is Lobby.
     */
    private static float volumeForPeer(UUID senderId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0f;
        }
        String senderName = peerNames.get(senderId);
        if (senderName == null) {
            return 0f; // Every beacon has carried a chat-verified name since the 2026-09-20 scope rework.
        }
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        boolean isPartyMember = isRealPartyMemberByName(senderName);
        if (!isPartyMember && cfg.getListenScope() != VoiceScope.LOBBY) {
            return 0f; // Listen scope = Party: never hear a lobby-only peer, discovered or not.
        }
        Player sender = findLoadedPlayerByName(senderName);
        if (sender == null) {
            return 0f; // Not currently loaded/visible (shouldn't happen inside the same instance, but be safe).
        }
        if (isPartyMember) {
            return 1.0f;
        }
        if (!cfg.isLobbyFalloffEnabled()) {
            return 1.0f;
        }
        double dist = client.player.distanceTo(sender);
        double range = cfg.getMaxRange();
        return dist <= range ? (float) (1.0 - dist / range) : 0f;
    }

    /** Any loaded player in the current instance with this name - {@link LeapMenuFeature#currentPartyMembers()}
     *  is "everyone loaded", which inside a dungeon means the whole lobby, party or not. */
    private static Player findLoadedPlayerByName(String name) {
        for (Player p : LeapMenuFeature.currentPartyMembers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private static boolean isRealPartyMemberByName(String name) {
        if (name == null) {
            return false;
        }
        for (String teammate : PartyTracker.teammates()) {
            if (teammate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code playerName} (a real party member - see {@link PartyTracker#teammates()}) has sent an
     *  audio frame in the last {@link #TALKING_HOLD_MS} - backs {@link PartyVoiceHudElement}. Only reads
     *  a timestamp map the network receive thread already maintains; never touches audio hardware, so it's
     *  safe to call every HUD render frame. */
    public static boolean isTalking(String playerName) {
        if (playerName == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, String> entry : peerNames.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase(playerName)) {
                continue;
            }
            Long last = lastAudioAtMs.get(entry.getKey());
            if (last != null && now - last <= TALKING_HOLD_MS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Broadcasts this client's address beacon over whichever chat channel {@link ProximityVoiceConfig#getTalkScope()}
     * needs to reach: Party Chat (real {@code /party} only) for {@link VoiceScope#PARTY}, or plain chat for
     * {@link VoiceScope#LOBBY} - Hypixel only ever delivers a plain chat message from inside a Catacombs
     * instance to the players physically in that same instance (see the class doc), so this never reaches
     * further than "the lobby" even though it isn't Party Chat.
     */
    private static boolean discoveryNoticeShown;

    /** Tells him once per session why nobody is being found, instead of looking silently broken. */
    private static void announceDiscoveryDisabledOnce() {
        if (discoveryNoticeShown) {
            return;
        }
        discoveryNoticeShown = true;
        ModOverlayMessage.show("§e[ProxVoice] Voice is on hold: finding other players used to put your IP "
                + "address in party chat. It is being moved onto the mod's own server.", 6000);
    }

    private static void onChatMessage(Component message) {
        if (!running) {
            return;
        }
        String raw = message.getString();
        if (!raw.contains(TAG)) {
            return;
        }
        String senderName;
        String rest;
        Matcher partyMatch = PARTY_CHAT_LINE.matcher(raw);
        if (partyMatch.matches()) {
            senderName = partyMatch.group(1);
            rest = partyMatch.group(2);
        } else {
            // Only accept a message shaped like real chat ("[RANK] Name: text") - a whisper ("From Name:
            // ...") or a system line never matches this, so format alone keeps this from trusting either.
            // Combined with `running` (this client is inside a dungeon instance right now) and the same
            // instance-isolation Hypixel gives Catacombs groups, a plain-chat beacon can only ever have
            // come from someone physically in this run - see the class doc (2026-09-16 audit's reasoning
            // extended to the 2026-09-20 Lobby scope work).
            Matcher plainMatch = PLAIN_CHAT_LINE.matcher(raw);
            if (!plainMatch.matches()) {
                return;
            }
            senderName = plainMatch.group(1);
            rest = plainMatch.group(2);
        }
        Matcher m = PEER_PATTERN.matcher(rest);
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
            if (port <= 0 || port > 65535) {
                return;
            }
            // A dungeon instance is at most 5 players; anything beyond that is beacon spam.
            if (!peerAddresses.containsKey(sessionId) && peerAddresses.size() >= 8) {
                return;
            }
            peerAddresses.put(sessionId, new InetSocketAddress(InetAddress.getByName(ip), port));
            peerNames.put(sessionId, senderName);
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
