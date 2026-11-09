package com.killer560.hub.commandprefix;

import com.killer560.hub.util.CommandTreeRefresh;
import com.killer560.hub.util.ModLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.function.Function;

/**
 * Chosen names for the main command and the alternate command character. Every name gets the whole tree
 * (built once per name from {@code tree}) behind {@code requires(switch on)}, so a switch takes effect at once;
 * tab completion for a name switched on later is re-merged by {@link CommandTreeRefresh}. The chat hook works only on send.
 */
public final class CommandPrefixFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-commandprefix");
    /** After every other mod's default-phase registration, so a root another mod owns is already in the dispatcher. */
    private static final Identifier LATE = Identifier.fromNamespaceAndPath("killer560smod", "command_prefix");

    private CommandPrefixFeature() {
    }

    public static void register(Function<String, LiteralArgumentBuilder<FabricClientCommandSource>> tree) {
        ClientCommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
        ClientCommandRegistrationCallback.EVENT.register(LATE, (dispatcher, registryAccess) -> {
            for (String name : CommandPrefixConfig.NAMES) {
                CommandNode<FabricClientCommandSource> existing = dispatcher.getRoot().getChild(name);
                // Legit GFS owns /k with its "gfs" subcommand only: ours merges beside it (each of ours is gated on the switch).
                if (existing != null && !existing.getChildren().stream().allMatch(n -> n.getName().equals("gfs"))) {
                    LOGGER.warn("[CommandPrefixes] /{} is already another command's root; not registered", name);
                    continue;
                }
                dispatcher.register(existing != null ? tree.apply(name) : tree.apply(name).requires(src -> on(name)));
            }
        });
        for (String name : CommandPrefixConfig.NAMES) {
            CommandTreeRefresh.watch(() -> CommandPrefixConfig.getInstance().isNameOn(name));
        }
        ClientSendMessageEvents.ALLOW_CHAT.register(CommandPrefixFeature::allowChat);
    }

    public static boolean on(String name) {
        return CommandPrefixConfig.getInstance().isNameOn(name);
    }

    /** A merged root (/k beside Legit GFS) is always parsed, so a bare name that is off must read as unknown. */
    public static void requireOn(String name) throws CommandSyntaxException {
        if (!on(name)) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }
    }

    /** False when the line was run as a client command. A first word that is no client command root is plain chat. */
    private static boolean allowChat(String message) {
        CommandPrefixConfig cfg = CommandPrefixConfig.getInstance();
        if (!cfg.isAltOn() || !message.startsWith(cfg.altChar())) {
            return true;
        }
        String line = message.substring(cfg.altChar().length());
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        CommandDispatcher<FabricClientCommandSource> d = ClientCommandInternals.getActiveDispatcher();
        Minecraft mc = Minecraft.getInstance();
        if (end == 0 || d == null || mc.getConnection() == null) {
            return true;
        }
        FabricClientCommandSource src = (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider();
        CommandNode<FabricClientCommandSource> root = d.getRoot().getChild(line.substring(0, end).toLowerCase(java.util.Locale.ROOT));
        if (root == null || !root.canUse(src)) {
            return true;
        }
        try {
            d.execute(line, src);
        } catch (CommandSyntaxException e) {
            src.sendError(Component.literal(e.getMessage()));
        } catch (RuntimeException e) {
            LOGGER.warn("[CommandPrefixes] /{} failed", line, e);
        }
        return false;
    }
}
