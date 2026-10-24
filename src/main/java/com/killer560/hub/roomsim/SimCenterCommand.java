package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * {@code /center} - in the dungeon sim, moves him onto the center of the block he stands on, the way an etherwarp lands
 * him: a server teleport to the block's middle at his feet's height, the camera left as it is.
 */
public final class SimCenterCommand {

    private SimCenterCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("center").executes(ctx -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!SimState.canAct(client) || client.player == null || client.getSingleplayerServer() == null) {
                        ModChat.send("Sim", ModChat.text("/center only works in the sim."));
                        return 1;
                    }
                    ServerPlayer sp = client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
                    if (sp == null) {
                        ModChat.send("Sim", ModChat.text("No server player to move."));
                        return 1;
                    }
                    double feetY = sp.getY();
                    BlockPos under = BlockPos.containing(sp.getX(), feetY - 0.01, sp.getZ());
                    double x = under.getX() + 0.5;
                    double z = under.getZ() + 0.5;
                    // Relative rotation of zero, as the sim's own ability teleports use: the camera he has stays his.
                    sp.teleportTo((ServerLevel) sp.level(), x, feetY, z, Set.of(Relative.Y_ROT, Relative.X_ROT), 0f, 0f, false);
                    ModChat.send("Sim", ModChat.text("Centered on "), ModChat.value(under.toShortString()));
                    return 1;
                })));
    }
}
