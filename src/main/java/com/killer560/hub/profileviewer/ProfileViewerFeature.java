package com.killer560.hub.profileviewer;

import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.screen.ProfileViewerScreen;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/**
 * SkyBlock Profile Viewer - {@code /pv [name]} (and {@code /killer560pv [name]}), modelled on
 * NotEnoughUpdates' old 1.8.9 {@code /pv} GUI and today's SkyBlock Profile Viewer mod (meowdding/skyblock-pv).
 * No name = yourself. Read-only: it only fetches public API data and shows it; never interacts with the
 * server. Not gated - works anywhere a client command does.
 * <p>
 * Also an optional keybind (unbound by default, Profile Viewer settings tab): opens the player under your
 * crosshair, or yourself when you aren't looking at a real player.
 */
public final class ProfileViewerFeature {

    public static final String CHAT_PREFIX = "Profile Viewer";

    private static boolean keyWasDown = false;

    /** Who to open: a name to look up, and the UUID when it's already known (self / tab list / crosshair). */
    public record Target(String name, UUID uuid) {
    }

    private ProfileViewerFeature() {
    }

    public static void register() {
        ProfileViewerConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(ProfileViewerFeature::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(command("pv"));
            dispatcher.register(command("killer560pv"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command(String literal) {
        SuggestionProvider<FabricClientCommandSource> players = (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                    String name = info.getProfile().name();
                    if (isRealPlayer(info.getProfile().id(), name) && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        names.add(name);
                    }
                }
            }
            names.forEach(builder::suggest);
            return builder.buildFuture();
        };
        return ClientCommands.literal(literal)
                .executes(ctx -> {
                    openDeferred(self());
                    return 1;
                })
                .then(ClientCommands.argument("player", StringArgumentType.word())
                        .suggests(players)
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            if (!ProfileViewerApi.isValidName(name) && ProfileViewerApi.parseUuid(name) == null) {
                                ModChat.send(CHAT_PREFIX, ModChat.bad("\"" + name + "\" isn't a valid Minecraft name."));
                                return 0;
                            }
                            openDeferred(targetForName(name));
                            return 1;
                        }));
    }

    /** Hypixel NPCs / tab-list filler entries use non-v4 UUIDs and odd names. */
    private static boolean isRealPlayer(UUID uuid, String name) {
        return uuid != null && uuid.version() == 4 && ProfileViewerApi.isValidName(name);
    }

    public static Target self() {
        Minecraft client = Minecraft.getInstance();
        return new Target(client.getUser().getName(), client.getUser().getProfileId());
    }

    /** Uses the tab list's UUID when that player is online, skipping the Mojang lookup. */
    public static Target targetForName(String name) {
        Minecraft client = Minecraft.getInstance();
        if (name.equalsIgnoreCase(client.getUser().getName())) {
            return self();
        }
        if (client.getConnection() != null) {
            for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                if (info.getProfile().name().equalsIgnoreCase(name) && isRealPlayer(info.getProfile().id(), info.getProfile().name())) {
                    return new Target(info.getProfile().name(), info.getProfile().id());
                }
            }
        }
        return new Target(name, ProfileViewerApi.parseUuid(name));
    }

    public static void openDeferred(Target target) {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /killer560 - the chat screen closing after the command would otherwise replace
        // this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new ProfileViewerScreen(null, target)));
    }

    private static void tick(Minecraft client) {
        int code = ProfileViewerConfig.getInstance().getOpenKeyCode();
        if (code < 0 || client.player == null || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(client.getWindow(), code);
        if (down && !keyWasDown && client.screen == null) {
            Target target = self();
            Entity looked = client.crosshairPickEntity;
            if (looked instanceof Player p && p != client.player && isRealPlayer(p.getUUID(), p.getGameProfile().name())) {
                target = new Target(p.getGameProfile().name(), p.getUUID());
            }
            client.setScreenAndShow(new ProfileViewerScreen(null, target));
        }
        keyWasDown = down;
    }
}
