package com.killer560.hub.bridge;

import com.killer560.hub.interop.DetectedMods;
import com.killer560.hub.melody.MelodyTrackerFeature;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Odin's Melody socket ({@code wss://ws.odtheking.com/<server code>}, spec section 3).
 * <p>
 * <b>When:</b> exactly Odin's own window - from Goldor's P3 line to "The Core entrance is opening!" (spec 3.3),
 * in an F7/M7 run, while the sidebar shows a server code. Odin itself never retries a dropped socket inside that
 * window; we do, but never sooner than the shared 30 s minimum backoff, so in practice at most once or twice.
 * <p>
 * <b>Outbound (2026-09-21, was a documented gap):</b> {@code com.killer560.hub.melody.MelodyTrackerFeature} now
 * carries an always-on, legit, read-only SELF Melody signal (it reads the terminal's own contents each tick,
 * never clicks) - see its class doc. {@link #produceOutbound} polls {@link MelodyTrackerFeature#selfSnapshot()}
 * (never re-derives it) and sends whichever of the three fields changed since the last send, in exactly Odin's
 * own wire format ({@link OdinCodec#update}), each de-duplicated against its own last-sent value so a steady
 * board doesn't resend. Odin's protocol has no authentication at all - its {@code "user"} field is whatever the
 * sender claims (spec 3.2) - so only our own real, verified IGN ({@code ctx.selfName()}) is ever sent, never a
 * teammate's. Inbound progress is likewise only accepted for players the party list says are in OUR party,
 * never for arbitrary names.
 */
final class OdinAdapter extends SocketAdapter {

    /** Client thread only (see {@link #produceOutbound}/{@link #resetOutbound}): the last value of each field
     *  actually sent to Odin, so a steady board is not resent every tick. -1 = nothing sent yet (also reset on
     *  reconnect via {@link #resetOutbound}, matching every other adapter's own dedupe map). */
    private int sentClayRow = -1;
    private int sentTarget = -1;
    private int sentCurrent = -1;

    OdinAdapter() {
        super("Odin", DetectedMods.ODIN);
    }

    @Override
    protected Desire decide(BridgeContext ctx) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isOdin()) {
            return Desire.off(BridgeStatus.OFF, "");
        }
        if (DetectedMods.isLoaded(DetectedMods.ODIN)) {
            return Desire.off(BridgeStatus.SKIPPED_INSTALLED, "");
        }
        if (!ctx.active()) {
            return Desire.off(BridgeStatus.WAITING, "needs Party Interop on");
        }
        if (!ctx.onHypixel()) {
            return Desire.off(BridgeStatus.NOT_ON_HYPIXEL, "");
        }
        if (!ctx.inDungeon()) {
            return Desire.runFinished("not in a dungeon");
        }
        if (!"F7".equals(ctx.floor()) && !"M7".equals(ctx.floor())) {
            return Desire.runFinished("Melody is F7/M7 only");
        }
        if (!ctx.p3Active()) {
            return Desire.runFinished("waiting for Goldor (P3)");
        }
        if (ctx.teammates().isEmpty()) {
            return Desire.runFinished("solo run");
        }
        if (ctx.serverCode() == null) {
            return Desire.off(BridgeStatus.WAITING, "no server code on the sidebar yet");
        }
        return Desire.connect(ctx.serverCode());
    }

    @Override
    protected CompletableFuture<WebSocket> openSocket(String target, WebSocket.Listener listener) {
        URI uri = OdinCodec.socketUri(target);
        if (uri == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("not a server code"));
        }
        // spec 3.1: no headers, no subprotocol, no auth
        return HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(uri, listener);
    }

    @Override
    protected String closeReason() {
        return "Client shutdown"; // spec 3.3: Odin's own shutdown() close frame
    }

    @Override
    protected void onMessage(String text) {
        OdinCodec.Update update = OdinCodec.decode(text);
        if (update != null) {
            BridgeFeature.applyOnClientThread(() -> BridgeFeature.applyOdin(update));
        }
    }

    @Override
    protected void resetOutbound() {
        sentClayRow = -1;
        sentTarget = -1;
        sentCurrent = -1;
    }

    @Override
    protected void produceOutbound(BridgeContext ctx) {
        if (ctx.selfName() == null) {
            return; // spec 3.2/3.7: never send anything but our own verified name
        }
        MelodyTrackerFeature.SelfMelody self = MelodyTrackerFeature.selfSnapshot();
        if (self == null) {
            return; // our own terminal isn't open right now - nothing new to say
        }
        if (self.clayRow() != sentClayRow && self.clayRow() >= BridgeTables.MELODY_MIN_CLAY_ROW
                && self.clayRow() <= BridgeTables.MELODY_MAX_CLAY_ROW) {
            if (offer(OdinCodec.update(ctx.selfName(), BridgeTables.MELODY_TYPE_CLAY, self.clayRow()))) {
                sentClayRow = self.clayRow();
            }
        }
        if (self.target() != sentTarget && self.target() >= BridgeTables.MELODY_MIN_COLUMN
                && self.target() <= BridgeTables.MELODY_MAX_COLUMN) {
            if (offer(OdinCodec.update(ctx.selfName(), BridgeTables.MELODY_TYPE_PURPLE, self.target()))) {
                sentTarget = self.target();
            }
        }
        if (self.current() != sentCurrent && self.current() >= BridgeTables.MELODY_MIN_COLUMN
                && self.current() <= BridgeTables.MELODY_MAX_COLUMN) {
            if (offer(OdinCodec.update(ctx.selfName(), BridgeTables.MELODY_TYPE_PANE, self.current()))) {
                sentCurrent = self.current();
            }
        }
    }
}
