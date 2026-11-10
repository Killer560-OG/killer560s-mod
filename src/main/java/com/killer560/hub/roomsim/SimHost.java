package com.killer560.hub.roomsim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayCalls;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.HttpUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Co-op sim, phase 2: the owner's room (docs/SIM-COOP.md "Multiplayer"). Create opens the sim world to LAN on a free
 * port, asks the router to forward it ({@link SimUpnp}) and lists the room on the relay ({@link SimRooms}); only room
 * metadata goes through the relay, never game traffic. In a locked or listed room the host's server admits a guest only
 * with a ticket the relay issued (its {@code sim_hello_ack} names it) and kicks one without, ~12 s after it joined. Max
 * players holds on the server too. Client thread for the room, server thread for admission; the shared fields are
 * volatile or concurrent.
 */
public final class SimHost {

    /** One guest as the Session page lists it. */
    public record Guest(UUID uuid, String name, boolean admitted) {
    }

    private record Ticket(String nonce, long expiresMs) {
    }

    /** Server ticks a guest of a locked or listed room has to show its ticket. */
    static final int ADMIT_TICKS = 240;
    /** Client ticks between two relay refreshes (the contract: every 30 s, the room goes after 90 s without one). */
    private static final int REFRESH_TICKS = 600;
    private static final int OPEN_WAIT_TICKS = 20 * 90;

    private static volatile boolean active, pendingOpen, refreshNow, inFlight;
    private static volatile String name = "", password = "", roomId, secret, publicHost;
    private static volatile int max = 5, port = -1;
    private static volatile long roomKey;
    /** What Create shows: the port, the router's answer, the list's answer. */
    private static volatile String status = "", upnpLine = "", listLine = "";
    private static int refreshTicks, openTicks;
    private static volatile List<Guest> guests = List.of();

    /** Relay tickets by normalized uuid, and the ticket each guest's ack named. */
    private static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> CLAIMED = new ConcurrentHashMap<>();
    /** Server thread only: when each guest was first seen, and who is in. */
    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Set<UUID> ADMITTED = ConcurrentHashMap.newKeySet();
    /** Guests turned away as over Max, until they are gone (a disconnect takes a tick or two). */
    private static final Set<UUID> FULL = ConcurrentHashMap.newKeySet();
    private static volatile int kicks, fullRefusals;

    private SimHost() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SimHost::clientTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!active || !(server instanceof IntegratedServer)) return;
            if (server.getPlayerList().getPlayers().size() > max) refuseFull(handler.player);
            else landJoiner(server, handler.player);
        });
        // As the owner's own connection drops, while the guests' still carry the bye (by SERVER_STOPPING the
        // connection listener has already closed them: the guest read "Disconnected").
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (active && SimCoop.isHost(handler.player)) byeAll(server, "the owner left");
        });
    }

    /**
     * A joiner starts on the host's spot (a clear one, held for its chunks), not on the world spawn, which is the bedrock
     * roof above the sim. While a build runs everyone is at the holding area and is placed when it ends. Server thread.
     */
    private static void landJoiner(MinecraftServer server, ServerPlayer sp) {
        if (SimCoop.isHost(sp) || !SimState.isActive() || SimWorld.isBuilding()
                || com.killer560.hub.bosssim.BossSim.isBuilding()) return;
        for (ServerPlayer h : server.getPlayerList().getPlayers()) {
            if (SimCoop.isHost(h) && h.level() instanceof net.minecraft.server.level.ServerLevel level) {
                SimArrival.place(sp, level, h.getX(), h.getY(), h.getZ(), h.getYRot(), 0f);
                return;
            }
        }
    }

    /** Moves everyone to {@code at} (the host's world rebuilds that part); host only. Client thread. */
    public static void moveTo(Minecraft mc, com.killer560.hub.bosssim.BossFight.Start at) {
        SimCoopConfig.get().setStartAt(at);
        if (!active) return;
        var fight = com.killer560.hub.bosssim.BossFight.running();
        boolean master = at == com.killer560.hub.bosssim.BossFight.Start.P5
                || fight && com.killer560.hub.bosssim.BossFight.master();
        com.killer560.hub.bosssim.BossSim.start(mc, at, master);
    }

    public static boolean hosting() { return active; }

    /** Waiting for the sim world to open before publishing. */
    public static boolean opening() { return pendingOpen; }

    /** The hello's nonce: the listed room's key, 0 for a plain LAN room. */
    static long roomKey() { return roomKey; }

    public static String status() { return status; }

    public static String upnpLine() { return upnpLine; }

    public static String listLine() { return listLine; }

    public static String roomName() { return name; }

    public static int max() { return max; }

    public static int port() { return port; }

    public static boolean locked() { return !password.isEmpty(); }

    /** Every guest on the server (not the owner), refreshed every 10 server ticks. */
    public static List<Guest> guests() { return guests; }

    /** The public address guests outside his network use, or null when the router did not open the port. */
    public static String publicAddress() { return publicHost == null ? null : publicHost + ":" + port; }

    /** {kicked without a ticket or by the owner, refused as full}, for tests. */
    public static int[] countsForTest() { return new int[]{kicks, fullRefusals}; }

    public static String roomIdForTest() { return roomId; }
    /** The listed room and its owner secret (null while unlisted), for invites. */
    static String roomId() { return roomId; }

    static String secret() { return secret; }

    /**
     * Create. Null when it started, else why not. In the sim it publishes at once; elsewhere it opens the F7 boss (the
     * sim's single entry point, SimWorld) and publishes once the world is up.
     */
    public static String create(Minecraft mc, String roomName, String pw, int maxPlayers) {
        String n = roomName == null ? "" : roomName.trim();
        if (n.isEmpty() || n.length() > 32) return "The room name must be 1 to 32 characters.";
        if (pw != null && pw.length() > 64) return "The password can be at most 64 characters.";
        if (active || pendingOpen) return "You are already hosting a room.";
        name = n;
        password = pw == null ? "" : pw;
        max = Math.max(2, Math.min(5, maxPlayers));
        SimCoopConfig.get().setRoom(n, max);
        if (SimState.canAct(mc) && mc.getSingleplayerServer() != null) return publish(mc);
        if (!SimState.canOpen(mc)) return "The sim cannot open here.";
        pendingOpen = true;
        openTicks = 0;
        status = "Opening the sim world...";
        com.killer560.hub.bosssim.BossSim.start(mc, SimCoopConfig.get().startAt(), false);
        return null;
    }

    private static String publish(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return "The sim world is not open.";
        int p = server.isPublished() ? server.getPort() : HttpUtil.getAvailablePort();
        if (!server.isPublished() && !server.publishServer(MinecraftServer.MultiplayerScope.LAN, p)) {
            status = "Could not open the world on port " + p + ".";
            return status;
        }
        port = p;
        active = true;
        roomId = null;
        secret = null;
        roomKey = 0;
        publicHost = null;
        TICKETS.clear();
        CLAIMED.clear();
        ADMITTED.clear();
        refreshTicks = 0;
        status = "Open on port " + p + ".";
        upnpLine = "Asking your router to open the port...";
        listLine = "";
        SimCoop.LOGGER.info("[SimCoop] hosting \"{}\" on port {} ({} max{})", name, p, max, locked() ? ", locked" : "");
        SimUpnp.map(p).thenAccept(r -> {
            if (!active || port != p) return;
            upnpLine = r.ok() ? r.message() + ": anyone can join." : r.message();
            publicHost = r.ok() ? r.externalIp() : null;
            list();
        });
        return null;
    }

    /** Lists (or re-lists) the room on the relay. Any thread. */
    private static void list() {
        if (!active || inFlight) return;
        inFlight = true;
        listLine = "Listing the room...";
        SimRooms.create(name, password, max, mode(), publicHost, port).whenComplete((rep, err) -> {
            inFlight = false;
            if (!active) return;
            if (rep != null && rep.status() == 200 && rep.body() != null && rep.body().has("roomId")) {
                String id = rep.body().get("roomId").getAsString();
                secret = rep.body().get("hostSecret").getAsString();
                roomKey = key(id);
                roomId = id;
                listLine = "Listed in the room list" + (publicHost == null ? " (LAN only)." : ".");
                SimCoop.LOGGER.info("[SimCoop] room listed ({})", publicHost == null ? "LAN only" : "public");
            } else {
                String why = rep == null ? String.valueOf(err) : rep.error() == null ? "status " + rep.status() : rep.error();
                listLine = "Not in the room list (" + why + ")" + (locked()
                        ? ": a locked room cannot be joined until it is listed." : ": friends can still join by address.");
                SimCoop.LOGGER.warn("[SimCoop] room not listed: {}", why);
            }
        });
    }

    static long key(String roomId) {
        try {
            return Long.parseUnsignedLong(roomId.substring(0, 16), 16);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String mode() {
        String m = SimCoop.modeLabel().replaceAll("[^A-Za-z0-9_ -]", "").trim();
        return m.isEmpty() ? "sim" : m.length() > 24 ? m.substring(0, 24) : m;
    }

    private static void clientTick(Minecraft mc) {
        if (pendingOpen) {
            if (SimState.canAct(mc) && mc.getSingleplayerServer() != null && !SimWorld.isBuilding()
                    && !com.killer560.hub.bosssim.BossSim.isBuilding()) {
                pendingOpen = false;
                String why = publish(mc);
                if (why != null) status = why;
            } else if (++openTicks > OPEN_WAIT_TICKS) {
                pendingOpen = false;
                status = "The sim world did not open.";
            }
            return;
        }
        if (!active) return;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || !server.isPublished()) {
            end("the world closed");
            return;
        }
        if (++refreshTicks < REFRESH_TICKS && !refreshNow) return;
        refreshTicks = 0;
        refreshNow = false;
        String id = roomId;
        if (id == null) {
            list();
            return;
        }
        if (inFlight) return;
        inFlight = true;
        int players = Math.max(1, Math.min(max, server.getPlayerCount()));
        SimRooms.refresh(id, secret, players, mode(), publicHost, port).whenComplete((rep, err) -> {
            inFlight = false;
            if (rep == null || !active) return;
            if (rep.status() == 404) {
                roomId = null;
                refreshNow = true;
                return;
            }
            if (rep.status() != 200 || rep.body() == null || !rep.body().has("tickets")) return;
            for (JsonElement e : rep.body().getAsJsonArray("tickets")) {
                JsonObject t = e.getAsJsonObject();
                TICKETS.put(norm(t.get("uuid").getAsString()), new Ticket(t.get("nonce").getAsString(),
                        t.get("expires").getAsLong()));
            }
        });
    }

    /** Close Room on the Session page: guests are told, then the world goes back to single player. Client thread. */
    public static void close(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (active && server != null) {
            server.execute(() -> {
                byeAll(server, "the owner closed the room");
                server.unpublishServer();
            });
        }
        end("closed by the owner");
    }

    private static void end(String why) {
        boolean was = active;
        active = false;
        pendingOpen = false;
        String id = roomId, s = secret;
        roomId = null;
        roomKey = 0;
        guests = List.of();
        TICKETS.clear();
        CLAIMED.clear();
        if (id != null) SimRooms.delete(id, s);
        SimUpnp.unmap();
        if (was) {
            status = "Room closed (" + why + ").";
            SimCoop.LOGGER.info("[SimCoop] room closed: {}", why);
        }
    }

    /** Kick on the Session page. Client thread. */
    public static void kick(Minecraft mc, UUID uuid) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null && !SimCoop.isHost(sp)) kick(sp, "the owner kicked you");
        });
    }

    private static void kick(ServerPlayer sp, String bye) {
        kicks++;
        SimCoop.LOGGER.info("[SimCoop] kicked {}: {}", sp.getGameProfile().name(), SimCoop.byeText(bye));
        SimCoop.bye(sp, bye);
    }

    private static void byeAll(MinecraftServer server, String reason) {
        for (ServerPlayer sp : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!SimCoop.isHost(sp)) {
                SimCoop.LOGGER.info("[SimCoop] dropped {}: {}", sp.getGameProfile().name(), SimCoop.byeText(reason));
                SimCoop.bye(sp, reason);
            }
        }
    }

    /** Server thread: the guest's ack named {@code ticket} (blank for none). An unknown one asks the relay now. */
    static void acked(ServerPlayer sp, String ticket) {
        if (!active || ticket == null || ticket.isEmpty()) return;
        CLAIMED.put(sp.getUUID(), ticket);
        if (!TICKETS.containsKey(norm(sp.getUUID().toString()))) refreshNow = true;
    }

    /** Server thread, every 10 ticks from {@link SimCoop}: admits ticket holders, kicks the rest of a gated room. */
    static void serverTick(List<ServerPlayer> players, int tick) {
        if (!active) {
            if (!SEEN.isEmpty()) SEEN.clear();
            return;
        }
        boolean gated = locked() || roomId != null;
        List<Guest> list = new ArrayList<>(players.size());
        Set<UUID> present = new java.util.HashSet<>();
        int seats = 1;
        for (ServerPlayer sp : List.copyOf(players)) {
            if (SimCoop.isHost(sp)) continue;
            UUID id = sp.getUUID();
            present.add(id);
            // Max holds here too: a join path that skipped Fabric's JOIN event (players join in list order).
            if (FULL.contains(id) || ++seats > max) {
                if (!FULL.contains(id)) refuseFull(sp);
                continue;
            }
            int since = SEEN.computeIfAbsent(id, k -> tick);
            if (!ADMITTED.contains(id)) {
                String claim = CLAIMED.get(id);
                Ticket t = TICKETS.get(norm(id.toString()));
                if (claim != null && t != null && t.nonce().equalsIgnoreCase(claim)
                        && System.currentTimeMillis() <= t.expiresMs()) {
                    ADMITTED.add(id);
                    TICKETS.remove(norm(id.toString()));
                    SimCoop.LOGGER.info("[SimCoop] {} admitted with a ticket", sp.getGameProfile().name());
                } else if (gated && tick - since >= ADMIT_TICKS) {
                    kick(sp, "join through the Multiplayer menu");
                    continue;
                }
            }
            list.add(new Guest(id, sp.getGameProfile().name(), ADMITTED.contains(id)));
        }
        SEEN.keySet().retainAll(present);
        ADMITTED.retainAll(present);
        CLAIMED.keySet().retainAll(present);
        FULL.retainAll(present);
        // A guest came or went: tell the list now, so its player count frees the seat (else up to 30 s stale).
        if (list.size() != guests.size()) refreshNow = true;
        guests = List.copyOf(list);
    }

    private static void refuseFull(ServerPlayer sp) {
        FULL.add(sp.getUUID());
        fullRefusals++;
        SimCoop.LOGGER.info("[SimCoop] {} refused: the room is full ({})", sp.getGameProfile().name(), max);
        sp.connection.disconnect(Component.literal("This room is full."));
    }

    static String norm(String uuid) { return uuid.replace("-", "").toLowerCase(Locale.ROOT); }
}
