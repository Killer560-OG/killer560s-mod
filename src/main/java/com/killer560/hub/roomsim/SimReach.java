package com.killer560.hub.roomsim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.relay.RelayCalls;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModNet;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sim invites, the receiver's side (coopsim phase 3; contract in docs/SIM-COOP.md "Invites"): while "Receive Sim
 * Invites" is ON the client keeps ONE hibernating relay socket ({@code /simreach}) through which a room owner's invite
 * arrives. Connected lazily from a client tick (never at boot), backed off 5 s .. 10 min on failure, reconnected on an
 * account switch, closed when the setting goes OFF and on client stop. It sends only WebSocket ping frames (every 50 s,
 * answered by the relay runtime for free) and the block list. An invite carries a key that skips the room's password;
 * the receiver joins with it, declines (nothing is sent) or blocks the sender.
 */
public final class SimReach {

    /** One received invite; {@code id} is what /siminvite and the Join page name it by. */
    public record Invite(int id, String from, String fromUuid, String roomId, String room, String key, long expires,
                         boolean locked) {
    }

    private static final long MIN_BACKOFF_MS = 5_000, MAX_BACKOFF_MS = 600_000, PING_MS = 50_000;
    private static final int MAX_PENDING = 10;

    private static HttpClient http;
    private static volatile WebSocket socket;
    private static volatile boolean connecting, blocksDirty = true;
    /** Bumped on every close, so callbacks of an old socket do nothing. */
    private static volatile int generation;
    private static long nextTryMs, backoffMs = MIN_BACKOFF_MS, lastPingMs;
    private static String account = "";
    private static int ticks, nextId = 1, received, connects;
    private static final List<Invite> PENDING = new ArrayList<>();

    private SimReach() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (++ticks % 20 == 0) tick(c);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> close());
        ClientCommandRegistrationCallback.EVENT.register((d, r) -> d.register(ClientCommands.literal("siminvite")
                .then(action("join")).then(action("decline")).then(action("block"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
            action(String what) {
        return ClientCommands.literal(what).then(ClientCommands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
            int id = IntegerArgumentType.getInteger(ctx, "id");
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> act(mc, what, id));
            return 1;
        }));
    }

    /** The setting or the block list changed (any thread): the next tick connects, closes or sends the list. */
    static void settingsChanged() {
        blocksDirty = true;
        nextTryMs = 0;
        backoffMs = MIN_BACKOFF_MS;
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        synchronized (PENDING) {
            PENDING.removeIf(i -> i.expires() <= now);
        }
        var user = mc.getUser();
        String who = user == null ? "" : String.valueOf(user.getProfileId());
        boolean wanted = SimCoopConfig.get().receiveInvites() && !who.isEmpty();
        if (!wanted || !who.equals(account)) {
            if (socket != null || connecting) close();
            account = who;
            if (!wanted) return;
        }
        WebSocket ws = socket;
        if (ws == null) {
            if (!connecting && now >= nextTryMs) connect();
            return;
        }
        if (now - lastPingMs >= PING_MS) {
            lastPingMs = now;
            ws.sendPing(ByteBuffer.allocate(0));
        }
        if (blocksDirty) {
            blocksDirty = false;
            JsonObject o = new JsonObject();
            o.addProperty("type", "blocks");
            JsonArray a = new JsonArray();
            SimCoopConfig.get().blocked().keySet().forEach(a::add);
            o.add("uuids", a);
            ws.sendText(o.toString(), true);
        }
    }

    private static void connect() {
        connecting = true;
        int gen = generation;
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL).replaceFirst("^http", "ws");
        RelayCalls.bearer().thenCompose(token -> {
            String url = ModNet.url("simrooms-relay", base + "/simreach?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&mc=" + URLEncoder.encode(SimGuest.mc(), StandardCharsets.UTF_8) + "&protocol=" + SimCoop.PROTOCOL);
            return ModNet.webSocket(client().newWebSocketBuilder(), URI.create(url), new Listener(gen));
        }).whenComplete((ws, err) -> {
            if (gen != generation) {
                if (ws != null) ws.abort();
                return;
            }
            connecting = false;
            if (err != null) failed("connect: " + err);
        });
    }

    private static synchronized HttpClient client() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        return http;
    }

    private static void failed(String why) {
        socket = null;
        nextTryMs = System.currentTimeMillis() + backoffMs + ThreadLocalRandom.current().nextLong(backoffMs / 2 + 1);
        SimCoop.LOGGER.info("[SimReach] {}; next try in {} s", why, (nextTryMs - System.currentTimeMillis()) / 1000);
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
    }

    /** Closes the socket (setting OFF, account switch, client stop); the receiver is then not reachable. */
    private static void close() {
        generation++;
        connecting = false;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "off").exceptionally(e -> null);
    }

    private static final class Listener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder text = new StringBuilder();

        Listener(int gen) { this.gen = gen; }

        @Override
        public void onOpen(WebSocket ws) {
            if (gen != generation) {
                ws.abort();
                return;
            }
            socket = ws; // stored here, before any message can arrive (a buildAsync callback can come after the first one)
            connects++;
            backoffMs = MIN_BACKOFF_MS;
            blocksDirty = true;
            lastPingMs = System.currentTimeMillis();
            SimCoop.LOGGER.info("[SimReach] connected (receiving sim invites)");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (text.length() + data.length() <= 8192) text.append(data);
            if (last) {
                String s = text.toString();
                text.setLength(0);
                if (gen == generation) onMessage(s);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            if (gen == generation) failed("closed " + code + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (gen == generation) failed("error " + error);
        }
    }

    private static void onMessage(String s) {
        JsonObject o;
        try {
            o = JsonParser.parseString(s).getAsJsonObject();
            if (!"invite".equals(str(o, "type"))) return;
        } catch (RuntimeException e) {
            return;
        }
        String fromUuid = str(o, "fromUuid");
        if (SimCoopConfig.get().blocked().containsKey(fromUuid.replace("-", "").toLowerCase(java.util.Locale.ROOT))) return;
        Invite inv;
        synchronized (PENDING) {
            PENDING.removeIf(i -> i.roomId().equals(str(o, "roomId"))); // a re-invite replaces the old one
            inv = new Invite(nextId++, cut(str(o, "from"), 16), fromUuid, str(o, "roomId"), cut(str(o, "room"), 32),
                    str(o, "invite"), o.has("expires") ? o.get("expires").getAsLong() : System.currentTimeMillis() + 600_000L,
                    o.has("locked") && o.get("locked").getAsBoolean());
            PENDING.add(inv);
            while (PENDING.size() > MAX_PENDING) PENDING.removeFirst();
            received++;
        }
        SimCoop.LOGGER.info("[SimReach] invite {} from {} to {}", inv.id(), inv.from(), inv.room());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> show(mc, inv));
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    /** Render thread: a chat line with [Join] [Decline] [Block] in a world, a prompt on the title or sim menus. */
    private static void show(Minecraft mc, Invite inv) {
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.4f, 0.8f));
        if (mc.level != null && mc.player != null) {
            ModChat.send("Sim", ModChat.value(inv.from()).append(ModChat.text(" invited you to ")).append(ModChat.value(inv.room())));
            ModChat.send("Sim", link("[Join]", "join", inv.id(), ChatFormatting.GREEN).append(Component.literal("  "))
                    .append(link("[Decline]", "decline", inv.id(), ChatFormatting.GRAY)).append(Component.literal("  "))
                    .append(link("[Block]", "block", inv.id(), ChatFormatting.RED)));
            return;
        }
        Screen now = McCompat.screen(mc);
        if (now == null || now instanceof TitleScreen || now.getClass().getPackageName().equals(SimReach.class.getPackageName())) {
            McCompat.setScreen(mc, new ConfirmScreen(ok -> {
                McCompat.setScreen(mc, now);
                act(mc, ok ? "join" : "decline", inv.id());
            }, Component.literal("Sim Invite"), Component.literal(inv.from() + " invited you to " + inv.room() + "."),
                    Component.literal("Join"), Component.literal("Decline")));
        }
    }

    private static MutableComponent link(String label, String what, int id, ChatFormatting color) {
        String cmd = "/siminvite " + what + " " + id;
        return Component.literal(label).withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(cmd)).withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd))));
    }

    /** Render thread: join, decline or block invite {@code id}. Join leaves the current world first. */
    static void act(Minecraft mc, String what, int id) {
        Invite inv;
        synchronized (PENDING) {
            inv = PENDING.stream().filter(i -> i.id() == id).findFirst().orElse(null);
            if (inv != null) PENDING.remove(inv);
        }
        if (inv == null) {
            ModChat.send("Sim", ModChat.text("That invite is gone (it lasts 10 minutes)."));
            return;
        }
        switch (what) {
            case "block" -> {
                SimCoopConfig.get().setBlocked(inv.fromUuid(), inv.from(), true);
                synchronized (PENDING) {
                    PENDING.removeIf(i -> i.fromUuid().equals(inv.fromUuid()));
                }
                ModChat.send("Sim", ModChat.text("Blocked sim invites from " + inv.from() + "."));
            }
            case "join" -> join(mc, inv);
            default -> { } // a decline sends nothing
        }
    }

    private static void join(Minecraft mc, Invite inv) {
        if (SimHost.hosting()) {
            ModChat.send("Sim", ModChat.text("Close your own room before joining " + inv.room() + "."));
            return;
        }
        SimMultiplayerScreen page = new SimMultiplayerScreen(SimMenuNav.open(new TitleScreen()), SimMultiplayerScreen.View.JOIN);
        if (mc.level != null) {
            mc.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
            mc.disconnect(page, false);
        } else {
            McCompat.setScreen(mc, page);
        }
        page.join(inv.roomId(), inv.room(), null, inv.key());
    }

    /** Live invites, oldest first (the Join page lists them). */
    public static List<Invite> pending() {
        synchronized (PENDING) {
            return List.copyOf(PENDING);
        }
    }

    // ---- test hooks ----

    public static boolean connectedForTest() { return socket != null; }

    public static int receivedForTest() { return received; }

    public static int connectsForTest() { return connects; }

    /** Drops the socket and connects again on the next tick (a test pointed the relay at a local one). */
    public static void reconnectForTest() {
        close();
        settingsChanged();
    }

    /** Joins, declines or blocks invite {@code id} as the chat links do (render thread). */
    public static void actForTest(String what, int id) { act(Minecraft.getInstance(), what, id); }
}
