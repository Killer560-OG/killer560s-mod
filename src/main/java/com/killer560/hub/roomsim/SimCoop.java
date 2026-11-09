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
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    public static final int PROTOCOL = 1;
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

    /** The guest's answer: its build, so the host's log says who joined with what. */
    public record Ack(int proto, String modVersion, String mc, String commit, String variant)
            implements CustomPacketPayload {
        public Type<Ack> type() { return ACK; }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(proto);
            b.writeUtf(modVersion, STR);
            b.writeUtf(mc, STR);
            b.writeUtf(commit, STR);
            b.writeUtf(variant, STR);
        }

        static Ack read(FriendlyByteBuf b) {
            return new Ack(b.readVarInt(), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR), b.readUtf(STR));
        }
    }

    public static final CustomPacketPayload.Type<Info> HELLO = type("sim_hello");
    public static final CustomPacketPayload.Type<Info> STATE = type("sim_state");
    public static final CustomPacketPayload.Type<Ack> ACK = type("sim_hello_ack");

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
            LOGGER.info("[SimCoop] {} joined with protocol {} mod {} MC {} {} {}", ctx.player().getGameProfile().name(),
                    ack.proto(), ack.modVersion(), ack.mc(), ack.variant(), ack.commit());
        });
        ServerTickEvents.END_SERVER_TICK.register(SimCoop::serverTick);
        SimGuest.register();
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

    private static void serverTick(MinecraftServer server) {
        if (++ticks % EVERY != 0) return;
        Minecraft client = Minecraft.getInstance();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
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

    private static void updateParty(List<ServerPlayer> players) {
        List<String> names = new ArrayList<>(players.size());
        for (ServerPlayer sp : players) {
            String name = sp.getGameProfile().name();
            if (isHost(sp)) names.add(0, name); else names.add(name);
        }
        String key = String.join(",", names);
        if (!key.equals(party.key())) party = new Party(List.copyOf(names), key);
    }

    private static Info info(MinecraftServer server, boolean hello) {
        var fight = com.killer560.hub.bosssim.BossFight.phase();
        boolean boss = com.killer560.hub.bosssim.BossFight.running();
        String mode = boss ? "boss" : SimState.isGeneratedFloor() ? "floor" : "room";
        var owner = server.getSingleplayerProfile();
        return new Info(hello, PROTOCOL, SimGuest.modVersion(), SimGuest.mc(),
                mode, SimState.floorLabel(), com.killer560.hub.bosssim.BossFight.master(), fight.name(),
                com.killer560.hub.bosssim.BossFight.section(), owner == null ? "" : owner.name(), lanNonce());
    }

    /**
     * The join ticket. Phase 1 has none: a LAN join (the guest checks the address is private) is the ticket, so this
     * is 0. Phase 2's lobby puts its ticket here and {@link SimGuest#ticketMatches} checks it.
     */
    private static long lanNonce() { return 0L; }
}
