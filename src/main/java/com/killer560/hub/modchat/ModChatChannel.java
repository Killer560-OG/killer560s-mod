package com.killer560.hub.modchat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mod Chat as a chat CHANNEL, the way Hypixel has party and guild channels (killer560, 2026-10-08: "make stuff like
 * /chat k or /kc to send the message in my chat or to enter my chat's channel (just like /chat p or /gc)").
 * <ul>
 * <li>{@code /kc <message>} sends one line to Mod Chat, like {@code /pc}/{@code /gc}. {@code /kc} on its own switches
 *     the channel, like {@code /chat k}.</li>
 * <li>{@code /chat k} (also {@code kc}, {@code mod}, {@code modchat}, {@code killer560}) switches the outgoing channel
 *     to Mod Chat: every plain typed line then goes to the relay instead of Hypixel. It is caught at Fabric's
 *     {@link ClientSendMessageEvents#ALLOW_COMMAND} and never reaches Hypixel.</li>
 * <li>Every other {@code /chat ...} ({@code /chat a}, {@code /chat p}, {@code /chat g}, ...) goes to Hypixel exactly
 *     as typed, and switches this channel off: Hypixel then answers with its own "You are now in the ... channel".</li>
 * </ul>
 * {@code /chat} itself is Hypixel's command and is never registered client-side, so nothing else about it changes.
 * <p>
 * <b>Never a fallback to Hypixel.</b> While the channel is Mod Chat, a line that cannot go to the relay (Mod Chat off,
 * relay down) is dropped with a red line saying so - sending it to Hypixel instead would post a message he meant to
 * keep among mod users into public chat, the leak {@link ModChatFeature}'s class doc is about.
 * <p>
 * The channel lives only for the connection: leaving the server puts typed chat back on Hypixel, so a relog can never
 * silently route his chat somewhere he has forgotten about.
 */
public final class ModChatChannel {

    private static final Logger LOGGER = ModLog.get("killer560smod-modchat");

    /** The command as Fabric hands it (no leading slash): "chat" and at most one bounded argument. */
    private static final Pattern CHAT_COMMAND = Pattern.compile("^chat(?:\\s+(\\S{1,32}))?\\s*$", Pattern.CASE_INSENSITIVE);
    /** {@code /chat <one of these>} means Mod Chat. Hypixel's own channels (a, all, p, party, g, guild, o, officer,
     *  coop, ...) are deliberately not here. */
    static final Set<String> MOD_CHANNEL_NAMES = Set.of("k", "kc", "mod", "modchat", "killer560");

    private static volatile boolean active;
    private static boolean registered;

    private ModChatChannel() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientSendMessageEvents.ALLOW_COMMAND.register(ModChatChannel::allowCommand);
        ClientSendMessageEvents.ALLOW_CHAT.register(ModChatChannel::allowChat);
        // Runs on the Netty thread (CLAUDE.md); it only clears a flag, nothing in the world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = false);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("kc")
                        .executes(context -> {
                            switchToModChat();
                            return 1;
                        })
                        .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> {
                                    sendLine(StringArgumentType.getString(context, "message"));
                                    return 1;
                                }))));
    }

    /** True while plain typed chat goes to Mod Chat. */
    public static boolean isActive() {
        return active;
    }

    /** Switches the channel without the chat line (tests, and the channel being turned off from elsewhere). */
    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Fabric's ALLOW_COMMAND: false swallows the command (it never leaves the client). Only {@code /chat <mod name>} is
     * swallowed; any other {@code /chat} switches this channel off and still goes to Hypixel unchanged.
     */
    static boolean allowCommand(String command) {
        if (command == null) {
            return true;
        }
        Matcher m = CHAT_COMMAND.matcher(command.trim());
        if (!m.matches()) {
            return true;
        }
        String arg = m.group(1);
        if (arg != null && MOD_CHANNEL_NAMES.contains(arg.toLowerCase(Locale.ROOT))) {
            switchToModChat();
            return false;
        }
        if (arg != null && active) {
            // "/chat a", "/chat p", ...: Hypixel switches and says so itself.
            active = false;
            LOGGER.info("[ModChat] channel back to Hypixel (/chat {})", arg);
        }
        return true;
    }

    /** Fabric's ALLOW_CHAT: while the channel is Mod Chat, a plain line goes to the relay and never to Hypixel. */
    static boolean allowChat(String message) {
        if (!active) {
            return true;
        }
        sendLine(message);
        return false;
    }

    private static void switchToModChat() {
        if (!ModChatConfig.getInstance().isEnabled()) {
            ModChat.send("ModChat", ModChat.bad("Mod Chat is off"),
                    ModChat.dim(" - turn it on in its settings tab first. Your chat channel did not change."));
            return;
        }
        active = true;
        LOGGER.info("[ModChat] channel set to Mod Chat");
        ModChat.send("ModChat", ModChat.good("You are now in the "), ModChat.value("MOD CHAT"),
                ModChat.good(" channel"), ModChat.dim(" (/chat a, /chat p, /chat g ... to leave)"));
    }

    private static void sendLine(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String result = ModChatFeature.send(message.trim());
        boolean failed = result.startsWith("§c");
        if (failed) {
            // Red, in chat: a dropped line must be impossible to miss. Nothing went to Hypixel.
            ModChat.send("ModChat", ModChat.bad(result.substring(2).replaceFirst("^\\[ModChat] ", "")));
        } else if (!ModChatConfig.getInstance().isLogToChat()) {
            // With Log To Chat on, the relay's echo of the line is the confirmation; without it, say it went.
            com.killer560.hub.notify.ModOverlayMessage.show(result, 3000);
        }
    }
}
