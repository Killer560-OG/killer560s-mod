package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party chat in the sim (killer560, 2026-11-09: a position message sent {@code /pc at ee2} and the integrated server
 * printed "Unknown or incomplete command"). {@code /pc}, {@code /pchat}, {@code /party chat} and {@code /p chat} are
 * swallowed. Alone, "You are not in a party right now." (red) is shown, as Hypixel does for a player in no party. In a
 * co-op session the host's server sends "Party > Name: msg" to every real player: the host's line is handled here, a
 * guest's goes up as {@link SimCoop.PartyMsg} (never as a command to the host's server, no position write). The hook
 * acts only while the sim's state is held, which Hypixel never sets.
 */
public final class SimPartyChat {

    private static final Pattern PARTY_CHAT = Pattern.compile("(?i)(?:pc|pchat|(?:party|p) chat)(?:\\s+(.*))?");
    private static volatile int sentForTest;

    private SimPartyChat() {
    }

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(SimPartyChat::allow);
    }

    /** False (not sent) for a party chat command in the sim; the command comes without its slash. */
    static boolean allow(String command) {
        Matcher m = PARTY_CHAT.matcher(command.strip());
        Minecraft client = Minecraft.getInstance();
        boolean host = SimState.canAct(client);
        if (!m.matches() || !host && !SimState.isGuest(client)) {
            return true;
        }
        String text = m.group(1) == null ? "" : m.group(1).strip();
        MinecraftServer server = client.getSingleplayerServer();
        if (host && !text.isEmpty() && server != null && client.player != null
                && server.getPlayerList().getPlayerCount() > 1) {
            UUID id = client.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(id);
                if (sp != null) say(sp, text);
            });
        } else if (!host && !text.isEmpty() && ClientPlayNetworking.canSend(SimCoop.PARTY_MSG)) {
            ClientPlayNetworking.send(new SimCoop.PartyMsg(text));
            sentForTest++;
        } else {
            ModChat.sendBare(ModChat.colored("You are not in a party right now.", 0xFF5555));
        }
        return false;
    }

    /** Server thread: one party chat line to every real player. */
    static void say(ServerPlayer from, String text) {
        MinecraftServer server = from.level().getServer();
        if (server == null || text.isBlank()) return;
        Component line = Component.literal("Party ").withStyle(ChatFormatting.BLUE)
                .append(Component.literal("> ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(from.getGameProfile().name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": " + text).withStyle(ChatFormatting.WHITE));
        SimCoop.broadcast(server, line);
    }

    /** Guest /pc lines sent up, for tests. */
    public static int sentForTest() { return sentForTest; }
}
