package com.killer560.hub.invsort;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * {@code /invsort} - opens the layouts menu ({@link InventorySorterScreen}), where layouts are made, saved, renamed,
 * deleted and bound to keys. The commands only APPLY a layout (killer560, 2026-10-08: "You should be able to set
 * keybinds to swap to different inventories and do it through commands still, just not saving through commands"):
 * {@code /invsort apply <name>}, {@code /invsort list}, {@code /invsort stop}. Cheat build only - the whole command
 * is never registered in a legit jar.
 */
public final class InventorySorterCommands {

    public static final String FEATURE = "InvSort";

    private static final SuggestionProvider<FabricClientCommandSource> LAYOUT_SUGGEST =
            (ctx, b) -> {
                String remaining = b.getRemaining().toLowerCase(java.util.Locale.ROOT);
                for (String name : InventoryLayoutStore.getInstance().listNames()) {
                    if (name.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                        b.suggest(name);
                    }
                }
                return b.buildFuture();
            };

    private InventorySorterCommands() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("invsort")
                        .executes(context -> {
                            openMenu();
                            return 1;
                        })
                        .then(ClientCommands.literal("apply")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests(LAYOUT_SUGGEST)
                                        .executes(context -> {
                                            InventorySorterExecutor.applyByName(StringArgumentType.getString(context, "name"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("list").executes(context -> {
                            list();
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(context -> {
                            stop();
                            return 1;
                        }))));
    }

    /** Opens the layouts menu on the next task - the chat screen that ran the command closes itself first. */
    public static void openMenu() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        client.execute(() -> client.setScreenAndShow(new InventorySorterScreen(null)));
    }

    private static void list() {
        List<String> names = InventoryLayoutStore.getInstance().listNames();
        if (names.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No saved layouts - "), ModChat.value("/invsort"),
                    ModChat.text(" opens the menu to make one."));
            return;
        }
        ModChat.send(FEATURE, ModChat.text(names.size() + " layout" + (names.size() == 1 ? "" : "s") + ":"));
        for (String name : names) {
            InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
            ModChat.send(FEATURE, ModChat.dim("- "), ModChat.value(name),
                    ModChat.dim(" (" + (layout == null ? 0 : layout.size()) + " slots)"));
        }
    }

    private static void stop() {
        if (!InventorySorterExecutor.isRunning()) {
            ModChat.send(FEATURE, ModChat.text("Nothing to stop."));
            return;
        }
        InventorySorterExecutor.requestStop();
    }
}
