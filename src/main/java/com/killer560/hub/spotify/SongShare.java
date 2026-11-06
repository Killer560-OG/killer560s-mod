package com.killer560.hub.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayListener;
import com.killer560.hub.relay.RelayTap;
import com.killer560.hub.supporters.SupportersFeature;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Song Tag, sending side (killer560, 2026-10-24). Sends the song you are playing on Spotify to the other mod users in your
 * relay room as one {@code data} packet, key {@link RemoteSongs#KEY}: {@code {"state":"play","artist":..,"title":..}}, or
 * {@code {"state":"off"}} once when it stops. Since 2026-11-01 Share What can add the lyric line you are on ({@code line},
 * {@code into}, and with Share Current Word {@code words}) or send it instead of the song (see {@link #payload}); a lyric
 * line changes every few seconds, still at most one packet a second.
 * <p>
 * The relay room: {@code ModChatFeature} keeps the relay connected while Share My Song or Show Others' Songs is on, in the
 * LOBBY room unless Mod Chat picks one (2026-11-01; before that nothing connected for Song Tag, so with Mod Chat off and
 * no party every song went nowhere).
 * <p>
 * Nothing is sent unless ALL of these hold ({@link #blockReason}): Share My Song is on, Show Me As A Mod User is on (a
 * song tag on someone who hides that they use the mod would give it away), and you are in the supporter list. A paused
 * Spotify, an advert or nothing playing sends "off", and the tag disappears for everyone. A player who never shared
 * sends nothing at all, not even "off".
 * <p>
 * Sent only when the payload changes, at most once per {@link #MIN_SEND_MS}, plus a resend every {@link #HEARTBEAT_MS}
 * while playing and soon after someone joins the room, because the relay does not replay {@code data} to late joiners.
 * That stays far under the relay's 4 messages per second, which chat and party data share.
 */
public final class SongShare {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-spotify");
    static final long MIN_SEND_MS = 1_000;
    static final long HEARTBEAT_MS = 30_000;
    private static final int TICK_EVERY = 5;

    /** Test hook: what Spotify is playing, instead of the real window (null = the real one). */
    static volatile NowPlaying testNowPlaying;
    /** Test hook: where a packet goes instead of the relay (null = the relay). Also counts as connected. */
    static volatile BiPredicate<String, JsonElement> testSender;

    private static volatile boolean resend;
    private static volatile boolean roomChanged;

    // Client thread only.
    private static String lastSentJson;
    private static boolean live;
    private static long lastSendMs;
    private static int ticks;
    private static Component ownTag;

    // Test hooks (read by the testkit).
    private static volatile String lastHanded = "";
    private static volatile long handedCount;
    private static volatile String lastBlock = "";

    private SongShare() {
    }

    public static void register() {
        SongShareConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SongShare", client -> tick()));
        RelayClient.addListener(new RelayListener() {
            @Override
            public void onConnected(String room, List<String> online) {
                RemoteSongs.clear();
                roomChanged = true;
            }

            @Override
            public void onDisconnected(String reason) {
                RemoteSongs.clear();
                roomChanged = true;
            }

            @Override
            public void onPresence(String event, String name, List<String> online) {
                if ("join".equals(event)) {
                    resend = true;
                } else if ("leave".equals(event)) {
                    RemoteSongs.forget(name);
                }
            }
        });
    }

    private static void tick() {
        if (++ticks % TICK_EVERY != 0) return;
        updateLocalTag();
        if (roomChanged) {
            // A new room has heard nothing from us: start over, and send the current song if there is one.
            roomChanged = false;
            lastSentJson = null;
            live = false;
            ownTag = null;
        }
        boolean connected = testSender != null || RelayClient.isConnected() || RelayTap.isConnected();
        if (!connected) {
            live = false;
            ownTag = null;
            lastSentJson = null;
            return;
        }
        JsonObject payload = payload();
        // What decides a resend: everything but "into", which moves every tick and is only a timing hint.
        JsonObject content = payload.deepCopy();
        content.remove("into");
        String json = content.toString();
        boolean play = "play".equals(payload.get("state").getAsString());
        long now = System.currentTimeMillis();
        if (!play && !live) {
            // Never shared in this room, so there is nothing to take back: send nothing.
            lastSentJson = json;
            resend = false;
            return;
        }
        boolean changed = !json.equals(lastSentJson);
        boolean due = play && (now - lastSendMs >= HEARTBEAT_MS || resend);
        if (!(changed || due) || now - lastSendMs < MIN_SEND_MS) return;
        BiPredicate<String, JsonElement> sender = testSender;
        lastHanded = payload.toString();
        handedCount++;
        // Into the main room and, while that is a party room, the lobby room too (RelayTap): anyone within 16 blocks.
        boolean sent = sender != null ? sender.test(RemoteSongs.KEY, payload)
                : RelayClient.sendData(RemoteSongs.KEY, payload) | RelayTap.sendData(RemoteSongs.KEY, payload);
        if (!sent) return;
        resend = false;
        lastSendMs = now;
        lastSentJson = json;
        live = play;
        ownTag = play && payload.has("title")
                ? RemoteSongs.buildTag(payload.get("artist").getAsString(), payload.get("title").getAsString()) : null;
    }

    /**
     * What would be sent right now: "play" with what Share What allows (the song, the lyric line, or both), or "off".
     * The lyric line (2026-11-01) is the line being sung as its words joined by single spaces, "into" how far into it
     * playback is (ms), and with Share Current Word each word's start from the line's start (ms), so a receiver lights
     * the word being sung on its own clock between packets.
     */
    static JsonObject payload() {
        JsonObject o = new JsonObject();
        String why = blockReason();
        String block = why == null ? "" : why;
        if (!block.equals(lastBlock) && SongShareConfig.getInstance().isShareSong()) {
            LOGGER.info("[SongTag] sharing: {}", block.isEmpty() ? "allowed" : "blocked, " + block);
        }
        lastBlock = block;
        NowPlaying np = nowPlaying();
        String title = np == null ? "" : RemoteSongs.clean(np.title(), RemoteSongs.TITLE_MAX);
        if (why != null || np == null || np.kind() != NowPlaying.Kind.TRACK || title.isEmpty()) {
            o.addProperty("state", "off");
            return o;
        }
        SongShareConfig cfg = SongShareConfig.getInstance();
        SongShareConfig.ShareWhat what = cfg.getShareWhat();
        o.addProperty("state", "play");
        if (what.song()) {
            o.addProperty("artist", RemoteSongs.clean(np.artist(), RemoteSongs.ARTIST_MAX));
            o.addProperty("title", title);
        }
        if (what.lyrics()) {
            addLine(o, cfg.isShareWord());
        }
        if (!o.has("title") && !o.has("line")) {
            // Lyrics only, and no line right now (none found yet, or before the first): nothing to show anyone.
            JsonObject off = new JsonObject();
            off.addProperty("state", "off");
            return off;
        }
        return o;
    }

    /** Adds the line being sung, if any: "line", "into" and (with {@code words}) "words". */
    private static void addLine(JsonObject o, boolean words) {
        LyricsEngine.Snapshot snap = snapshot();
        if (!snap.hasTrack() || snap.lines().isEmpty() || snap.kind() != NowPlaying.Kind.TRACK) return;
        double elapsed = snap.elapsedSeconds(SpotifyLyricsFeature.nowMs());
        int idx = snap.lineAt(elapsed);
        if (idx < 0) return;
        LyricsEngine.LyricLine line = snap.lines().get(idx);
        List<String> all = line.parsed().words();
        if (all.isEmpty()) {
            o.addProperty("line", "");   // an instrumental gap: the receiver's line goes
            return;
        }
        // Whole words only, within the receiver's limits.
        StringBuilder text = new StringBuilder();
        int n = 0;
        for (String w : all) {
            String c = RemoteSongs.clean(w, RemoteSongs.LINE_MAX);
            if (c.isEmpty() || c.indexOf(' ') >= 0 || n >= RemoteSongs.MAX_WORDS
                    || text.length() + c.length() + 1 > RemoteSongs.LINE_MAX) {
                break;
            }
            if (n > 0) {
                text.append(' ');
            }
            text.append(c);
            n++;
        }
        if (n == 0) return;
        o.addProperty("line", text.toString());
        long into = Math.round((elapsed - line.timeSeconds()) * 1000);
        o.addProperty("into", Math.max(0, Math.min(RemoteSongs.MAX_INTO_MS, into)));
        if (words) {
            double start = line.timeSeconds();
            double[] starts = new double[all.size()];
            LyricTiming.Parsed parsed = line.parsed();
            if (parsed.timed()) {
                starts = parsed.wordStarts();
            } else {
                double[] share = LyricTiming.shares(parsed, start, snap.lineEnd(idx));
                double t = start;
                for (int k = 0; k < share.length; k++) {
                    starts[k] = t;
                    t += share[k];
                }
            }
            JsonArray arr = new JsonArray();
            long prev = 0;
            for (int k = 0; k < n; k++) {
                long ms = Math.max(prev, Math.min(RemoteSongs.MAX_INTO_MS, Math.round((starts[k] - start) * 1000)));
                arr.add(ms);
                prev = ms;
            }
            o.add("words", arr);
        }
    }

    /** Test hook: the lyric state to share instead of the engine's (null = the engine's). */
    static volatile LyricsEngine.Snapshot testSnapshot;

    private static LyricsEngine.Snapshot snapshot() {
        LyricsEngine.Snapshot t = testSnapshot;
        return t != null ? t : SpotifyLyricsFeature.snapshot();
    }

    /** Why your song may not be shared, or null when it may. */
    static String blockReason() {
        if (!SongShareConfig.getInstance().isShareSong()) return "Share My Song is off";
        if (!ModUserConfig.getInstance().isShare()) return "Show Me As A Mod User is off";
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return "not in a world";
        if (!SupportersFeature.isSupporter(mc.player.getUUID())) return "not a supporter";
        return null;
    }

    private static NowPlaying nowPlaying() {
        NowPlaying t = testNowPlaying;
        return t != null ? t : SpotifyLyricsFeature.nowPlaying();
    }

    /**
     * Your own song line for your own nametag in F5: while Share My Song is on and a song plays, whether or not it can
     * be SENT. killer560 (2026-10-26): "I turned it on but don't see it in f5" - it was only shown once the relay had
     * taken it, so with Show Me As A Mod User off (its default, and his), out of a relay room, or not in the supporter
     * list, the switch showed nothing anywhere and said nothing. Your own copy is local, so none of those gates apply
     * to it; the tab's Share My Song row says why it is not reaching anyone else ({@link #notSentReason}).
     */
    public static Component ownTag() { return localTag; }

    // Client thread only.
    private static Component localTag;
    private static String localKey = "";

    private static void updateLocalTag() {
        // Supporters only (killer560, 2026-10-26: "for sharing spotify that should be supporter only ... just sharing the
        // hud thing above their head, not lyrics"): a non-supporter's own copy is not shown either, even with an old
        // config file that has Share My Song on. My Lyrics In F5 is not gated.
        NowPlaying np = SongShareConfig.getInstance().isShareSong() && selfIsSupporter() ? nowPlaying() : null;
        String title = np == null ? "" : RemoteSongs.clean(np.title(), RemoteSongs.TITLE_MAX);
        if (np == null || np.kind() != NowPlaying.Kind.TRACK || title.isEmpty()) {
            localTag = null;
            localKey = "";
            return;
        }
        String artist = RemoteSongs.clean(np.artist(), RemoteSongs.ARTIST_MAX);
        String key = artist + "\n" + title;
        if (!key.equals(localKey)) {
            localKey = key;
            localTag = RemoteSongs.buildTag(artist, title);
        }
    }

    /** Whether you are in the supporter list (false while it has not loaded: nothing is sent until it has). */
    public static boolean selfIsSupporter() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && SupportersFeature.isSupporter(mc.player.getUUID());
    }

    /** A new listener in a room (the lobby link's join or welcome): send the current song again. */
    public static void resendSoon() { resend = true; }

    /** Whose songs can reach you right now, from the relay rooms you are in (the Show Others' Songs hover). */
    public static String receiveScope() {
        String room = RelayClient.isConnected() ? RelayClient.room() : "";
        boolean lobby = RelayTap.isConnected() || room.startsWith("lobby:");
        boolean party = room.startsWith("party:");
        if (!lobby && !party) return "On, but not connected to the mod's relay right now, so no one's song can reach you.";
        String near = " They must have Share My Song on and be within 16 blocks.";
        if (lobby && party) return "You see the songs of mod users in this lobby, partied or not, and in your party." + near;
        if (lobby) return "You see the songs of mod users in this lobby, partied or not." + near;
        return "You see the songs of mod users in your party; the lobby link is not connected yet (it waits for Hypixel's"
                + " server id), so players outside your party are not seen right now.";
    }

    /** Why your song is not reaching other mod users right now, or null when it is (or Share My Song is off). */
    public static String notSentReason() {
        if (!SongShareConfig.getInstance().isShareSong()) return null;
        String why = blockReason();
        if (why != null) return why;
        if (testSender == null && !RelayClient.isConnected() && !RelayTap.isConnected()) return "not connected to the mod's relay (no party or lobby room)";
        return null;
    }
}
