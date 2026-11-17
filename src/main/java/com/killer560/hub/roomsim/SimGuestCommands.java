package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A co-op guest typing a sim command (coopstart-1111): the guest's client has no sim world of its own, so the commands are
 * not there for it. The same words are caught here and sent to the owner's server as {@link SimRequests} (per-player ones:
 * items, goto, speed, fly, loadout) or as a COMMAND request (world-wide ones, run on the owner's sim). The owner's
 * "Guests May ..." switches still apply, and the answer comes back as a "[Sim]" line.
 */
public final class SimGuestCommands {

    /** The world-wide sim commands a guest may run on the owner's sim. */
    static final Set<String> HOST_ROOTS = Set.of("start", "simbuild", "simboss", "simmobs", "simpuzzle", "simscore", "simlive",
            "simwhere", "c", "center", "reset", "reload");

    private SimGuestCommands() {
    }

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(c -> !handle(c));
    }

    /** True when {@code command} (no slash) was a sim command typed by a guest and has been answered or sent. */
    public static boolean handle(String command) {
        if (!SimState.isGuest(Minecraft.getInstance())) return false;
        String[] w = command.strip().split("\\s+", 2);
        String root = w[0].toLowerCase(Locale.ROOT);
        String rest = w.length > 1 ? w[1].strip() : "";
        boolean sent;
        switch (root) {
            case "item", "simitem" -> {
                List<String> ids = SimRequests.items();
                if (rest.equalsIgnoreCase("all")) {
                    sent = false;
                    for (String id : ids) sent |= SimRequests.send(SimRequests.Kind.ITEM, id);
                } else if (SimItems.idOf(rest) != null) {
                    sent = SimRequests.send(SimRequests.Kind.ITEM, SimItems.idOf(rest));
                } else {
                    ModChat.send("Sim", ModChat.text("/item <name|all>: " + String.join(", ", ids).toLowerCase(Locale.ROOT)));
                    return true;
                }
            }
            case "goto", "tpto" -> sent = SimRequests.send(SimRequests.Kind.TELEPORT, rest);
            case "speed" -> sent = SimRequests.send(SimRequests.Kind.SPEED, rest);
            case "fly" -> sent = SimRequests.send(SimRequests.Kind.FLY, rest.equalsIgnoreCase("off") ? "off" : "on");
            case "simloadout" -> {
                if (!rest.equalsIgnoreCase("apply")) return false;   // save, clear, slot and auto are his own client's
                sent = SimRequests.send(SimRequests.Kind.LOADOUT, SimRequests.loadoutArg());
            }
            default -> {
                if (!HOST_ROOTS.contains(root)) return false;
                sent = SimRequests.send(SimRequests.Kind.COMMAND, command.strip());
            }
        }
        if (!sent) ModChat.send("Sim", ModChat.text("The owner's sim cannot take that right now."));
        return true;
    }
}
