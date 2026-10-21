package com.killer560.hub.spotify;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side glue for the Spotify Lyrics feature: settings, party detection, and the
 * poll-and-send loop. Runs entirely inside the mod - no companion process to start or manage.
 */
public final class SpotifyLyricsFeature {

    public static final Logger LOGGER = ModLog.get("killer560smod-spotify");

    // Persistent settings
    // Off by default, and "Song Title Only" by default (killer560, 2026-09-30). This is one of the few
    // settings that does NOT follow the usual "defaults ON" rule; he asked for it off. The same two defaults
    // are repeated in loadConfig() as the fallback for a missing key, so keep all three in agreement.
    // "enabled" is the Send Lyrics To Chat switch (row renamed from "Mod Enabled", killer560 2026-10-20: "an option
    // for sending lyrics"). It only ever controlled chat posting; the HUD has its own switch. Same field and JSON
    // key as before, so nobody's saved value changes.
    public static volatile boolean enabled = false;
    public static volatile ChatDestination chatDestination = ChatDestination.PARTY;
    public static volatile boolean fullLyrics = false;
    public static volatile ProfanityLevel profanityLevel = ProfanityLevel.ALL;
    // No source setting and no Last.fm credentials.
    //
    // killer560 (2026-09-30): "make it so there is no longer an option to select how it reads it should only
    // ever be able to read spotify." So the Spotify desktop app is the only source, Last.fm is gone, and with
    // it the API key, the username and the whole setup page. Nothing here needs configuring to work.
    // The lyric timing offset is gone too. It existed to compensate for Last.fm taking several seconds to
    // report a track change; the window title changes the instant the track does, so there is nothing to
    // compensate for. Pausing is handled by the clock in LyricsEngine rather than by a slider.

    // Same filename the old standalone mod's companion server used to read, so an existing
    // Last.fm login carries over without re-entering it.
    private static final Path CONFIG_FILE =
            ModPaths.config("spotify-lyrics-lastfm.json");

    private static final LyricsEngine ENGINE = new LyricsEngine();
    private static final SpotifyDesktopSource DESKTOP = new SpotifyDesktopSource();
    /** Spotify's position, length, pause state and cover from Windows' media controls (a background helper). */
    private static final SpotifyMediaSession MEDIA = new SpotifyMediaSession();

    /** How often the Spotify window is read. See the scheduling call for why it is this small. */
    private static final long POLL_MS = 250;
    private static volatile boolean inParty = false;
    private static volatile String lastSentLyric = "";

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-spotify-lyrics");
        t.setDaemon(true);
        return t;
    });

    private SpotifyLyricsFeature() {
    }

    public static void init() {
        loadConfig();
        SpotifyHudConfig.load();
        registerChatListeners();
        // 250 ms, not 2 s.
        //
        // killer560 (2026-09-30): "the spotify mod lyrics are still delayed a little bit ingame." A two-second
        // poll put the delay in twice over. The lyric clock starts when the title CHANGE IS SEEN, so a track
        // spotted up to two seconds late makes every line in that song late by the same amount for its whole
        // length; and the current line was only recomputed on the same two-second beat, so each line could be
        // up to two seconds late again on top. At 250 ms both errors are a quarter-second at worst.
        //
        // Affordable because the window handle is cached in SpotifyDesktopSource and the process-id lookup for
        // five seconds, so a poll that finds nothing new is two syscalls.
        SCHEDULER.scheduleAtFixedRate(SpotifyLyricsFeature::safeTick, 250, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** A throw out of a scheduleAtFixedRate task silently cancels every later run, so nothing may escape tick(). */
    private static void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            LOGGER.warn("Spotify poll tick failed: {}", String.valueOf(t));
        }
    }

    // ---- Config persistence ----

    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_FILE)).getAsJsonObject();
                // Per-key reads (2026-09-15 persistence audit): every key used to share one try/catch, so a
                // single malformed value skipped every later key - and the next saveConfig() then wrote
                // blank Last.fm credentials back to disk. Now only the bad key falls back to its default.
                enabled = ConfigJson.getBool(obj, "enabled", false);
                chatDestination = ConfigJson.getEnum(obj, "chatDestination", ChatDestination.class, ChatDestination.PARTY);
                fullLyrics = ConfigJson.getBool(obj, "fullLyrics", false);
                profanityLevel = ConfigJson.getEnum(obj, "profanityLevel", ProfanityLevel.class, ProfanityLevel.ALL);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read Spotify Lyrics config: {}", e.getMessage());
        }
    }

    public static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("chatDestination", chatDestination.name());
            obj.addProperty("fullLyrics", fullLyrics);
            obj.addProperty("profanityLevel", profanityLevel.name());
            Files.writeString(CONFIG_FILE, new Gson().toJson(obj));
        } catch (Exception e) {
            LOGGER.warn("Could not save Spotify Lyrics config: {}", e.getMessage());
        }
    }

    /** Send Lyrics To Chat: whether lyric lines (or the song title) are posted to the chosen chat. */
    public static boolean isSendLyricsToChat() {
        return enabled;
    }

    public static void setSendLyricsToChat(boolean send) {
        enabled = send;
    }

    // ---- Party detection ----

    private static void registerChatListeners() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) ->
                        updatePartyStatus(com.killer560.hub.util.ChatObserver.raw(message)));
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> updatePartyStatus(com.killer560.hub.util.ChatObserver.raw(message)));
    }

    private static void updatePartyStatus(String text) {
        if (text.contains("joined the party") || text.contains("You have joined")
                || text.contains("You'll be partied with") || text.contains("created a party")) {
            inParty = true;
        } else if (text.contains("You left the party") || text.contains("The party was disbanded")
                || text.contains("You have been kicked from the party") || text.contains("disbanded the party")) {
            inParty = false;
        }
    }

    // ---- Poll + send ----

    private static void tick() {
        // Feature off: do nothing at all. Previously poll() ran before this guard, so once Last.fm
        // credentials were entered the key + username were sent to ws.audioscrobbler.com every 2 s for
        // the whole session even with the feature disabled (2026-09-16 audit).
        // The Spotify HUD reads the same poll, so it runs while either is on; only chat sending needs Send Lyrics To Chat.
        if (!enabled && !SpotifyHudConfig.getInstance().isHudEnabled()) {
            MEDIA.stop();   // no helper process while nothing reads it
            return;
        }
        MEDIA.ensureRunning();
        ENGINE.poll(DESKTOP, MEDIA, fullLyrics);
        if (!enabled) {
            return;
        }

        // The sim counts as a place lyrics may play.
        //
        // killer560 (2026-09-30): "nor the spotify lyrics anymore." They were never broken - the log shows the
        // tracks being found and 91, 40 and 62 synced lines fetched for them - they were gated twice over and
        // said nothing about it. He was testing in the dungeon sim, which is a single-player world, so the
        // Skyblock check below refused it AND the party check further down refused it, because his destination
        // is Party and there is no party in a solo world.
        //
        // The sim is the mod's own practice dungeon and there is no public chat in it to leak into, so both
        // gates are lifted there and the lyric is printed locally.
        boolean inSim = com.killer560.hub.roomsim.SimState.isActive();

        // Skyblock Only: this runs on a timer, so it checks your location directly rather than
        // SkyblockGate.allows() (which lets the mod's own screens through) - opening the mod menu in another
        // game mode must never start sending lyrics there.
        if (!inSim && com.killer560.hub.util.SkyblockGate.isEnabled()
                && !com.killer560.hub.util.SkyblockGate.isOnSkyblock()) {
            return;
        }
        String lyric = ENGINE.getCurrentLyric();
        if (lyric.isEmpty() || lyric.equals(lastSentLyric)) {
            return;
        }
        lastSentLyric = lyric;

        boolean isTrackChange = ENGINE.isCurrentLyricTransition();
        if (!isTrackChange && !fullLyrics) {
            return;
        }
        if (!inSim && chatDestination == ChatDestination.PARTY && !inParty) {
            return;
        }

        sendToChat(applyProfanityFilter(lyric), inSim);
    }

    /** Hypixel and the vanilla packet accept at most 256 characters; a command and illegal characters are not allowed. */
    private static final int MAX_CHAT_CHARS = 256;

    /**
     * What may go to a chat: no section signs or control characters (the server kicks for them), no leading slash
     * (with the plain destination a lyric starting with "/" would run as a command), and short enough that the
     * prefix plus the line fits the 256-character limit (a longer string makes the packet encoder throw).
     */
    static String chatSafe(String message, String prefix) {
        StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '§' || c < 32 || c == 127) {
                c = ' ';
            }
            sb.append(c);
        }
        String s = sb.toString().strip();
        int start = 0;
        while (start < s.length() && s.charAt(start) == '/') {
            start++;
        }
        s = s.substring(start).strip();
        int max = Math.max(0, MAX_CHAT_CHARS - prefix.length());
        if (s.length() > max) {
            s = s.substring(0, max).strip();
        }
        return s;
    }

    /** What the Spotify HUD draws: the last poll's state, safe to read from the render thread. */
    public static LyricsEngine.Snapshot snapshot() {
        return ENGINE.snapshot();
    }

    /** The current track's cover from Windows' media controls, or null; any thread. */
    public static SpotifyMediaSession.Cover cover() {
        return MEDIA.cover();
    }

    /** Why Spotify cannot be read right now, or an empty string when it can (nothing to show then). */
    public static String sourceProblem() {
        return DESKTOP.available() ? "" : DESKTOP.unavailableReason();
    }

    private static void sendToChat(String raw, boolean localOnly) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String message = chatSafe(raw, localOnly ? "" : chatDestination.prefix);
        if (message.isEmpty()) {
            return;
        }
        if (localOnly) {
            // In the sim there is nobody to send to - /pc in a single-player world is just an unknown command
            // - so the line is printed where he can read it instead.
            client.execute(() -> com.killer560.hub.util.ModChat.send("Lyrics",
                    com.killer560.hub.util.ModChat.text(message)));
            return;
        }
        if (chatDestination == ChatDestination.MOD_CHAT) {
            client.execute(() -> com.killer560.hub.modchat.ModChatChannel.sendLine(message));
            return;
        }
        String full = chatDestination.prefix + message;
        client.execute(() -> {
            // client.player was read on the poll thread; it may be gone by the time this runs on the main thread.
            if (client.player != null) {
                client.player.connection.sendChat(full);
            }
        });
    }

    // ---- Profanity filter ----

    private static final Set<String> MINIMAL_BANNED_WORDS;
    static {
        Set<String> w = new HashSet<>(Arrays.asList(
                "nigger", "nigga", "niggas",
                "spic", "spics",
                "chink", "chinks",
                "kike", "kikes",
                "cracker",
                "wetback",
                "beaner",
                "gook",
                "raghead", "towelhead",
                "honky",
                "wop",
                "kraut",
                "fag", "faggot", "faggots",
                "dyke", "dykes",
                "tranny",
                "retard", "retarded", "retards",
                "spastic",
                "cocksucker",
                "motherfucker", "motherfucking",
                "dick", "dicks", "dickhead",
                "thot",
                "drug", "drugs"
        ));
        MINIMAL_BANNED_WORDS = Collections.unmodifiableSet(w);
    }

    private static final Set<String> ALL_BANNED_WORDS;
    static {
        Set<String> w = new HashSet<>(MINIMAL_BANNED_WORDS);
        w.addAll(Arrays.asList(
                "fuck", "fucking", "fucked", "fucker", "fucks",
                "shit", "shitting", "shitty", "bullshit", "shithead",
                "ass", "asshole", "jackass", "dumbass", "smartass",
                "bitch", "bitches", "bitchy",
                "cunt", "cunts",
                "cock", "cocks",
                "pussy", "pussies",
                "bastard", "bastards",
                "jizz", "cum",
                "twat", "twats",
                "wank", "wanker", "wanked", "wanking",
                "bollocks",
                "arse", "arsehole",
                "slut", "sluts",
                "whore", "whores",
                "skank"
        ));
        ALL_BANNED_WORDS = Collections.unmodifiableSet(w);
    }

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\w']+|[^\\w']+");

    private static String applyProfanityFilter(String input) {
        if (profanityLevel == ProfanityLevel.NONE) {
            return input;
        }

        Set<String> banned = (profanityLevel == ProfanityLevel.MINIMAL) ? MINIMAL_BANNED_WORDS : ALL_BANNED_WORDS;

        StringBuilder out = new StringBuilder(input.length());
        Matcher m = WORD_PATTERN.matcher(input);
        while (m.find()) {
            String token = m.group();
            if (Character.isLetter(token.charAt(0))) {
                String stripped = token.replaceAll("[^a-zA-Z]", "").toLowerCase();
                if (banned.contains(stripped)) {
                    out.append(token.charAt(0));
                    for (int i = 1; i < token.length(); i++) out.append('*');
                    continue;
                }
            }
            out.append(token);
        }
        return out.toString();
    }
}
