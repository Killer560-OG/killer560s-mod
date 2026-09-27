package com.killer560.hub.dungeonextras;

import com.google.gson.JsonParser;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /breakeraura} - config file management only (list / select / new / delete / reload) plus
 * {@code clear}, mirroring how {@code /ap3} and {@code /ar} carry their own command trees. Picking and breaking
 * blocks themselves stay exactly where they were - the Pick Block key and {@link BreakerAuraFeature#onClientTick}
 * - this is only the "which named config" layer {@link BreakerAuraStore} added.
 * <p>
 * Registered on every build, same as {@code Ap3Commands}: the legit jar keeps the command tree so a shared config
 * can't crash it, {@link #ready()} just says it is not available there. Unlike {@code /ap3}'s gate, this does NOT
 * require the master toggle or Skyblock/p3sim - choosing which wall-set is active is meta-configuration, and
 * killer560 already asked for picking itself to work with the aura off ("I should have a keybind to select
 * blocks"); requiring the feature to be ON just to organise its saved sets would be a strange asymmetry with that.
 */
public final class BreakerAuraCommands {

    public static final String FEATURE = "Breaker Aura";

    private BreakerAuraCommands() {
    }

    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_SUGGEST =
            (ctx, b) -> suggestTokens(b, BreakerAuraStore.listConfigNames());

    /** Same "complete the last token of a greedy string" trick {@code Ap3Commands} uses, so a name can be
     *  completed even though it may contain spaces. */
    private static CompletableFuture<Suggestions> suggestTokens(SuggestionsBuilder b, List<String> options) {
        String remaining = b.getRemaining();
        SuggestionsBuilder offset = b.createOffset(b.getStart());
        String low = remaining.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(low)) {
                offset.suggest(o);
            }
        }
        return offset.buildFuture();
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("breakeraura")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("clear").executes(context -> {
                            if (ready()) {
                                clear();
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("reload").executes(context -> {
                            if (ready()) {
                                reload();
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("config")
                                .executes(context -> {
                                    if (ready()) {
                                        list();
                                    }
                                    return 1;
                                })
                                .then(ClientCommands.literal("list").executes(context -> {
                                    if (ready()) {
                                        list();
                                    }
                                    return 1;
                                }))
                                .then(ClientCommands.literal("select")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .suggests(CONFIG_SUGGEST)
                                                .executes(context -> {
                                                    if (ready()) {
                                                        select(StringArgumentType.getString(context, "name"));
                                                    }
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("new")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    if (ready()) {
                                                        create(StringArgumentType.getString(context, "name"));
                                                    }
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("delete")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .suggests(CONFIG_SUGGEST)
                                                .executes(context -> {
                                                    if (ready()) {
                                                        delete(StringArgumentType.getString(context, "name"));
                                                    }
                                                    return 1;
                                                }))))));
    }

    /** Cheat build only - the one gate every Breaker Aura config action needs. See the class doc for why this
     *  does not also require the master toggle or Skyblock/p3sim. */
    private static boolean ready() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ModChat.send(FEATURE, ModChat.bad("Not available in this build."));
            return false;
        }
        return true;
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands:"));
        ModChat.send(FEATURE, ModChat.value("/breakeraura config list"), ModChat.dim(" - every config in the folder, and which is active"));
        ModChat.send(FEATURE, ModChat.value("/breakeraura config select <name>"), ModChat.dim(" - switch which config is active"));
        ModChat.send(FEATURE, ModChat.value("/breakeraura config new <name>"), ModChat.dim(" - create an empty config and switch to it"));
        ModChat.send(FEATURE, ModChat.value("/breakeraura config delete <name>"), ModChat.dim(" - delete a config that is not active"));
        ModChat.send(FEATURE, ModChat.value("/breakeraura clear"), ModChat.dim(" - forget every pick in the active config"));
        ModChat.send(FEATURE, ModChat.value("/breakeraura reload"), ModChat.dim(" - re-read the active config's file from disk"));
        ModChat.send(FEATURE, ModChat.dim("Pick/unpick blocks with the Pick Block key (set in the tab) - there is no chat command for that."));
    }

    private static void list() {
        List<String> names = BreakerAuraStore.listConfigNames();
        String active = DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile();
        ModChat.send(FEATURE, ModChat.text(names.size() + " config" + (names.size() == 1 ? "" : "s") + " in "),
                ModChat.value(BreakerAuraStore.directory().getFileName().toString() + "/"), ModChat.text(":"));
        for (String n : names) {
            ModChat.send(FEATURE, n.equalsIgnoreCase(active) ? ModChat.good("> " + n) : ModChat.dim("  " + n));
        }
    }

    private static void select(String raw) {
        String clean = BreakerAuraStore.normalizeConfigName(raw);
        String error = clean == null ? "Type a name first." : BreakerAuraStore.validateConfigName(clean);
        if (error == null && BreakerAuraStore.listConfigNames().stream().noneMatch(n -> n.equalsIgnoreCase(clean))) {
            error = "No config called " + clean + ".";
        }
        if (error != null) {
            ModChat.send(FEATURE, ModChat.bad(error));
            return;
        }
        BreakerAuraStore.select(clean);
        ModChat.send(FEATURE, ModChat.good("Using " + DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile() + "."));
    }

    private static void create(String raw) {
        String error = BreakerAuraStore.createConfig(raw);
        if (error != null) {
            ModChat.send(FEATURE, ModChat.bad(error));
            return;
        }
        ModChat.send(FEATURE, ModChat.good("Created and using " + DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile() + "."));
    }

    private static void delete(String raw) {
        String name = BreakerAuraStore.normalizeConfigName(raw);
        String error = name == null ? "Type a name first." : BreakerAuraStore.delete(name);
        if (error != null) {
            ModChat.send(FEATURE, ModChat.bad(error));
            return;
        }
        ModChat.send(FEATURE, ModChat.good("Deleted " + name + "."));
    }

    private static void clear() {
        int n = BreakerAuraFeature.clearSelection();
        ModChat.send(FEATURE, ModChat.good("Cleared " + n + " picked block" + (n == 1 ? "" : "s") + " from "
                + DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile() + "."));
    }

    /** Same job as {@code /ap3 reload}: re-reads the active config's file so a friend's copy dropped into the
     *  folder, or a hand-edit, works without a restart. Syntax is checked here first so a broken file gets NAMED
     *  in chat instead of quietly loading as zero picks. */
    private static void reload() {
        Path file = BreakerAuraStore.file();
        String parseError = null;
        boolean exists = Files.isRegularFile(file);
        if (exists) {
            try {
                JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                parseError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        BreakerAuraStore.reload();
        int loaded = BreakerAuraStore.getInstance().pickedKeys().size();
        if (parseError != null) {
            ModChat.send(FEATURE, ModChat.bad("Failed to parse "), ModChat.value(file.getFileName().toString()),
                    ModChat.bad(": " + parseError), ModChat.dim(" - fix the file (or paste a good copy back in) and /breakeraura reload again."));
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Reloaded "), ModChat.value(loaded + " picked block" + (loaded == 1 ? "" : "s")),
                ModChat.text(" from "), ModChat.value(file.getFileName().toString()),
                ModChat.dim(exists ? "." : " - no file yet; it's written the first time you pick a block."));
    }
}
