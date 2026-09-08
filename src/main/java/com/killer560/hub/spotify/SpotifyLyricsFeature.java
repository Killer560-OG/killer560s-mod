package com.killer560.hub.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-spotify");

    // Persistent settings
    public static volatile boolean enabled = true;
    public static volatile ChatDestination chatDestination = ChatDestination.PARTY;
    public static volatile boolean fullLyrics = true;
    public static volatile ProfanityLevel profanityLevel = ProfanityLevel.ALL;
    public static volatile String lastFmApiKey = "";
    public static volatile String lastFmUsername = "";
    // Lyric timing offset in milliseconds (0-15000), compensates for the delay between a song
    // starting and Last.fm reporting it. User-adjustable via the in-game slider.
    public static volatile int lyricTimingOffsetMs = 5_000;

    // Same filename the old standalone mod's companion server used to read, so an existing
    // Last.fm login carries over without re-entering it.
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("spotify-lyrics-lastfm.json");

    private static final LyricsEngine ENGINE = new LyricsEngine();
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
        registerChatListeners();
        SCHEDULER.scheduleAtFixedRate(SpotifyLyricsFeature::tick, 1, 2, TimeUnit.SECONDS);
    }

    // ---- Config persistence ----

    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_FILE)).getAsJsonObject();
                enabled = obj.has("enabled") ? obj.get("enabled").getAsBoolean() : true;
                lastFmApiKey = obj.has("apiKey") ? obj.get("apiKey").getAsString() : "";
                lastFmUsername = obj.has("username") ? obj.get("username").getAsString() : "";
                lyricTimingOffsetMs = obj.has("timingOffsetMs")
                        ? Math.max(0, Math.min(15_000, obj.get("timingOffsetMs").getAsInt()))
                        : 5_000;
                chatDestination = obj.has("chatDestination")
                        ? parseEnum(ChatDestination.class, obj.get("chatDestination").getAsString(), ChatDestination.PARTY)
                        : ChatDestination.PARTY;
                fullLyrics = obj.has("fullLyrics") ? obj.get("fullLyrics").getAsBoolean() : true;
                profanityLevel = obj.has("profanityLevel")
                        ? parseEnum(ProfanityLevel.class, obj.get("profanityLevel").getAsString(), ProfanityLevel.ALL)
                        : ProfanityLevel.ALL;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read Spotify Lyrics config: {}", e.getMessage());
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("apiKey", lastFmApiKey.trim());
            obj.addProperty("username", lastFmUsername.trim());
            obj.addProperty("timingOffsetMs", lyricTimingOffsetMs);
            obj.addProperty("chatDestination", chatDestination.name());
            obj.addProperty("fullLyrics", fullLyrics);
            obj.addProperty("profanityLevel", profanityLevel.name());
            Files.writeString(CONFIG_FILE, new Gson().toJson(obj));
        } catch (Exception e) {
            LOGGER.warn("Could not save Spotify Lyrics config: {}", e.getMessage());
        }
    }

    // ---- Party detection ----

    private static void registerChatListeners() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) ->
                        updatePartyStatus(message.getString()));
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> updatePartyStatus(message.getString()));
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
        ENGINE.poll(lastFmApiKey, lastFmUsername, lyricTimingOffsetMs, fullLyrics);

        if (!enabled) {
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
        if (chatDestination == ChatDestination.PARTY && !inParty) {
            return;
        }

        sendToChat(applyProfanityFilter(lyric));
    }

    private static void sendToChat(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            String full = chatDestination.prefix + message;
            client.execute(() -> client.player.connection.sendChat(full));
        }
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
