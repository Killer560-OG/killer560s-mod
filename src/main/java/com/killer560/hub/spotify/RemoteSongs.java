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

import java.util.List;
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
 * tag (a slur in a shared lyric line drops the line), and other swear words are starred at YOUR Display Censor level
 * (2026-11-01; it was always the strict level before). Each sender may update at most once per {@link #MIN_INTERVAL_MS};
 * at most {@link #MAX_SENDERS} are kept; an entry is dropped {@link #EXPIRE_MS} after its last packet (the sender resends
 * every 30 s while playing). The drawn lines are built on the render thread when the look changes, so the per-frame
 * lookups ({@link #tagFor}, {@link #lyricFor}) normally allocate nothing.
 * <p>
 * Since 2026-11-01 a sender may also share the lyric line it is on ({@code line}), how far into it it was when sent
 * ({@code into}, ms) and each word's start in it ({@code words}, ms from the line's start), per its Share What and Share
 * Current Word settings. An older client reads only {@code artist}/{@code title} and ignores the rest.
 */
public final class RemoteSongs {

    /** The relay {@code data} key. Versioned like PartyData's {@code dg.v1.*}. */
    public static final String KEY = "np.v1";
    /** Raised from 512 for the shared lyric line (2026-11-01); the relay's own limit is 4 KB a message. */
    static final int MAX_VALUE_CHARS = 1024;
    /** A shared lyric line: at most this many characters and words, "into" and word offsets at most a minute. */
    static final int LINE_MAX = 120;
    static final int MAX_WORDS = 32;
    static final long MAX_INTO_MS = 60_000;
    /** A shared line stays this long after its last word starts, unless the next packet replaces it first. */
    static final long LINE_LINGER_MS = 8_000;
    static final int MAX_SENDERS = 64;
    static final int ARTIST_MAX = 48;
    static final int TITLE_MAX = 64;
    /** The drawn line, prefix excluded, is cut to this many characters with an ellipsis. */
    static final int DRAWN_MAX = 48;
    static final long MIN_INTERVAL_MS = 500;
    static final long EXPIRE_MS = 90_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UUID_DASHLESS = Pattern.compile("[0-9a-fA-F]{32}");

    /**
     * One sender's state. The song line and the lyric line are built lazily on the render thread and kept until the
     * viewer's look changes (theme, F5 colors, censor level) or, for the lyric, the lit word moves, so a frame normally
     * allocates nothing.
     */
    static final class Entry {
        final UUID uuid;
        final String artist;
        final String title;
        /** The shared lyric line's words (empty when none), how far into the line it was when sent, each word's offset
         *  from the line's start in ms (null: no word is lit), and when it arrived. */
        final List<String> words;
        final long intoMs;
        final int[] wordMs;
        final long atMs;
        // Render thread only.
        Component tag;
        int tagStyle = Integer.MIN_VALUE;
        Component lyric;
        int lyricStyle = Integer.MIN_VALUE;
        int lyricLit = -2;

        Entry(UUID uuid, String artist, String title, List<String> words, long intoMs, int[] wordMs, long atMs) {
            this.uuid = uuid;
            this.artist = artist;
            this.title = title;
            this.words = words;
            this.intoMs = intoMs;
            this.wordMs = wordMs;
            this.atMs = atMs;
        }
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
        // The lyric line a sender shares (2026-11-01): plain text, its words split the way the sender split them.
        String line = clean(string(obj, "line"), LINE_MAX);
        if (SlurFilter.isBlocked(title) || SlurFilter.isBlocked(artist)) {
            SONGS.remove(from);
            return "filtered";
        }
        if (!line.isEmpty() && SlurFilter.isBlocked(line)) {
            line = "";   // the song may stay; the line does not
        }
        List<String> words = line.isEmpty() ? List.of() : LyricTiming.parse(line, 0).words();
        if (words.size() > MAX_WORDS) {
            words = List.copyOf(words.subList(0, MAX_WORDS));
        }
        long into = Math.max(0, Math.min(MAX_INTO_MS, number(obj, "into")));
        int[] wordMs = words.isEmpty() ? null : offsets(obj.get("words"), words.size());
        if (title.isEmpty() && words.isEmpty()) {
            SONGS.remove(from);
            return "no title";
        }
        SONGS.put(from, new Entry(uuid, artist, title, words, into, wordMs, now));
        return null;
    }

    /** A sender's word offsets: exactly one per word, each 0..60 s and never earlier than the one before; else null. */
    private static int[] offsets(JsonElement e, int n) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) {
            return null;
        }
        int[] out = new int[n];
        int prev = 0;
        for (int i = 0; i < n; i++) {
            JsonElement x = e.getAsJsonArray().get(i);
            if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            double d = x.getAsDouble();
            if (!(d >= prev) || d > MAX_INTO_MS) {
                return null;
            }
            out[i] = (int) d;
            prev = out[i];
        }
        return out;
    }

    private static long number(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        double d = e.getAsDouble();
        return Double.isFinite(d) ? (long) d : 0;
    }

    /** The live entry for {@code name}, or null: every gate checked again on every lookup. */
    private static Entry live(String name, UUID entityId) {
        if (name == null || entityId == null || SONGS.isEmpty() || !SongShareConfig.getInstance().isShowOthers()
                || !(testConnected || RelayClient.isConnected())) {
            return null;
        }
        Entry e = SONGS.get(name);
        if (e == null || !e.uuid.equals(entityId)) {
            return null;
        }
        if (System.currentTimeMillis() - e.atMs > EXPIRE_MS) {
            SONGS.remove(name, e);
            return null;
        }
        if (ModUsers.variantOf(name) == null || !SupportersFeature.isSupporter(entityId)) {
            return null;
        }
        return e;
    }

    /**
     * The tag to draw above {@code name}'s nametag, or null. Called per player per frame: a map lookup and a few field
     * reads, no allocation unless the look changed. {@code entityId} must be the uuid the relay stamped, so a packet for
     * one account never paints another entity that happens to carry the same name.
     */
    public static Component tagFor(String name, UUID entityId) {
        Entry e = live(name, entityId);
        if (e == null || e.title.isEmpty()) {
            return null;
        }
        int style = SpotifyHudConfig.getInstance().getDisplayCensor().ordinal();
        if (e.tag == null || e.tagStyle != style) {
            e.tagStyle = style;
            e.tag = buildTag(SpotifyLyricsFeature.censorDisplay(e.artist), SpotifyLyricsFeature.censorDisplay(e.title));
        }
        return e.tag;
    }

    /**
     * The lyric line another player shares, drawn over their head like your own My Lyrics In F5 line, in YOUR F5 colors
     * and censor level, with the word being sung lit when they share it: the word whose offset has passed at the line's
     * position, which is the "into" they sent plus the time since it arrived. Gone {@link #LINE_LINGER_MS} after the
     * last word (a sender that stops sending leaves no stale line).
     */
    public static Component lyricFor(String name, UUID entityId) {
        Entry e = live(name, entityId);
        if (e == null || e.words.isEmpty()) {
            return null;
        }
        long t = e.intoMs + (System.currentTimeMillis() - e.atMs);
        int lastStart = e.wordMs == null ? 0 : e.wordMs[e.wordMs.length - 1];
        if (t > lastStart + LINE_LINGER_MS) {
            return null;
        }
        int lit = -1;
        if (e.wordMs != null) {
            for (int k = 0; k < e.wordMs.length && e.wordMs[k] <= t; k++) {
                lit = k;
            }
        }
        com.killer560.hub.gui.OverlayPalette p = com.killer560.hub.gui.OverlayThemes.menu();
        int style = SongTagDraw.styleKey(p);
        if (e.lyric == null || e.lyricStyle != style || e.lyricLit != lit) {
            e.lyricStyle = style;
            e.lyricLit = lit;
            e.lyric = SongTagDraw.buildLyric(e.words, lit, false, p);
            lastRemoteLyric = e.lyric == null ? "" : e.lyric.getString();
            lastRemoteLit = lit;
        }
        return e.lyric;
    }

    // Test hooks: the last remote lyric line built and the word it lit.
    private static volatile String lastRemoteLyric = "";
    private static volatile int lastRemoteLit = -1;

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

    public static int tracked() { return SONGS.size(); }


    /** "♪ Title - Artist" in gray italic, plain literal text, never a sender-chosen style or event. */
    static Component buildTag(String artist, String title) { return buildTag(artist, title, -1); }

    /** The same line in {@code argb} instead of gray ({@code -1}: gray), still italic: Song In F5's custom text color. */
    static Component buildTag(String artist, String title, int argb) {
        String line = artist.isEmpty() ? title : title + " - " + artist;
        if (line.codePointCount(0, line.length()) > DRAWN_MAX) {
            line = line.substring(0, line.offsetByCodePoints(0, DRAWN_MAX - 1)).stripTrailing() + "…";
        }
        if (argb == -1) {
            return Component.literal("♪ " + line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        }
        final int rgb = argb & 0xFFFFFF;
        return Component.literal("♪ " + line).withStyle(s -> s.withColor(rgb).withItalic(true));
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
        return e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() ? "" : e.getAsString();
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
