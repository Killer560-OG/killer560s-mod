package com.killer560.hub.lfg;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/** {@code /pf} (or {@code /partyfinder}) opens the Custom Party Finder; {@code /pf unlist} takes the player's own listing down. */
public final class LfgCommands {

    private LfgCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}, alongside {@link LfgFeature#register()}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String name : new String[] {"pf", "partyfinder"}) {
                dispatcher.register(ClientCommands.literal(name)
                        .executes(context -> open())
                        .then(ClientCommands.literal("unlist").executes(context -> {
                            if (!LfgFeature.isListed()) {
                                ModChat.send(LfgFeature.CHAT, ModChat.dim("You have no listing."));
                                return 0;
                            }
                            LfgFeature.unlist("unlisted");
                            ModChat.send(LfgFeature.CHAT, ModChat.text("Listing removed."));
                            return 1;
                        })));
            }
        });
    }

    private static int open() {
        if (!LfgConfig.getInstance().isEnabledRaw()) {
            ModChat.send(LfgFeature.CHAT, ModChat.bad("Custom Party Finder is off (Dungeon > Custom Party Finder)."));
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        // Deferred like /fl: the chat screen closing after the command would otherwise replace this screen.
        client.execute(() -> client.setScreenAndShow(new LfgScreen(null)));
        return 1;
    }
}
