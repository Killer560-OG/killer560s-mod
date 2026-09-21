package com.killer560.hub.bridge;

import com.google.gson.JsonObject;
import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.partydata.PartyDataFeature;
import com.killer560.hub.relay.mixin.MinecraftProfileKeysAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * NoammAddons' party socket ({@code wss://ws.noamm.org}, spec section 2).
 * <p>
 * <b>Grouping:</b> by Hypixel server code, announced in {@code dungeon_start} (spec 2.3). NoammAddons only
 * sends that - and only shares map/score data at all - when the run has teammates, so this adapter connects
 * only during a non-solo dungeon run with a known server code.
 * <p>
 * <b>Sign-in (spec 2.2):</b> sign a random 16-byte nonce with the player's Mojang chat-signing key (the same
 * {@link ProfileKeyPair} {@code relay.RelayAuth} already uses for our own relay, reached the same way), POST it
 * with the Mojang-signed public key to {@code api.noamm.org/hypixel/auth}, get a bearer token, open the socket
 * with {@code name} + {@code token} as query parameters. The access token is never involved; the private key
 * never leaves {@code Signature}. {@code "mod"} identifies honestly as this mod - see the staging notes for
 * what happens if their server only accepts its own id.
 */
final class NoammAdapter extends SocketAdapter {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    /** NoammAddons' Ktor client pings every 10 s (spec 2.5); a little slower is plenty to keep a proxy's idle
     *  timer from closing us. */
    private static final long PING_MS = 20_000L;
    static final String MOD_ID = "killer560s-mod";

    // client thread -> worker
    private volatile String selfName;
    private volatile UUID selfUuid;

    // worker only
    private volatile NoammCodec.Token token;
    private long lastPingMs;
    private volatile boolean startSent;
    private volatile String socketInfo = "";

    // client thread only
    private final Map<String, String> sent = new HashMap<>();
    private int seenRunEpoch = -1;

    NoammAdapter() {
        super("NoammAddons", DetectedMods.NOAMM_ADDONS);
    }

    @Override
    protected Desire decide(BridgeContext ctx) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isNoamm()) {
            return Desire.off(BridgeStatus.OFF, "");
        }
        if (DetectedMods.isLoaded(DetectedMods.NOAMM_ADDONS)) {
            return Desire.off(BridgeStatus.SKIPPED_INSTALLED, "");
        }
        if (!ctx.active()) {
            return Desire.off(BridgeStatus.WAITING, "needs Party Interop on");
        }
        if (!ctx.onHypixel()) {
            return Desire.off(BridgeStatus.NOT_ON_HYPIXEL, "");
        }
        if (!ctx.identityKnown()) {
            return Desire.off(BridgeStatus.WAITING, "account and player name differ");
        }
        selfName = ctx.selfName();
        selfUuid = ctx.selfUuid();
        if (!ctx.inDungeon()) {
            return Desire.runFinished("not in a dungeon");
        }
        if (ctx.teammates().isEmpty()) {
            return Desire.off(BridgeStatus.WAITING, "solo run - NoammAddons only shares with a party");
        }
        if (ctx.serverCode() == null) {
            return Desire.off(BridgeStatus.WAITING, "no server code on the sidebar yet");
        }
        return Desire.connect(ctx.serverCode());
    }

    // ------------------------------------------------------------------ worker: sign-in + socket

    @Override
    protected CompletableFuture<WebSocket> openSocket(String target, WebSocket.Listener listener) {
        String name = selfName;
        if (name == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no identity yet"));
        }
        startSent = false;
        CompletableFuture<NoammCodec.Token> tok;
        if (token != null && !token.needsRefresh(System.currentTimeMillis())) {
            tok = CompletableFuture.completedFuture(token);
        } else {
            markAuthenticating();
            tok = authenticate();
        }
        return tok.thenCompose(t -> {
            token = t; // written on an HTTP thread, read next on the worker after whenComplete hands over
            WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(HTTP_TIMEOUT);
            try {
                builder.header("User-Agent", userAgent());
            } catch (IllegalArgumentException ignored) {
                // A JDK that restricts this header just sends its default one.
            }
            return builder.buildAsync(NoammCodec.socketUri(name, t.token()), listener);
        });
    }

    private CompletableFuture<NoammCodec.Token> authenticate() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        UUID uuid = selfUuid;
        if (user == null || uuid == null || !uuid.equals(user.getProfileId())) {
            return CompletableFuture.failedFuture(new BridgeAuthException("signed-in account changed", false));
        }
        ProfileKeyPairManager keys = keyPairManager(mc);
        if (keys == null) {
            return CompletableFuture.failedFuture(new BridgeAuthException("Minecraft has no profile key manager", true));
        }
        return keys.prepareKeyPair().thenApply(optional -> {
            ProfileKeyPair pair = optional.orElse(null);
            if (pair == null) {
                throw new BridgeAuthException("Minecraft never issued this account a chat key", true);
            }
            if (pair.publicKey().data().hasExpired()) {
                throw new BridgeAuthException("your Minecraft chat key expired - restart the game", true);
            }
            return body(uuid, pair);
        }).thenCompose(body -> HTTP.sendAsync(HttpRequest.newBuilder(java.net.URI.create(NoammCodec.AUTH_URL))
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", userAgent())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        ).thenApply(response -> {
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                String why = response.body() == null ? "" : shorten(response.body());
                throw new BridgeAuthException("NoammAddons refused the sign-in (HTTP " + code + ")"
                        + (why.isEmpty() ? "" : ": " + why), false);
            }
            NoammCodec.Token t = NoammCodec.parseToken(response.body());
            if (t == null) {
                throw new BridgeAuthException("NoammAddons sent no usable token", false);
            }
            return t;
        });
    }

    /** Spec 2.2 steps 3-7. Signing happens here with the key object Minecraft already holds; nothing about
     *  the private key is ever serialised. */
    private static String body(UUID uuid, ProfileKeyPair pair) {
        ProfilePublicKey.Data data = pair.publicKey().data();
        byte[] nonce = NoammCodec.nonceBytes(UUID.randomUUID());
        byte[] signed;
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(pair.privateKey());
            sig.update(nonce);
            signed = sig.sign();
        } catch (Exception e) {
            throw new BridgeAuthException("couldn't sign the NoammAddons nonce (" + e.getClass().getSimpleName() + ")", true);
        }
        return NoammCodec.authBody(uuid.toString(),
                Base64.getEncoder().encodeToString(data.key().getEncoded()),
                Base64.getEncoder().encodeToString(data.keySignature()),
                data.expiresAt().toEpochMilli(), nonce, signed,
                MOD_ID, version("minecraft"), version("killer560smod"));
    }

    /** Same field-then-getter fallback as {@code RelayAuth.keyPairManager}. */
    private static ProfileKeyPairManager keyPairManager(Minecraft mc) {
        try {
            return ((MinecraftProfileKeysAccessor) (Object) mc).killer560smod$getProfileKeyPairManager();
        } catch (Throwable ignored) {
            return mc.getProfileKeyPairManager();
        }
    }

    private static String version(String modId) {
        try {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String userAgent() {
        return MOD_ID + "/" + version("killer560smod") + " (cross-mod bridge)";
    }

    @Override
    protected boolean isAuthRejection(Throwable error) {
        if (error instanceof WebSocketHandshakeException hs) {
            int code = hs.getResponse().statusCode();
            return code == 401 || code == 403;
        }
        return false;
    }

    @Override
    protected void dropCachedCredential() {
        token = null;
    }

    @Override
    protected void onReady(String target) {
        lastPingMs = System.currentTimeMillis();
        socketInfo = "";
    }

    @Override
    protected void onPump(long nowMs) {
        if (nowMs - lastPingMs >= PING_MS) {
            lastPingMs = nowMs;
            sendPing();
        }
    }

    @Override
    protected boolean onTargetChanged(String oldTarget, String newTarget) {
        // A different Hypixel instance is a different lobby: clear the old one, then a fresh dungeon_start.
        if (startSent) {
            sendNow(NoammCodec.reset());
        }
        startSent = false;
        return true;
    }

    @Override
    protected List<String> goodbye(Desire why) {
        if (!startSent) {
            return List.of();
        }
        // spec 2.3: dungeon_end on run end, reset on a world change while still in a dungeon
        return why.runOver() ? List.of(NoammCodec.dungeonEnd(), NoammCodec.reset()) : List.of(NoammCodec.reset());
    }

    @Override
    protected String connectedDetail() {
        return socketInfo;
    }

    @Override
    protected void onMessage(String text) {
        NoammCodec.Message msg = NoammCodec.decode(text);
        if (msg == null || msg instanceof NoammCodec.Chat) {
            return; // /ws chat is free text from anyone on that server, with no dungeon meaning - not shown
        }
        if (msg instanceof NoammCodec.SocketInfo info) {
            socketInfo = info.usersInLobby() + " in lobby, " + info.connectedUsers() + " online";
            refreshConnectedDetail();
            return;
        }
        BridgeFeature.applyOnClientThread(() -> BridgeFeature.applyNoamm(msg));
    }

    // ------------------------------------------------------------------ client thread: outbound

    @Override
    protected void resetOutbound() {
        sent.clear();
    }

    @Override
    protected void produceOutbound(BridgeContext ctx) {
        if (ctx.runEpoch() != seenRunEpoch) {
            seenRunEpoch = ctx.runEpoch();
            sent.clear();
        }
        if (!sent.containsKey("start")) {
            if (ctx.entranceCol() < 0 || ctx.floor() == null) {
                return; // the server learns which lobby we are in from dungeon_start - nothing before it
            }
            List<String> members = new ArrayList<>();
            members.add(ctx.selfName());
            members.addAll(ctx.teammates());
            String start = NoammCodec.dungeonStart(ctx.serverCode(), ctx.floor(), members, ctx.entranceCol(), ctx.entranceRow());
            if (start == null || !offer(start)) {
                return;
            }
            sent.put("start", start);
            startSent = true;
        }
        for (PartyDataFeature.SelfFact fact : ctx.selfFacts()) {
            String frame = translate(fact);
            if (frame != null) {
                send(fact.id(), frame);
            }
        }
        for (BridgeContext.RoomCell cell : ctx.roomCells()) {
            String frame = NoammCodec.room(cell.name(), cell.x(), cell.z(), cell.col(), cell.row(), cell.isSeparator());
            if (frame != null) {
                send("cell:" + cell.col() + "," + cell.row(), frame);
            }
        }
    }

    private void send(String id, String frame) {
        if (frame.equals(sent.get(id))) {
            return;
        }
        if (offer(frame)) {
            sent.put(id, frame);
        }
    }

    /** One of our SELF facts in NoammAddons' shape, or null when their protocol has no equivalent. */
    private static String translate(PartyDataFeature.SelfFact fact) {
        JsonObject p = fact.payload();
        switch (fact.key()) {
            case PartyDataFeature.KEY_FLAG -> {
                String flag = BridgeFeature.str(p, "flag");
                if ("mimic".equals(flag)) {
                    return NoammCodec.mimic();
                }
                if ("prince".equals(flag)) {
                    return NoammCodec.prince();
                }
                if ("bat".equals(flag)) {
                    return NoammCodec.bat();
                }
                return null; // blood_opened / blood_done: NoammAddons has no message for these
            }
            case PartyDataFeature.KEY_ROOM_SECRETS -> {
                Integer found = BridgeFeature.integer(p, "found");
                return found == null ? null : NoammCodec.roomSecrets(BridgeFeature.str(p, "room"), found);
            }
            case PartyDataFeature.KEY_DRAGON -> {
                // NoammAddons' SPAWN puts the dragon straight to ALIVE on the receiver (S2CPacketM7Dragon.kt), so
                // it means our "alive", not our earlier "spawning" (particles) - that has no NoammAddons equivalent.
                if (!"alive".equals(BridgeFeature.str(p, "event"))) {
                    return null;
                }
                return NoammCodec.dragon(BridgeTables.NOAMM_DRAGON_SPAWN, BridgeFeature.str(p, "colour"));
            }
            case PartyDataFeature.KEY_DOOR -> {
                Integer x = BridgeFeature.integer(p, "x"), z = BridgeFeature.integer(p, "z");
                Integer col = BridgeFeature.integer(p, "col"), row = BridgeFeature.integer(p, "row");
                if (x == null || z == null || col == null || row == null) {
                    return null;
                }
                return NoammCodec.door(x, z, col, row, BridgeFeature.str(p, "type"));
            }
            default -> {
                // counters: no NoammAddons message. KEY_ROOM: sent per cell from roomCells instead, the way
                // NoammAddons' own scanner does.
                return null;
            }
        }
    }
}
