package com.killer560.hub.shorts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny Chrome DevTools Protocol client over {@link java.net.http.WebSocket} - just enough to send commands
 * ({@code Input.dispatchKeyEvent}, {@code Runtime.evaluate}, {@code Browser.close}) to the Shorts browser
 * without ever focusing its window. Every blocking call here must only be made from a background thread.
 */
final class CdpClient implements WebSocket.Listener {

    static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static final long COMMAND_TIMEOUT_MS = 5000;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final StringBuilder partial = new StringBuilder();
    private final Runnable onClosed;
    private volatile WebSocket socket;
    private volatile boolean open;

    private CdpClient(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    /** Blocking GET of a DevTools HTTP endpoint (e.g. /json/list). */
    static String httpGet(int port, String path, long timeoutMs) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + path);
        }
        return resp.body();
    }

    /** @return the webSocketDebuggerUrl of the best "page" target (a youtube.com one if present), or null. */
    static String findPageWebSocketUrl(int port) throws Exception {
        JsonArray targets = JsonParser.parseString(httpGet(port, "/json/list", 2000)).getAsJsonArray();
        String fallback = null;
        for (JsonElement el : targets) {
            JsonObject t = el.getAsJsonObject();
            if (!t.has("type") || !"page".equals(t.get("type").getAsString()) || !t.has("webSocketDebuggerUrl")) {
                continue;
            }
            String ws = t.get("webSocketDebuggerUrl").getAsString();
            String url = t.has("url") ? t.get("url").getAsString() : "";
            if (url.contains("youtube.com")) {
                return ws;
            }
            if (fallback == null) {
                fallback = ws;
            }
        }
        return fallback;
    }

    /** @return the browser-level webSocketDebuggerUrl from /json/version, or null. */
    static String findBrowserWebSocketUrl(int port, long timeoutMs) throws Exception {
        JsonObject v = JsonParser.parseString(httpGet(port, "/json/version", timeoutMs)).getAsJsonObject();
        return v.has("webSocketDebuggerUrl") ? v.get("webSocketDebuggerUrl").getAsString() : null;
    }

    /** Blocking connect. */
    static CdpClient connect(String wsUrl, Runnable onClosed) throws Exception {
        CdpClient client = new CdpClient(onClosed);
        client.socket = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create(wsUrl), client)
                .get(5, TimeUnit.SECONDS);
        client.open = true;
        return client;
    }

    boolean isOpen() {
        return open;
    }

    /** Sends a command; the future completes with the "result" object or exceptionally on error/timeout. */
    CompletableFuture<JsonObject> send(String method, JsonObject params) {
        WebSocket ws = socket;
        if (!open || ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("CDP not connected"));
        }
        int id = nextId.getAndIncrement();
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params == null ? new JsonObject() : params);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        synchronized (this) {
            try {
                ws.sendText(msg.toString(), true).get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                pending.remove(id);
                future.completeExceptionally(e);
                return future;
            }
        }
        return future.orTimeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((r, t) -> pending.remove(id));
    }

    /** Blocking Runtime.evaluate returning the by-value result (JsonElement, possibly null). */
    JsonElement evaluate(String expression) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        params.addProperty("userGesture", true);
        JsonObject result = send("Runtime.evaluate", params).get(COMMAND_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
        if (result.has("exceptionDetails")) {
            throw new IllegalStateException("JS exception: " + result.get("exceptionDetails"));
        }
        JsonObject r = result.has("result") ? result.getAsJsonObject("result") : null;
        return r != null && r.has("value") ? r.get("value") : null;
    }

    /** Blocking key press (keyDown + keyUp) delivered straight to the page - no window focus needed. */
    void pressKey(String key, String code, int windowsVirtualKeyCode, String text) throws Exception {
        JsonObject down = new JsonObject();
        down.addProperty("type", text != null ? "keyDown" : "rawKeyDown");
        down.addProperty("key", key);
        down.addProperty("code", code);
        down.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
        down.addProperty("nativeVirtualKeyCode", windowsVirtualKeyCode);
        if (text != null) {
            down.addProperty("text", text);
            down.addProperty("unmodifiedText", text);
        }
        send("Input.dispatchKeyEvent", down).get(COMMAND_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
        JsonObject up = new JsonObject();
        up.addProperty("type", "keyUp");
        up.addProperty("key", key);
        up.addProperty("code", code);
        up.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
        up.addProperty("nativeVirtualKeyCode", windowsVirtualKeyCode);
        send("Input.dispatchKeyEvent", up).get(COMMAND_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
    }

    void close() {
        WebSocket ws = socket;
        boolean wasOpen = open;
        open = false;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
            }
            try {
                ws.abort();
            } catch (Exception ignored) {
            }
        }
        failPending("CDP closed");
        if (wasOpen) {
            onClosed.run();
        }
    }

    private void failPending(String reason) {
        for (CompletableFuture<JsonObject> f : pending.values()) {
            f.completeExceptionally(new IllegalStateException(reason));
        }
        pending.clear();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                if (msg.has("id")) {
                    CompletableFuture<JsonObject> f = pending.remove(msg.get("id").getAsInt());
                    if (f != null) {
                        if (msg.has("error")) {
                            f.completeExceptionally(new IllegalStateException("CDP error: " + msg.get("error")));
                        } else {
                            f.complete(msg.has("result") ? msg.getAsJsonObject("result") : new JsonObject());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        handleDrop();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        handleDrop();
    }

    private void handleDrop() {
        boolean wasOpen = open;
        open = false;
        failPending("CDP connection dropped");
        if (wasOpen) {
            onClosed.run();
        }
    }
}
