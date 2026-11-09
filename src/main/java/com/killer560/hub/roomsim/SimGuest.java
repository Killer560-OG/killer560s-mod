package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Co-op sim, the GUEST half (docs/SIM-COOP.md): what a player who joined someone else's sim world over LAN knows about
 * it. READ-ONLY by design: it feeds gates and DungeonState's floor/boss flags and nothing else. The guest's own client
 * never acts as the sim (SimState.canAct stays false on a guest); every world write is the host's server.
 *
 * <p>The receivers are registered PER CONNECTION and only for a private / loopback address that is not Hypixel or
 * p3sim, so the {@code killer560smod:} channels are never announced to a public server (a global receiver would be
 * listed in the client's minecraft:register to every server it joins).
 */
public final class SimGuest {

    private static volatile SimCoop.Info current;
    private static final AtomicBoolean PENDING_DROP = new AtomicBoolean();
    /** Hellos and states accepted, and refused, since start; for tests. */
    private static volatile int accepted;
    private static volatile int refused;
    /** The landing the host is holding this guest for, answered once SimArrival lets go; -1 for none. Render thread. */
    private static int pendingArrive = -1;
    private static boolean wasAttacking;
    /** Landings the host announced and Terminator clicks sent, for tests. */
    private static volatile int arrivesSeen, clicksSent;

    private SimGuest() {
    }

    static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            if (!eligible(client, handler)) return;
            ClientPlayNetworking.registerReceiver(SimCoop.HELLO, (info, ctx) -> receive(info));
            ClientPlayNetworking.registerReceiver(SimCoop.STATE, (info, ctx) -> receive(info));
            ClientPlayNetworking.registerReceiver(SimCoop.ARRIVE, (a, ctx) -> arrive(a));
        });
        // DISCONNECT is on the netty thread and a task queued from it can be dropped (code-lessons): a flag the
        // render thread drains.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PENDING_DROP.set(true));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (PENDING_DROP.getAndSet(false)) drop();
            if (current != null || pendingArrive >= 0) guestTick(client);
        });
    }

    /**
     * Render thread: the host's server teleported this guest. SimArrival holds the loading screen until the chunks
     * round the spot are here - the same wait he gets, minus his put-back: that is the host's server's job
     * ({@link SimCoop#hold}), nothing here writes the position.
     */
    private static void arrive(SimCoop.Arrive a) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        arrivesSeen++;
        SimArrival.expect(client.player.getUUID(), new double[]{a.x(), a.y(), a.z()});
        pendingArrive = a.seq();
    }

    private static void guestTick(Minecraft client) {
        if (pendingArrive >= 0 && !SimArrival.holding()) {
            if (ClientPlayNetworking.canSend(SimCoop.ARRIVED)) ClientPlayNetworking.send(new SimCoop.Arrived(pendingArrive));
            pendingArrive = -1;
        }
        // The Terminator's left click (Salvation when armed, else the shot) is the host's SimClass reading HIS attack
        // key; a guest's press goes to the host's server, which fires it from the guest's own aim.
        boolean attacking = isGuest(client) && com.killer560.hub.compat.McCompat.screen(client) == null
                && client.options.keyAttack.isDown();
        boolean pressed = attacking && !wasAttacking;
        wasAttacking = attacking;
        if (pressed && SimTerminator.ITEM_ID.equals(
                com.killer560.hub.cheatutils.CheatUtils.skyblockId(client.player.getMainHandItem()))
                && ClientPlayNetworking.canSend(SimCoop.CLICK)) {
            ClientPlayNetworking.send(new SimCoop.Click());
            clicksSent++;
        }
    }

    /** {landings announced, Terminator clicks sent}, for tests. */
    public static int[] guestForTest() { return new int[]{arrivesSeen, clicksSent}; }

    /** Whether this client is a guest in another player's sim (see {@link SimState#isGuest}). */
    static boolean isGuest(Minecraft client) {
        return current != null && client != null && client.level != null && client.player != null
                && client.getSingleplayerServer() == null && client.getCurrentServer() != null;
    }

    /** The host's last word, or null when not a guest. */
    public static SimCoop.Info info() { return current; }

    public static int acceptedForTest() { return accepted; }

    public static int refusedForTest() { return refused; }

    /** Render thread (Fabric runs play payload handlers there). */
    private static void receive(SimCoop.Info info) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener conn = client.getConnection();
        if (info.proto() != SimCoop.PROTOCOL || conn == null || !eligible(client, conn)
                || !(info.hello() || current != null) || !ticketMatches(info.nonce(), client)) {
            refused++;
            SimCoop.LOGGER.warn("[SimCoop] refused {} (protocol {}, guest {})", info.type().id().getPath(),
                    info.proto(), current != null);
            return;
        }
        SimCoop.Info was = current;
        current = info;
        accepted++;
        if (was == null) {
            com.killer560.hub.secrets.DungeonState.setRoomSim(true);
            SimCoop.LOGGER.info("[SimCoop] guest of {}'s sim: {} {} (host mod {}, MC {})", info.owner(), info.mode(),
                    info.floor(), info.modVersion(), info.mc());
            if (ClientPlayNetworking.canSend(SimCoop.ACK)) {
                ClientPlayNetworking.send(new SimCoop.Ack(SimCoop.PROTOCOL, modVersion(), mc(),
                        com.killer560.hub.autoupdate.BuildInfo.shortCommit(),
                        com.killer560.hub.autoupdate.BuildInfo.variant()));
            }
        }
        boolean boss = !info.phase().equals("IDLE") && !info.phase().equals("DONE");
        if (boss && !com.killer560.hub.secrets.DungeonState.isSimBoss()) {
            com.killer560.hub.secrets.DungeonState.simBossBegins(info.master());
        } else if (!boss) {
            com.killer560.hub.secrets.DungeonState.simBossEnds();
        }
    }

    private static void drop() {
        pendingArrive = -1;
        wasAttacking = false;
        if (current == null) return;
        current = null;
        com.killer560.hub.secrets.DungeonState.simBossEnds();
        // The host's own sim sets this too, but a client cannot be host and guest at once: the guest's own
        // SimState is never active.
        if (!SimState.isActive()) com.killer560.hub.secrets.DungeonState.setRoomSim(false);
        SimCoop.LOGGER.info("[SimCoop] left the host's sim");
    }

    /**
     * A guest honors the host only on a remote server (not his own integrated one) that is not Hypixel or p3sim, at
     * a private, loopback or link-local address. The testkit labels its loopback GrimAC server mc.hypixel.net, so
     * the name check refuses it too.
     */
    static boolean eligible(Minecraft client, ClientPacketListener conn) {
        if (client.getSingleplayerServer() != null) return false;
        ServerData data = conn.getServerData();
        if (data == null || data.ip == null) return false;
        String ip = data.ip.toLowerCase(Locale.ROOT);
        if (ip.contains("hypixel.net") || ip.contains("p3sim.net")) return false;
        SocketAddress remote = conn.getConnection().getRemoteAddress();
        return remote instanceof InetSocketAddress isa && privateAddress(isa.getAddress());
    }

    static boolean privateAddress(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc; // IPv6 unique local, fc00::/7
    }

    /**
     * The join ticket. Phase 1: a LAN join is the ticket (the address was already checked private), so any nonce
     * passes once {@link ServerData#isLan} or the private address holds. Phase 2's lobby checks its ticket here.
     */
    static boolean ticketMatches(long nonce, Minecraft client) {
        return client.getCurrentServer() != null;
    }

    static String modVersion() {
        return FabricLoader.getInstance().getModContainer("killer560smod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    static String mc() {
        String v = com.killer560.hub.autoupdate.BuildInfo.minecraft();
        return v == null ? "?" : v;
    }
}
