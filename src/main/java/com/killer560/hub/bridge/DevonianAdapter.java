package com.killer560.hub.bridge;

import com.google.gson.JsonObject;
import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.partydata.PartyDataFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Devonian's party socket ({@code ws://wss.docilelm.top/}, spec section 1).
 * <p>
 * <b>Grouping:</b> Devonian groups by {@code Party[<sum of member UUID hashCodes>]} (spec 1.3), which it gets
 * from Hypixel's Mod API party packet. This mod has no Mod API dependency, so {@link BridgeFeature} only
 * produces the hash when it can resolve EVERY party member to a real UUID from the players Hypixel has sent
 * this client - in practice, inside a dungeon run, where the whole party is in the same instance. A wrong
 * member set would just put us alone in a room nobody else is in, so "unknown" means "do not connect", never
 * "guess".
 * <p>
 * <b>Sign-in (spec 1.2):</b> random {@code r}; Mojang {@code joinServer(uuid, accessToken,
 * serverIdHash(r))} - the access token goes to Mojang's session server only, via authlib, exactly as vanilla
 * does when joining any server - then {@code Authenticate[<ign>, <r>]} on the socket. The server sends no
 * acknowledgement (spec 1.2 step 7), so the connection counts as signed in once it has stayed open
 * {@link #VERIFY_MS} after that frame; a close inside that window counts as a sign-in failure.
 * <p>
 * <b>Carried:</b> in, {@code RoomSecrets} (mapped through {@link BridgeTables}) and {@code SecretTracker}
 * (teammates only); out, {@code RoomSecrets} for rooms whose secret count we worked out ourselves. Devonian has
 * no message for flags, counters, doors or dragons.
 */
final class DevonianAdapter extends SocketAdapter {

    static final long VERIFY_MS = 3_000L;
    /** Answer the server's "3002" re-announce request at most this often - a misbehaving server must not be
     *  able to make us loop. */
    private static final long REANNOUNCE_MIN_MS = 30_000L;
    private static final String TARGET_PREFIX = "party:";

    // client thread -> worker
    private volatile String selfName;
    private volatile UUID selfUuid;

    // worker only
    private volatile String pendingR;
    private boolean partyAnnounced;
    private long lastReannounceMs;

    // client thread only: SelfFact id -> found count last queued
    private final Map<String, Integer> sentRoomSecrets = new HashMap<>();
    private int seenRunEpoch = -1;

    DevonianAdapter() {
        super("Devonian", DetectedMods.DEVONIAN);
    }

    @Override
    protected Desire decide(BridgeContext ctx) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isDevonian()) {
            return Desire.off(BridgeStatus.OFF, "");
        }
        if (DetectedMods.isLoaded(DetectedMods.DEVONIAN)) {
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
        if (ctx.devonianPartyHash() == null) {
            return ctx.inDungeon()
                    ? Desire.off(BridgeStatus.WAITING, ctx.teammates().isEmpty() ? "solo run" : "party UUIDs not all known yet")
                    : Desire.runFinished("not in a dungeon party");
        }
        return Desire.connect(TARGET_PREFIX + ctx.devonianPartyHash());
    }

    @Override
    protected CompletableFuture<WebSocket> openSocket(String target, WebSocket.Listener listener) {
        String name = selfName;
        UUID uuid = selfUuid;
        if (name == null || uuid == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no identity yet"));
        }
        markAuthenticating();
        String r = UUID.randomUUID().toString(); // spec 1.2 step 2: fresh per attempt, canonical form
        String serverId = DevonianCodec.serverIdHash(r);
        return CompletableFuture.supplyAsync(() -> {
            Minecraft mc = Minecraft.getInstance();
            User user = mc.getUser();
            // Only ever the signed-in account's own identity, and only if it is still the one decide() saw.
            if (user == null || !uuid.equals(user.getProfileId()) || !name.equals(user.getName())) {
                throw new BridgeAuthException("signed-in account changed", false);
            }
            try {
                mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Exception e) {
                // Never log the exception detail - some authlib messages include request context.
                throw new BridgeAuthException("Mojang session check failed (offline account or expired login)", false);
            }
            return r;
        }, AUTH_POOL).thenCompose(ok -> HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(DevonianCodec.URL), listener)
                .thenApply(ws -> {
                    pendingR = ok;
                    return ws;
                }));
    }

    @Override
    protected long onOpened(String target) {
        partyAnnounced = false;
        sendNow(DevonianCodec.authenticate(selfName, pendingR));
        pendingR = null;
        return VERIFY_MS;
    }

    @Override
    protected void onReady(String target) {
        announce(target, false);
    }

    @Override
    protected boolean onTargetChanged(String oldTarget, String newTarget) {
        announce(newTarget, true); // spec 1.3: PartyLeave first, then the new Party[hash]
        return true;
    }

    private void announce(String target, boolean leaveFirst) {
        Integer hash = hashOf(target);
        if (hash == null) {
            return;
        }
        if (leaveFirst && partyAnnounced) {
            sendNow(DevonianCodec.PARTY_LEAVE);
        }
        sendNow(DevonianCodec.party(hash));
        partyAnnounced = true;
    }

    @Override
    protected void onMessage(String text) {
        DevonianCodec.Message msg = DevonianCodec.decode(text);
        if (msg == null) {
            return;
        }
        if (msg instanceof DevonianCodec.Reannounce) {
            long now = System.currentTimeMillis();
            if (partyAnnounced && now - lastReannounceMs >= REANNOUNCE_MIN_MS) {
                lastReannounceMs = now;
                // spec 1.3: on "3002" while in a party, PartyLeave then Party[hash] again
                sendNow(DevonianCodec.PARTY_LEAVE);
                String target = currentTarget();
                Integer hash = hashOf(target);
                if (hash != null) {
                    sendNow(DevonianCodec.party(hash));
                }
            }
            return;
        }
        BridgeFeature.applyOnClientThread(() -> BridgeFeature.applyDevonian(msg));
    }

    @Override
    protected List<String> goodbye(Desire why) {
        // spec 1.3: PartyLeave on leaving a party, Disconnect when the feature is switched off
        return partyAnnounced ? List.of(DevonianCodec.PARTY_LEAVE, DevonianCodec.DISCONNECT)
                : List.of(DevonianCodec.DISCONNECT);
    }

    @Override
    protected void resetOutbound() {
        sentRoomSecrets.clear();
    }

    @Override
    protected void produceOutbound(BridgeContext ctx) {
        if (ctx.runEpoch() != seenRunEpoch) {
            seenRunEpoch = ctx.runEpoch();
            sentRoomSecrets.clear();
        }
        for (PartyDataFeature.SelfFact fact : ctx.selfFacts()) {
            if (!PartyDataFeature.KEY_ROOM_SECRETS.equals(fact.key())) {
                continue;
            }
            JsonObject p = fact.payload();
            String room = BridgeFeature.str(p, "room");
            Integer found = BridgeFeature.integer(p, "found");
            Integer total = BridgeFeature.integer(p, "total");
            BridgeTables.Room known = BridgeTables.room(room);
            // Devonian's receiver only applies a count whose total equals its own table's
            // (DungeonScanner.kt:229) - which agrees with ours for every room - so an unknown total is skipped.
            if (known == null || known.devonianId() < 0 || found == null || total == null
                    || total != known.secrets() || found < 0 || found > total) {
                continue;
            }
            Integer already = sentRoomSecrets.get(fact.id());
            if (already != null && already.intValue() == found) {
                continue;
            }
            if (offer(DevonianCodec.roomSecrets(found, total, known.devonianId()))) {
                sentRoomSecrets.put(fact.id(), found);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Integer hashOf(String target) {
        if (target == null || !target.startsWith(TARGET_PREFIX)) {
            return null;
        }
        try {
            return Integer.parseInt(target.substring(TARGET_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
