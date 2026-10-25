package com.killer560.hub.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.modusers.ModUsers;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.supporters.SlurFilter;
import com.killer560.hub.supporters.SupportersFeature;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Song Tag, receiving side (killer560, 2026-10-24): the song another mod user plays on Spotify, drawn as its own line
 * above their name. It arrives only as a relay {@code data} packet with key {@link #KEY} (never from chat), and the
 * packet's {@code from}/{@code uuid}, stamped by the relay from the sender's verified token, are the only thing trusted
 * about who sent it; the payload carries no name at all.
 * <p>
 * A packet is kept only when its sender shows as a mod user ({@link ModUsers}) and is in the supporter list
 * ({@link SupportersFeature#isSupporter}); both are checked again every time the tag is looked up, so a sender who stops
 * showing or leaves the supporter list loses the tag at once. Everything in it is hostile: the value is capped at
 * {@link #MAX_VALUE_CHARS} before it is read, every string is cleaned ({@link #clean}: no section signs, control,
 * format, bidi or invisible characters, whitespace collapsed, length capped), a slur in the artist or title hides the
 * tag, and other swear words are starred out at the strict level whatever your own Spotify filter is. Each sender may
 * update at most once per {@link #MIN_INTERVAL_MS}; at most {@link #MAX_SENDERS} are kept; an entry is dropped
 * {@link #EXPIRE_MS} after its last packet (the sender resends every 30 s while playing). The drawn {@link Component}
 * is built once per accepted packet, so the per-frame lookup ({@link #tagFor}) allocates nothing.
 */
public final class RemoteSongs {

    /** The relay {@code data} key. Versioned like PartyData's {@code dg.v1.*}. */
    public static final String KEY = "np.v1";
    static final int MAX_VALUE_CHARS = 512;
    static final int MAX_SENDERS = 64;
    static final int ARTIST_MAX = 48;
    static final int TITLE_MAX = 64;
    /** The drawn line, prefix excluded, is cut to this many characters with an ellipsis. */
    static final int DRAWN_MAX = 48;
    static final long MIN_INTERVAL_MS = 500;
    static final long EXPIRE_MS = 90_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UUID_DASHLESS = Pattern.compile("[0-9a-fA-F]{32}");

    record Entry(UUID uuid, String artist, String title, Component tag, long atMs) {
    }

    private static final Map<String, Entry> SONGS = new ConcurrentHashMap<>();
    /** When each sender's last packet was accepted, for the rate limit (kept after an "off" so off/on spam is limited too). */
    private static final Map<String, Long> LAST_ACCEPT = new ConcurrentHashMap<>();

    // Test hooks (read by the testkit). testConnected stands in for a live relay socket, which an offline client lacks.
    static volatile boolean testConnected;
    private static volatile long accepted;
    private static volatile long rejected;
    private static volatile String lastReject = "";

    private RemoteSongs() {
    }

    /** Relay worker thread. Never throws: a bad packet is counted and dropped. */
    public static void onRelayData(String from, String uuid, JsonElement value) {
        String why;
        try {
            why = accept(from, uuid, value, System.currentTimeMillis());
        } catch (RuntimeException e) {
            why = "bad packet: " + e.getClass().getSimpleName();
        }
        if (why == null) {
            accepted++;
        } else {
            rejected++;
            lastReject = why;
        }
    }

    /** @return null when the packet was applied, otherwise why it was dropped. */
    static String accept(String from, String uuidText, JsonElement value, long now) {
        if (from == null || !NAME.matcher(from).matches()) {
            return "bad sender name";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && from.equals(mc.player.getGameProfile().name())) {
            return "own packet";
        }
        UUID uuid = parseUuid(uuidText);
        if (uuid == null) {
            return "no sender uuid";
        }
        if (ModUsers.variantOf(from) == null) {
            return "not a mod user";
        }
        if (!SupportersFeature.isSupporter(uuid)) {
            return "not a supporter";
        }
        if (value == null || !value.isJsonObject()) {
            return "not an object";
        }
        if (value.toString().length() > MAX_VALUE_CHARS) {
            return "too long";
        }
        JsonObject obj = value.getAsJsonObject();
        String state = string(obj, "state");
        if ("off".equals(state)) {
            SONGS.remove(from);
            return null;
        }
        if (!"play".equals(state)) {
            return "unknown state";
        }
        Long last = LAST_ACCEPT.get(from);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return "too fast";
        }
        if (!SONGS.containsKey(from) && SONGS.size() >= MAX_SENDERS) {
            return "too many senders";
        }
        if (LAST_ACCEPT.size() > MAX_SENDERS * 4) {
            LAST_ACCEPT.clear();
        }
        LAST_ACCEPT.put(from, now);
        String artist = clean(string(obj, "artist"), ARTIST_MAX);
        String title = clean(string(obj, "title"), TITLE_MAX);
        if (title.isEmpty()) {
            SONGS.remove(from);
            return "no title";
        }
        if (SlurFilter.isBlocked(title) || SlurFilter.isBlocked(artist)) {
            SONGS.remove(from);
            return "filtered";
        }
        artist = SpotifyLyricsFeature.filterProfanity(artist, ProfanityLevel.ALL);
        title = SpotifyLyricsFeature.filterProfanity(title, ProfanityLevel.ALL);
        SONGS.put(from, new Entry(uuid, artist, title, buildTag(artist, title), now));
        return null;
    }

    /**
     * The tag to draw above {@code name}'s nametag, or null. Called per player per frame: a map lookup and a few field
     * reads, no allocation. {@code entityId} must be the uuid the relay stamped, so a packet for one account never
     * paints another entity that happens to carry the same name.
     */
    public static Component tagFor(String name, UUID entityId) {
        if (name == null || entityId == null || SONGS.isEmpty() || !SongShareConfig.getInstance().isShowOthers()
                || !(testConnected || RelayClient.isConnected())) {
            return null;
        }
        Entry e = SONGS.get(name);
        if (e == null || !e.uuid().equals(entityId)) {
            return null;
        }
        if (System.currentTimeMillis() - e.atMs() > EXPIRE_MS) {
            SONGS.remove(name, e);
            return null;
        }
        if (ModUsers.variantOf(name) == null || !SupportersFeature.isSupporter(entityId)) {
            return null;
        }
        return e.tag();
    }

    /** A player left the room. */
    static void forget(String name) {
        if (name != null) {
            SONGS.remove(name);
        }
    }

    /** The room changed or the socket went away. */
    static void clear() {
        SONGS.clear();
        LAST_ACCEPT.clear();
    }

    public static int tracked() {
        return SONGS.size();
    }

    // ---- text ----------------------------------------------------------------------------------------------------

    /** "♪ Title - Artist" in gray italic, plain literal text, never a sender-chosen style or event. */
    static Component buildTag(String artist, String title) {
        String line = artist.isEmpty() ? title : title + " - " + artist;
        if (line.codePointCount(0, line.length()) > DRAWN_MAX) {
            line = line.substring(0, line.offsetByCodePoints(0, DRAWN_MAX - 1)).stripTrailing() + "…";
        }
        return Component.literal("♪ " + line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    /**
     * Another player's text made safe to draw: section-sign codes removed with the character after them; control,
     * format (zero-width, bidi overrides and isolates), private-use, unassigned and lone surrogate characters dropped;
     * enclosing marks dropped and combining marks limited to two in a row (no towers of accents); the invisible fillers
     * Hangul and Braille offer dropped; any run of whitespace becomes one space; at most {@code max} code points.
     */
    static String clean(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(Math.min(s.length(), max * 2));
        int n = 0;
        int marks = 0;
        boolean space = false;
        for (int i = 0; i < s.length() && n < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') {
                if (i < s.length()) {
                    i += Character.charCount(s.codePointAt(i));
                }
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = true;
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.UNASSIGNED || type == Character.SURROGATE || type == Character.ENCLOSING_MARK
                    || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800) {
                continue;
            }
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
                if (b.isEmpty() || ++marks > 2) {
                    continue;
                }
            } else {
                marks = 0;
            }
            if (space && !b.isEmpty()) {
                b.append(' ');
                n++;
                if (n >= max) {
                    break;
                }
            }
            space = false;
            b.appendCodePoint(cp);
            n++;
        }
        return b.toString().strip();
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return "";
        }
        return e.getAsString();
    }

    static UUID parseUuid(String text) {
        if (text == null || text.isEmpty() || text.length() > 36) {
            return null;
        }
        try {
            if (UUID_DASHLESS.matcher(text).matches()) {
                return new UUID(Long.parseUnsignedLong(text.substring(0, 16), 16),
                        Long.parseUnsignedLong(text.substring(16), 16));
            }
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
