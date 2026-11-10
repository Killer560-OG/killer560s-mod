package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * Co-op sim guest requests ({@code sim_request}, C2S): a guest asks the HOST's server to teleport, change speed, fly,
 * apply its loadout or give it an item. The host's server runs it for the SENDER only, each kind behind an owner
 * setting ({@link SimCoopConfig}, default ON). The guest's own client still writes nothing: every change is the host
 * server's, as every sim change is.
 */
public final class SimRequests {

    /** What a guest may ask for. The ordinal is the wire value: append only. */
    public enum Kind {
        TELEPORT("Guests May Teleport", "teleport"),
        SPEED("Guests May Change Speed", "change speed"),
        FLY("Guests May Fly", "fly"),
        LOADOUT("Guests May Apply Loadouts", "apply a loadout"),
        ITEM("Guests May Take Items", "take items");

        public final String label;
        final String verb;

        Kind(String label, String verb) {
            this.label = label;
            this.verb = verb;
        }
    }

    private static final int ARG = 2048;

    public record Request(int kind, String arg) implements CustomPacketPayload {
        public Type<Request> type() { return TYPE; }
    }

    public static final CustomPacketPayload.Type<Request> TYPE = new CustomPacketPayload.Type<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "sim_request"));

    /** Requests honored and refused on this server, and the last one's "name kind arg -> answer", for tests. */
    private static volatile int honored, refused;
    private static volatile String last = "";

    private SimRequests() {
    }

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CustomPacketPayload.codec(
                (Request r, FriendlyByteBuf b) -> {
                    b.writeVarInt(r.kind());
                    b.writeUtf(r.arg(), ARG);
                }, b -> new Request(b.readVarInt(), b.readUtf(ARG))));
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (r, ctx) -> handle(ctx.player(), r.kind(), r.arg()));
    }

    /** Guest side: sends a request to the host. False when this client is not a guest or the host cannot take it. */
    public static boolean send(Kind kind, String arg) {
        Minecraft mc = Minecraft.getInstance();
        if (!SimGuest.isGuest(mc) || !ClientPlayNetworking.canSend(TYPE)) return false;
        ClientPlayNetworking.send(new Request(kind.ordinal(), arg == null ? "" : arg));
        return true;
    }

    /** Guest side: its own saved loadout as the request's argument (comma-separated, one id or blank per slot). */
    public static String loadoutArg() { return String.join(",", SimLoadout.slotsForRequest()); }

    /** Server thread. Public for the testkit, which hands it a fake guest. */
    public static String handle(ServerPlayer sp, int kindIndex, String arg) {
        Kind[] kinds = Kind.values();
        String answer;
        boolean ok = false;
        if (kindIndex < 0 || kindIndex >= kinds.length) {
            answer = "Unknown request.";
        } else if (SimCoop.isHost(sp) || !SimState.isActive() || !SimHost.hosting()) {
            answer = "Requests only work for guests in a hosted sim.";
        } else if (!SimCoopConfig.get().allows(kinds[kindIndex])) {
            answer = "The owner does not let guests " + kinds[kindIndex].verb + ".";
        } else {
            answer = run(sp, kinds[kindIndex], arg == null ? "" : arg.trim());
            ok = !answer.startsWith("!");
            if (!ok) answer = answer.substring(1);
        }
        if (ok) honored++; else refused++;
        last = sp.getGameProfile().name() + " " + (kindIndex >= 0 && kindIndex < kinds.length ? kinds[kindIndex] : kindIndex)
                + " " + arg + " -> " + answer;
        SimCoop.LOGGER.info("[SimCoop] request {}", last);
        sp.sendSystemMessage(Component.literal("[Sim] " + answer));
        return answer;
    }

    /** The answer; one starting with "!" is a refusal. */
    private static String run(ServerPlayer sp, Kind kind, String arg) {
        if (!(sp.level() instanceof ServerLevel level)) return "!Not in the sim world.";
        switch (kind) {
            case TELEPORT -> {
                if (arg.equalsIgnoreCase("owner")) {
                    ServerPlayer host = null;
                    for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                        if (SimCoop.isHost(p)) host = p;
                    }
                    if (host == null || host.level() != level) return "!The owner is not in this world.";
                    SimArrival.teleport(sp, level, host.getX(), host.getY(), host.getZ(), Set.of(), host.getYRot(), host.getXRot());
                    return "Teleported to the owner.";
                }
                SimRoomIndex.Placed target = SimTeleportCommands.resolve(SimRoomIndex.placed(), arg);
                if (target == null) return "!No room called " + arg + " on this floor.";
                SimBuilder.snapPlayerTo(sp.getUUID(), level, target.gridX(), target.gridZ(), RoomLibrary.get(target.name()));
                return "Teleported to " + target.name() + ".";
            }
            case SPEED -> {
                int speed;
                try {
                    speed = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    return "!Speed must be a number.";
                }
                if (speed < 100 || speed > 500) return "!Speed must be 100 to 500.";
                var attr = sp.getAttribute(Attributes.MOVEMENT_SPEED);
                if (attr == null) return "!No speed to set.";
                attr.setBaseValue(speed / 1000.0);
                return "Speed set to " + speed + ".";
            }
            case FLY -> {
                boolean on = arg.equalsIgnoreCase("on");
                SimSurvival.setFlight(sp, on);
                return "Flight " + (on ? "ON" : "OFF") + ".";
            }
            case LOADOUT -> {
                String[] ids = arg.split(",", -1);
                int set = 0;
                for (int i = 0; i < Math.min(36, ids.length); i++) {
                    if (ids[i].isBlank()) continue;
                    ItemStack stack = SimItems.build(ids[i].trim());
                    if (stack.isEmpty()) continue;
                    sp.getInventory().setItem(i, stack);
                    set++;
                }
                return set == 0 ? "!Your loadout has no sim items." : "Loadout applied (" + set + " slot" + (set == 1 ? "" : "s") + ").";
            }
            case ITEM -> {
                return SimItems.giveTo(sp, arg) ? "Gave " + arg + "." : "!" + arg + " is not a sim item.";
            }
        }
        return "!Unknown request.";
    }

    /** {honored, refused}, for tests. */
    public static int[] countsForTest() { return new int[]{honored, refused}; }

    public static String lastForTest() { return last; }

    /** The give list, for the Session page. */
    public static List<String> items() { return SimItems.giveIds(); }
}
