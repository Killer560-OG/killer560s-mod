package com.killer560.hub.invsort;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /invsort} - save/load/list/delete named inventory layouts and run the sorter. Mirrors the
 * command-drives-everything shape {@code Ap3Commands}/{@code AutoRoutesCommands} already use for named,
 * file-backed configs: one chat line per action, the store owns naming rules, this layer just reports.
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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("invsort")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("save")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            save(StringArgumentType.getString(context, "name"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("load")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests(LAYOUT_SUGGEST)
                                        .executes(context -> {
                                            load(StringArgumentType.getString(context, "name"));
                                            return 1;
                                        })))
                        // "apply" - same thing, killer560's own wording ("Apply a layout").
                        .then(ClientCommands.literal("apply")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests(LAYOUT_SUGGEST)
                                        .executes(context -> {
                                            load(StringArgumentType.getString(context, "name"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("list").executes(context -> {
                            list();
                            return 1;
                        }))
                        .then(ClientCommands.literal("delete")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests(LAYOUT_SUGGEST)
                                        .executes(context -> {
                                            delete(StringArgumentType.getString(context, "name"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("reload").executes(context -> {
                            InventoryLayoutStore.reload();
                            ModChat.send(FEATURE, ModChat.good("Reloaded "),
                                    ModChat.value(InventoryLayoutStore.getInstance().listNames().size() + " layout(s)"),
                                    ModChat.text(" from " + InventoryLayoutStore.directory().getFileName() + "."));
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(context -> {
                            stop();
                            return 1;
                        }))));
    }

    // ---- actions -----------------------------------------------------------------------------------

    private static boolean ready() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ModChat.send(FEATURE, ModChat.bad("Not available in this build."));
            return false;
        }
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            ModChat.send(FEATURE, ModChat.bad("Mods are paused outside Skyblock / p3sim."));
            return false;
        }
        if (!InventorySorterConfig.getInstance().isEnabledRaw()) {
            ModChat.send(FEATURE, ModChat.bad("Auto Inventory Sorter is OFF - turn it on in its tab first."));
            return false;
        }
        return Minecraft.getInstance().player != null;
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands:"));
        ModChat.send(FEATURE, ModChat.value("/invsort save <name>"), ModChat.dim(" - save your current inventory as a layout"));
        ModChat.send(FEATURE, ModChat.value("/invsort load <name>"), ModChat.dim(" (or apply) - sort your inventory to match a saved layout"));
        ModChat.send(FEATURE, ModChat.value("/invsort list"), ModChat.dim(" - every saved layout"));
        ModChat.send(FEATURE, ModChat.value("/invsort delete <name>"), ModChat.dim(" - remove a saved layout"));
        ModChat.send(FEATURE, ModChat.value("/invsort stop"), ModChat.dim(" - stop a run in progress"));
        ModChat.send(FEATURE, ModChat.dim("You can't walk while a layout is being applied - it lets go the instant it finishes or is stopped."));
    }

    /** Snapshots the player's real current inventory (0-35, the same numbering {@link InventoryLayout} uses) and
     *  writes it out under {@code name} - "a snapshot of which item belongs in which inventory slot, captured
     *  from the inventory as it is right now" (killer560). Doesn't require any particular screen to be open;
     *  it reads straight off the player's own real Inventory object. */
    private static void save(String rawName) {
        if (!ready()) {
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        String error = InventoryLayoutStore.validateName(name);
        if (error != null) {
            ModChat.send(FEATURE, ModChat.bad(error));
            return;
        }
        Inventory inv = Minecraft.getInstance().player.getInventory();
        Map<Integer, String> slots = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String identity = ItemIdentity.of(stack);
            if (identity != null) {
                slots.put(i, identity);
            }
        }
        if (slots.isEmpty()) {
            ModChat.send(FEATURE, ModChat.bad("Your inventory is empty - nothing to save."));
            return;
        }
        String saveError = InventoryLayoutStore.getInstance().save(new InventoryLayout(name, slots));
        if (saveError != null) {
            ModChat.send(FEATURE, ModChat.bad(saveError));
            return;
        }
        ModChat.send(FEATURE, ModChat.good("Saved "), ModChat.value(name),
                ModChat.text(" - " + slots.size() + " slot" + (slots.size() == 1 ? "" : "s") + "."));
    }

    private static void load(String rawName) {
        if (!ready()) {
            return;
        }
        if (InventorySorterExecutor.isRunning()) {
            ModChat.send(FEATURE, ModChat.bad("Already sorting - /invsort stop first."));
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
        if (layout == null) {
            ModChat.send(FEATURE, ModChat.bad("No layout called "), ModChat.value(name),
                    ModChat.text(" - "), ModChat.value("/invsort list"), ModChat.text(" to see what's saved."));
            return;
        }
        if (!InventorySorterExecutor.start(layout)) {
            ModChat.send(FEATURE, ModChat.bad("Couldn't start - no player, or a session is already running."));
            return;
        }
        ModChat.send(FEATURE, ModChat.good("Sorting to "), ModChat.value(layout.name()),
                ModChat.dim(" (" + layout.size() + " slot" + (layout.size() == 1 ? "" : "s") + ") - you can't walk until it's done."));
    }

    private static void list() {
        List<String> names = InventoryLayoutStore.getInstance().listNames();
        if (names.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No saved layouts - "), ModChat.value("/invsort save <name>"),
                    ModChat.text(" while your inventory is arranged the way you want it."));
            return;
        }
        ModChat.send(FEATURE, ModChat.text(names.size() + " layout" + (names.size() == 1 ? "" : "s") + ":"));
        for (String name : names) {
            InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
            ModChat.send(FEATURE, ModChat.dim("- "), ModChat.value(name),
                    ModChat.dim(" (" + (layout == null ? 0 : layout.size()) + " slots)"));
        }
    }

    private static void delete(String rawName) {
        if (!ready()) {
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        if (!InventoryLayoutStore.getInstance().delete(name)) {
            ModChat.send(FEATURE, ModChat.bad("No layout called "), ModChat.value(name), ModChat.text("."));
            return;
        }
        ModChat.send(FEATURE, ModChat.good("Deleted "), ModChat.value(name), ModChat.text("."));
    }

    private static void stop() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        if (!InventorySorterExecutor.isRunning()) {
            ModChat.send(FEATURE, ModChat.text("Nothing to stop."));
            return;
        }
        InventorySorterExecutor.requestStop();
    }
}
