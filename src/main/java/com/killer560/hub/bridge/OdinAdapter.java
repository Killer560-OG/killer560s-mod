package com.killer560.hub.bridge;

import com.killer560.hub.interop.DetectedMods;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Odin's Melody socket ({@code wss://ws.odtheking.com/<server code>}, spec section 3). Receive-only.
 * <p>
 * <b>When:</b> exactly Odin's own window - from Goldor's P3 line to "The Core entrance is opening!" (spec 3.3),
 * in an F7/M7 run, while the sidebar shows a server code. Odin itself never retries a dropped socket inside that
 * window; we do, but never sooner than the shared 30 s minimum backoff, so in practice at most once or twice.
 * <p>
 * <b>Why nothing is sent:</b> Odin's protocol has no authentication at all - its {@code "user"} field is
 * whatever the sender claims (spec 3.2). The only thing it carries is Melody progress, and this mod has no
 * always-on SELF signal for our own Melody state: {@code TerminalSolverFeature} only tracks the lime/magenta
 * panes inside its Auto Melody path, a cheat-build click loop. Deriving a new one here would be a second Melody
 * detector, which is exactly the "re-derive instead of relay" this bridge is told not to do, so the outbound half
 * is left as a documented gap (see the staging notes) and this adapter never speaks. Inbound progress is only
 * accepted for players the party list says are in OUR party, never for arbitrary names.
 */
final class OdinAdapter extends SocketAdapter {

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
    }

    @Override
    protected void produceOutbound(BridgeContext ctx) {
        // Receive-only - see the class doc.
    }
}
