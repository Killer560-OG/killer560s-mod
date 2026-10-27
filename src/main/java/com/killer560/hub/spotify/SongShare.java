package com.killer560.hub.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayListener;
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
 * {@code {"state":"off"}} once when it stops. Song name only, never a lyric line.
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
        if (++ticks % TICK_EVERY != 0) {
            return;
        }
        updateLocalTag();
        if (roomChanged) {
            // A new room has heard nothing from us: start over, and send the current song if there is one.
            roomChanged = false;
            lastSentJson = null;
            live = false;
            ownTag = null;
        }
        boolean connected = testSender != null || RelayClient.isConnected();
        if (!connected) {
            live = false;
            ownTag = null;
            lastSentJson = null;
            return;
        }
        JsonObject payload = payload();
        String json = payload.toString();
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
        if (!(changed || due) || now - lastSendMs < MIN_SEND_MS) {
            return;
        }
        BiPredicate<String, JsonElement> sender = testSender;
        lastHanded = json;
        handedCount++;
        boolean sent = sender != null ? sender.test(RemoteSongs.KEY, payload) : RelayClient.sendData(RemoteSongs.KEY, payload);
        if (!sent) {
            return;
        }
        resend = false;
        lastSendMs = now;
        lastSentJson = json;
        live = play;
        ownTag = play ? RemoteSongs.buildTag(payload.get("artist").getAsString(), payload.get("title").getAsString()) : null;
    }

    /** What would be sent right now: "play" with the song, or "off". */
    static JsonObject payload() {
        JsonObject o = new JsonObject();
        String why = blockReason();
        lastBlock = why == null ? "" : why;
        NowPlaying np = nowPlaying();
        String title = np == null ? "" : RemoteSongs.clean(np.title(), RemoteSongs.TITLE_MAX);
        if (why != null || np == null || np.kind() != NowPlaying.Kind.TRACK || title.isEmpty()) {
            o.addProperty("state", "off");
            return o;
        }
        o.addProperty("state", "play");
        o.addProperty("artist", RemoteSongs.clean(np.artist(), RemoteSongs.ARTIST_MAX));
        o.addProperty("title", title);
        return o;
    }

    /** Why your song may not be shared, or null when it may. */
    static String blockReason() {
        if (!SongShareConfig.getInstance().isShareSong()) {
            return "Share My Song is off";
        }
        if (!ModUserConfig.getInstance().isShare()) {
            return "Show Me As A Mod User is off";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return "not in a world";
        }
        if (!SupportersFeature.isSupporter(mc.player.getUUID())) {
            return "not a supporter";
        }
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
    public static Component ownTag() {
        return localTag;
    }

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

    /** Why your song is not reaching other mod users right now, or null when it is (or Share My Song is off). */
    public static String notSentReason() {
        if (!SongShareConfig.getInstance().isShareSong()) {
            return null;
        }
        String why = blockReason();
        if (why != null) {
            return why;
        }
        if (testSender == null && !RelayClient.isConnected()) {
            return "not connected to the mod's relay (no party or lobby room)";
        }
        return null;
    }
}
