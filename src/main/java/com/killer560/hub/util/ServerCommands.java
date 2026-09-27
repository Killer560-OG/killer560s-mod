package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

/**
 * Send a command to the SERVER and nowhere else.
 * <p>
 * {@code ClientPacketListener.sendCommand} is not that. Fabric's command API mixes into it
 * ({@code handler$…$fabric-command-api-v2$onSendCommand}), so a command whose name this mod has registered
 * client-side is caught there and dispatched back into our own handler. When the handler that sent it IS that
 * handler, the result is unbounded recursion and a StackOverflowError.
 * <p>
 * That is not theoretical. killer560's {@code 26.1.2 (Dungeons)} instance crashed on it repeatedly - five reports
 * between 2026-09-25 and 2026-09-26, 156 frames of the same three lines:
 * <pre>
 *   AuctionHouseFeature.forwardToServer(AuctionHouseFeature.java:91)
 *     -> ClientPacketListener.sendCommand
 *       -> fabric-command-api-v2$onSendCommand -> ClientCommandInternals.executeCommand
 *         -> AuctionHouseFeature.lambda$register$1        (the same handler again)
 * </pre>
 * {@code FriendsListCommands} carried a class comment asserting the opposite - "it is NOT re-typed into the chat
 * box, so it can't loop back through this same client command". The reasoning was right about the chat box and
 * wrong about the interception point, which is the method itself. That is his "/fl crashes" report, and
 * {@code BazaarScreen}'s {@code /bz} had it too.
 * <p>
 * {@link ServerboundChatCommandPacket} is what vanilla itself puts on the wire for a command with nothing to sign,
 * so this is the same bytes Hypixel would have received - it simply leaves from below the client dispatcher, where
 * nothing can catch it on the way out.
 * <p>
 * Use this whenever the intent is "Hypixel handles this". Keep using {@code sendCommand} where a command is meant
 * to be able to reach this mod's own client commands - a keybind or chat shortcut the player pointed at
 * {@code /ap3}, say.
 */
public final class ServerCommands {

    private ServerCommands() {
    }

    /**
     * @param command the command WITHOUT its leading slash, exactly as {@code sendCommand} takes it.
     * @return true if it went.
     */
    public static boolean toServer(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || player.connection == null) {
            return false;
        }
        player.connection.send(new ServerboundChatCommandPacket(command));
        return true;
    }
}
