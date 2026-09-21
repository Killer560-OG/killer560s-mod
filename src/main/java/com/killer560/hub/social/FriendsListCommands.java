package com.killer560.hub.social;

import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.UUID;

/**
 * {@code /fl} - killer560's 8.7 toggle between OUR Friends List and Hypixel's own, per the brief:
 * <ul>
 * <li>bare {@code /fl} opens {@link FriendsListScreen} when {@link FriendsListConfig#isEnabled()} is on,
 * otherwise forwards straight to Hypixel's real {@code /fl} - unchanged behaviour, since the setting ships
 * OFF (the brief's explicit "must never hijack his /fl until he turns it on").</li>
 * <li>{@code /flcustom} always opens our list, {@code /flhypixel} always forwards to Hypixel's - both work
 * regardless of the toggle, so neither one is ever unreachable.</li>
 * </ul>
 * <b>Collision check done before writing this</b> (see impl-bestfriends.md): this mod registers no other
 * {@code fl}/{@code flcustom}/{@code flhypixel} command anywhere, and registering a client-side {@code fl}
 * literal has no effect on the separate {@code f} literal Hypixel's own {@code /f add}, {@code /f remove},
 * etc. use - this class never touches {@code f} at all, so those keep working exactly as before.
 * <p>
 * Forwarding uses {@code ClientPacketListener.sendCommand}, the same real-command-to-the-server call
 * {@code autojoinskyblock.AutoJoinSkyblockFeature} and {@code commandshortcuts.CommandShortcutsFeature}
 * already use - it is NOT re-typed into the chat box, so it can't loop back through this same client command.
 */
public final class FriendsListCommands {

    private FriendsListCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("fl")
                    .executes(context -> handleFl(""))
                    .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                            .executes(context -> handleFl(StringArgumentType.getString(context, "args")))));

            dispatcher.register(ClientCommands.literal("flcustom")
                    .executes(context -> customSubcommand(""))
                    .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                            .executes(context -> customSubcommand(StringArgumentType.getString(context, "args")))));

            dispatcher.register(ClientCommands.literal("flhypixel")
                    .executes(context -> forwardToHypixel(""))
                    .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                            .executes(context -> forwardToHypixel(StringArgumentType.getString(context, "args")))));
        });
    }

    private static int handleFl(String args) {
        return FriendsListConfig.getInstance().isEnabled() ? customSubcommand(args) : forwardToHypixel(args);
    }

    /** Blank -> open the menu. {@code add <name>} / {@code remove <name>} are a chat-only shortcut for the
     *  same actions the menu's own Add box / Remove button do (notes are still menu-only - typing one in
     *  chat would need quoting rules the brief never specifies). */
    private static int customSubcommand(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            return openCustomScreen();
        }
        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase(Locale.US);
        String name = parts.length > 1 ? parts[1].trim() : "";
        switch (sub) {
            case "add" -> {
                if (name.isEmpty()) {
                    ModChat.send("Friends List", ModChat.bad("Usage: /fl add <name>"));
                    return 0;
                }
                return addByName(name);
            }
            case "remove" -> {
                if (name.isEmpty()) {
                    ModChat.send("Friends List", ModChat.bad("Usage: /fl remove <name>"));
                    return 0;
                }
                return removeByName(name);
            }
            default -> {
                ModChat.send("Friends List", ModChat.bad("Unknown option \"" + sub + "\". "),
                        ModChat.text("Use /fl, /fl add <name>, /fl remove <name>, or /flhypixel."));
                return 0;
            }
        }
    }

    /** Resolves through the shared {@code players.PlayerNames} resolver (cache/tab-list immediately, a
     *  background Mojang lookup if neither already knows the name) rather than requiring the person to be
     *  on the tab list right now - an upgrade over this package's original tab-list-only stub, now that the
     *  real resolver exists. A name that never resolves (typo, never existed) simply never gets a second
     *  callback - see {@code PlayerNames}' own documented failure contract. */
    private static int addByName(String name) {
        UUID known = PlayerNames.uuidFor(name);
        if (known == null) {
            // No immediate answer - resolveAsync will still kick off a background Mojang lookup below and
            // add them automatically if it succeeds, but PlayerNames never calls back on a lookup FAILURE
            // (typo, never-existed account - see its own doc), so this can't promise a follow-up message.
            ModChat.send("Friends List", ModChat.dim("Looking up \"" + name + "\" - will add automatically if found."));
        }
        PlayerNames.resolveAsync(name, id -> {
            if (id == null) {
                return;
            }
            FriendsListConfig cfg = FriendsListConfig.getInstance();
            FriendsListConfig.Friend added = cfg.add(id, name, "");
            cfg.save();
            ModChat.send("Friends List", added == null ? ModChat.dim(name + " is already on your list.")
                    : ModChat.good("Added " + name + "."));
        });
        return 1;
    }

    private static int removeByName(String name) {
        FriendsListConfig cfg = FriendsListConfig.getInstance();
        FriendsListConfig.Friend existing = cfg.byName(name);
        UUID id = existing != null ? existing.uuid : PlayerNames.uuidFor(name);
        if (id == null || !cfg.remove(id)) {
            ModChat.send("Friends List", ModChat.bad(name + " isn't on your list."));
            return 0;
        }
        cfg.save();
        ModChat.send("Friends List", ModChat.good("Removed " + name + "."));
        return 1;
    }

    private static int openCustomScreen() {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /bestfriends and /log - the chat screen closing after the command would otherwise
        // replace this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new FriendsListScreen(null)));
        return 1;
    }

    private static int forwardToHypixel(String args) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) {
            return 0;
        }
        String trimmed = args == null ? "" : args.trim();
        client.player.connection.sendCommand(trimmed.isEmpty() ? "fl" : "fl " + trimmed);
        return 1;
    }
}
