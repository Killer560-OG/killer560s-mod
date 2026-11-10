package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Co-op sim, the HOST half (docs/SIM-COOP.md): the integrated server of a sim world tells every other player on it
 * that this is the sim ({@code sim_hello}, then {@code sim_state} on each phase / floor / mode change) and keeps the
 * party list the tab shows. Nothing here writes a position, a block or an entity.
 */
public final class SimCoop {

    static final Logger LOGGER = ModLog.get("killer560smod-simcoop");
    /** Bumped on any change to a payload's fields; a guest refuses any other number. */
    public static final int PROTOCOL = 2;
    private static final int STR = 64;
    /** How often, in server ticks, the host greets new players and checks the state for a change. */
    private static final int EVERY = 10;

    /** What the host says about the sim: hello (first) and state (every change) carry the same fields. */
    public record Info(boolean hello, int proto, String modVersion, String mc, String mode, String floor,
                       boolean master, String phase, int section, String owner, long nonce)
            implements CustomPacketPayload {
        public Type<Info> type() { return hello ? HELLO : STATE; }

        /** The part that, when it changes, is sent again. */
        String sig() { return mode + '|' + floor + '|' + master + '|' + phase + '|' + section; }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(proto);
            b.writeUtf(modVersion, STR);
            b.writeUtf(mc, STR);
            b.writeUtf(mode, STR);
            b.writeUtf(floor, STR);
            b.writeBoolean(master);
            b.writeUtf(phase, STR);
            b.writeVarInt(section);
            b.writeUtf(owner, STR);
            b.writeLong(nonce);
        }

        static Info read(FriendlyByteBuf b, boolean hello) {
            return new Info(hello, b.readVarInt(), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR),
                    b.readBoolean(), b.readUtf(STR), b.readVarInt(), b.readUtf(STR), b.readLong());
        }
    }

    /**
     * The guest's answer: its build, so the host's log says who joined with what, and the relay ticket it joined with
     * (hex, blank for a plain LAN or address join), which {@link SimHost} checks in a locked or listed room.
     */
    public record Ack(int proto, String modVersion, String mc, String commit, String variant, String ticket)
            implements CustomPacketPayload {
        public Type<Ack> type() { return ACK; }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(proto);
            b.writeUtf(modVersion, STR);
            b.writeUtf(mc, STR);
            b.writeUtf(commit, STR);
            b.writeUtf(variant, STR);
            b.writeUtf(ticket, STR);
        }

        static Ack read(FriendlyByteBuf b) {
            return new Ack(b.readVarInt(), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR));
        }
    }

    /** Host -> guest, just before the host's server drops it: why ("the owner left"), for its Session Ended screen. */
    public record Bye(String reason) implements CustomPacketPayload {
        public Type<Bye> type() { return BYE; }
    }

    /**
     * Host -> guest, right after the host's server teleported it ({@link SimArrival#teleport}): hold the loading screen
     * until the chunks round {@code (x, y, z)} are in, then answer {@link Arrived} with the same {@code seq}.
     */
    public record Arrive(int seq, double x, double y, double z) implements CustomPacketPayload {
        public Type<Arrive> type() { return ARRIVE; }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(seq);
            b.writeDouble(x);
            b.writeDouble(y);
            b.writeDouble(z);
        }

        static Arrive read(FriendlyByteBuf b) {
            return new Arrive(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble());
        }
    }

    /** Guest -> host: the chunks round landing {@code seq} are in, so the host's server stops holding it. */
    public record Arrived(int seq) implements CustomPacketPayload {
        public Type<Arrived> type() { return ARRIVED; }
    }

    /** Guest -> host: a left-click PRESS with the Terminator in hand (Salvation or the shot, as the host's own). */
    public record Click() implements CustomPacketPayload {
        public Type<Click> type() { return CLICK; }
    }

    public static final CustomPacketPayload.Type<Info> HELLO = type("sim_hello");
    public static final CustomPacketPayload.Type<Info> STATE = type("sim_state");
    public static final CustomPacketPayload.Type<Ack> ACK = type("sim_hello_ack");
    public static final CustomPacketPayload.Type<Arrive> ARRIVE = type("sim_arrive");
    public static final CustomPacketPayload.Type<Arrived> ARRIVED = type("sim_arrived");
    public static final CustomPacketPayload.Type<Click> CLICK = type("sim_click");
    public static final CustomPacketPayload.Type<Bye> BYE = type("sim_bye");

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("killer560smod", path));
    }

    /** Players already greeted on this server (server thread only); cleared when the sim ends or they leave. */
    private static final Set<UUID> greeted = new HashSet<>();
    private static String lastSig;
    private static int ticks;
    /** Acks received, for tests. */
    private static volatile int acks;

    /** Host first, then the others in join order; read by the tab list on the client thread. */
    record Party(List<String> names, String key) {
    }

    private static volatile Party party = new Party(List.of(), "");

    /** Longest server-side hold, server ticks: a little past the guest's own 15 s, so its answer normally ends it. */
    private static final int HOLD_TICKS = 20 * 17;
    /** Server ticks between two put-backs of a guest that is falling while its chunks load. */
    private static final int PUT_EVERY = 5;

    /** A guest held after a far teleport, until it answers {@link Arrived}. Server thread only. */
    private static final class Hold {
        final int seq;
        final double x, y, z;
        final int since;
        int lastPut = -PUT_EVERY;
        int puts;

        Hold(int seq, double x, double y, double z, int since) {
            this.seq = seq;
            this.x = x;
            this.y = y;
            this.z = z;
            this.since = since;
        }
    }

    /** One per guest at most (a new landing replaces it); ends on its answer, the timeout or the guest leaving. */
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();
    private static int holdSeq;
    /** Holds started, answered and put-backs made, for tests. */
    private static volatile int holdsStarted, holdsAnswered, putBacks;

    private SimCoop() {
    }

    public static void register() {
        StreamCodec<FriendlyByteBuf, Info> hello = CustomPacketPayload.codec(Info::write, b -> Info.read(b, true));
        StreamCodec<FriendlyByteBuf, Info> state = CustomPacketPayload.codec(Info::write, b -> Info.read(b, false));
        PayloadTypeRegistry.clientboundPlay().register(HELLO, hello);
        PayloadTypeRegistry.clientboundPlay().register(STATE, state);
        PayloadTypeRegistry.serverboundPlay().register(ACK, CustomPacketPayload.codec(Ack::write, Ack::read));
        ServerPlayNetworking.registerGlobalReceiver(ACK, (ack, ctx) -> {
            acks++;
            LOGGER.info("[SimCoop] {} joined with protocol {} mod {} MC {} {} {}{}", ctx.player().getGameProfile().name(),
                    ack.proto(), ack.modVersion(), ack.mc(), ack.variant(), ack.commit(),
                    ack.ticket().isEmpty() ? "" : " (ticket)");
            SimHost.acked(ctx.player(), ack.ticket());
        });
        PayloadTypeRegistry.clientboundPlay().register(BYE,
                CustomPacketPayload.codec((m, b) -> b.writeUtf(m.reason(), STR), b -> new Bye(b.readUtf(STR))));
        PayloadTypeRegistry.clientboundPlay().register(ARRIVE, CustomPacketPayload.codec(Arrive::write, Arrive::read));
        PayloadTypeRegistry.serverboundPlay().register(ARRIVED,
                CustomPacketPayload.codec((a, b) -> b.writeVarInt(a.seq()), b -> new Arrived(b.readVarInt())));
        PayloadTypeRegistry.serverboundPlay().register(CLICK, StreamCodec.unit(new Click()));
        ServerPlayNetworking.registerGlobalReceiver(ARRIVED, (a, ctx) -> arrived(ctx.player(), a.seq()));
        ServerPlayNetworking.registerGlobalReceiver(CLICK, (c, ctx) -> SimTerminator.guestLeftClick(ctx.player()));
        ServerTickEvents.END_SERVER_TICK.register(SimCoop::serverTick);
        SimGuest.register();
        SimRequests.register();
        SimHost.register();
        SimCoopMap.register();
    }

    /**
     * Server thread: tells a guest why and drops it with the same words ("the owner left" -> "The owner left."). The
     * bye packet's task can be dropped with the connection, so the guest also reads the reason off the disconnect.
     */
    static void bye(ServerPlayer sp, String reason) {
        if (sp.connection == null) return;
        if (ServerPlayNetworking.canSend(sp, BYE.id())) ServerPlayNetworking.send(sp, new Bye(reason));
        sp.connection.disconnect(net.minecraft.network.chat.Component.literal(byeText(reason)));
    }

    /** The words a dropped guest sees: "the owner left" -> "The owner left.". */
    static String byeText(String reason) {
        return Character.toUpperCase(reason.charAt(0)) + reason.substring(1) + ".";
    }

    /** The bye a host's disconnect message carries ("The owner left." -> "the owner left"), or null for any other. */
    static String byeOf(net.minecraft.network.DisconnectionDetails details) {
        String s = details == null ? "" : details.reason().getString();
        return s.startsWith("The owner ") && s.endsWith(".") ? "t" + s.substring(1, s.length() - 1) : null;
    }

    /** Whether this server player is the one whose game is hosting (the singleplayer owner). */
    public static boolean isHost(ServerPlayer sp) {
        MinecraftServer server = sp.level().getServer();
        return server == null || server.isSingleplayerOwner(sp.nameAndId());
    }

    /** The tab's party: the real players while anyone joined, else just him (the single-player tab, unchanged). */
    static List<String> partyNames(Minecraft client) {
        Party p = party;
        if (p.names().size() > 1) return p.names();
        return List.of(client.player == null ? "" : client.player.getGameProfile().name());
    }

    /** One string that changes whenever {@link #partyNames} would, for the tab's change check. */
    static String partyKey(Minecraft client) {
        Party p = party;
        if (p.names().size() > 1) return p.key();
        return client.player == null ? "" : client.player.getGameProfile().name();
    }

    public static int acksForTest() { return acks; }

    /** {holds started, holds the guest answered, put-backs}, for tests. */
    public static int[] holdsForTest() { return new int[]{holdsStarted, holdsAnswered, putBacks}; }

    /**
     * Server thread, from every sim teleport: a co-op guest's client has to wait for the landing's chunks like his
     * does, but nothing on the guest may write its position. So the guest is told to hold its loading screen
     * ({@link Arrive}) and, until it answers, THIS server puts it back on the spot whenever it has fallen below it -
     * the same server-side teleport every sim landing is. No-op for him, a sim miniboss or a vanilla client.
     */
    static void hold(ServerPlayer sp, double x, double y, double z) {
        if (isHost(sp) || sp.connection == null || !ServerPlayNetworking.canSend(sp, ARRIVE.id())) return;
        Hold h = new Hold(++holdSeq, x, y, z, ticks);
        HOLDS.put(sp.getUUID(), h);
        holdsStarted++;
        ServerPlayNetworking.send(sp, new Arrive(h.seq, x, y, z));
    }

    /** Server thread: the guest has the chunks round landing {@code seq}; a stale answer for an older one is ignored. */
    private static void arrived(ServerPlayer sp, int seq) {
        Hold h = HOLDS.get(sp.getUUID());
        if (h == null || h.seq != seq) return;
        HOLDS.remove(sp.getUUID());
        holdsAnswered++;
        LOGGER.info("[SimCoop] {} has the chunks round {} {} {} after {} ticks ({} put-back(s))",
                sp.getGameProfile().name(), Math.round(h.x), Math.round(h.y), Math.round(h.z), ticks - h.since, h.puts);
    }

    private static void tickHolds(MinecraftServer server) {
        var it = HOLDS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Hold h = e.getValue();
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp == null || ticks - h.since > HOLD_TICKS) {
                if (sp != null) {
                    LOGGER.warn("[SimCoop] {} never confirmed the chunks round {} {} {} - released after {} ticks",
                            sp.getGameProfile().name(), Math.round(h.x), Math.round(h.y), Math.round(h.z), ticks - h.since);
                }
                it.remove();
                continue;
            }
            // Below the spot and still over it: it fell through chunks its client did not have yet. One sideways was a
            // later teleport or its own walk, never put back.
            double dx = sp.getX() - h.x, dz = sp.getZ() - h.z;
            if (sp.getY() < h.y - 0.2 && dx * dx + dz * dz < 4 && ticks - h.lastPut >= PUT_EVERY
                    && sp.level() instanceof ServerLevel level) {
                h.lastPut = ticks;
                h.puts++;
                putBacks++;
                SimArrival.move(sp, level, h.x, h.y, h.z, Set.of(Relative.Y_ROT, Relative.X_ROT), 0f, 0f);
            }
        }
    }

    private static void serverTick(MinecraftServer server) {
        ++ticks;
        if (!HOLDS.isEmpty()) tickHolds(server);
        Minecraft client = Minecraft.getInstance();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        updateSpots(players, SimState.isActive() && client.getSingleplayerServer() == server);
        if (ticks % EVERY != 0) return;
        if (client.getSingleplayerServer() == server) SimHost.serverTick(players, ticks);
        if (!SimState.isActive() || client.getSingleplayerServer() != server || players.size() < 2) {
            if (!greeted.isEmpty() || !party.names().isEmpty()) {
                greeted.clear();
                lastSig = null;
                party = new Party(List.of(), "");
            }
            return;
        }
        updateParty(players);
        Info state = info(server, false);
        boolean changed = !state.sig().equals(lastSig);
        lastSig = state.sig();
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer sp : players) {
            present.add(sp.getUUID());
            if (isHost(sp) || !ServerPlayNetworking.canSend(sp, HELLO.id())) continue;
            if (greeted.add(sp.getUUID())) {
                ServerPlayNetworking.send(sp, info(server, true));
                LOGGER.info("[SimCoop] hello to {} ({})", sp.getGameProfile().name(), state.sig());
            } else if (changed) {
                ServerPlayNetworking.send(sp, state);
            }
        }
        greeted.retainAll(present);
    }

    /** A co-op guest where the server last had it, for the host client's rules that read where players stand. */
    public record Spot(UUID uuid, String name, double x, double y, double z) {
    }

    private static volatile List<Spot> spots = List.of();

    /**
     * Every co-op guest (not him, no miniboss) as the server had it on its last tick; empty in single player, so a rule
     * that loops over it costs nothing there. Read on the client thread by rules written round {@code client.player}
     * (rooms entered, the tab's visited puzzles, pads): each also runs for these.
     */
    public static List<Spot> guestSpots() { return spots; }

    private static void updateSpots(List<ServerPlayer> players, boolean on) {
        if (!on || players.size() < 2) {
            if (!spots.isEmpty()) spots = List.of();
            return;
        }
        List<Spot> out = new ArrayList<>(players.size() - 1);
        for (ServerPlayer sp : players) {
            if (!isHost(sp)) out.add(new Spot(sp.getUUID(), sp.getGameProfile().name(), sp.getX(), sp.getY(), sp.getZ()));
        }
        spots = out;
    }

    /**
     * A use / click event that is a co-op GUEST's packet on this client's sim server (server thread, a ServerPlayer that
     * is not him and not a miniboss). The sim's click rules were written for his client's own event (level client side,
     * {@code player == client.player}); each also takes this one, and acts for that guest. Read-only: the host's server
     * still does every write.
     */
    public static boolean isGuestUse(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level) {
        return !level.isClientSide() && player instanceof ServerPlayer sp && !isHost(sp) && !SimMiniboss.isPlaced(sp)
                && SimState.isActive() && Minecraft.getInstance().getSingleplayerServer() == level.getServer();
    }

    /** Server thread: a line to every real player, as Hypixel's server sends a team line. */
    public static void broadcast(MinecraftServer server, net.minecraft.network.chat.Component line) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!SimMiniboss.isPlaced(sp)) sp.sendSystemMessage(line);
        }
    }

    private static void updateParty(List<ServerPlayer> players) {
        List<String> names = new ArrayList<>(players.size());
        for (ServerPlayer sp : players) {
            String name = sp.getGameProfile().name();
            if (isHost(sp)) names.add(0, name); else names.add(name);
        }
        String key = String.join(",", names);
        if (!key.equals(party.key())) party = new Party(List.copyOf(names), key);
    }

    /** What is playing, for the room list and the Session page: "boss F7", "floor F5", "room". */
    static String modeLabel() {
        String mode = mode();
        String floor = SimState.floorLabel();
        return mode.equals("room") || floor == null || floor.isEmpty() ? mode : mode + " " + floor;
    }

    private static String mode() {
        return com.killer560.hub.bosssim.BossFight.running() ? "boss" : SimState.isGeneratedFloor() ? "floor" : "room";
    }

    private static Info info(MinecraftServer server, boolean hello) {
        var fight = com.killer560.hub.bosssim.BossFight.phase();
        String mode = mode();
        var owner = server.getSingleplayerProfile();
        return new Info(hello, PROTOCOL, SimGuest.modVersion(), SimGuest.mc(),
                mode, SimState.floorLabel(), com.killer560.hub.bosssim.BossFight.master(), fight.name(),
                com.killer560.hub.bosssim.BossFight.section(), owner == null ? "" : owner.name(), SimHost.roomKey());
    }
}
