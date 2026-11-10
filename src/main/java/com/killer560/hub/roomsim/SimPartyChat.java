package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.regex.Pattern;

/**
 * The sim answers party chat as Hypixel does for a player in no party (killer560, 2026-11-09: a position message sent
 * {@code /pc at ee2} and the integrated server printed "Unknown or incomplete command"): {@code /pc}, {@code /pchat},
 * {@code /party chat} and {@code /p chat} are swallowed and "You are not in a party right now." (red) is shown.
 */
public final class SimPartyChat {

    private static final Pattern PARTY_CHAT = Pattern.compile("(?i)(pc|pchat|(party|p) chat)(\s.*)?");

    private SimPartyChat() {
    }

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(SimPartyChat::allow);
    }

    /** False (not sent) for a party chat command in the sim; the command comes without its slash. */
    static boolean allow(String command) {
        if (!PARTY_CHAT.matcher(command.strip()).matches() || !SimState.canAct(Minecraft.getInstance())) {
            return true;
        }
        ModChat.sendBare(ModChat.colored("You are not in a party right now.", 0xFF5555));
        return false;
    }
}
