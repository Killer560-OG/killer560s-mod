package com.killer560.hub.autosell;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /autosell} - the master toggle, the sell/never-sell lists and starting/stopping a session. Everything
 * that touches an item goes through {@link AutoSellConfig}; this layer is just chat plumbing, same shape as
 * every other command class in this mod.
 */
public final class AutoSellCommands {

    public static final String FEATURE = "AutoSell";

    private AutoSellCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("autosell")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(context -> {
                            setEnabled(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(context -> {
                            setEnabled(false);
                            return 1;
                        }))
                        .then(ClientCommands.literal("list").executes(context -> {
                            list();
                            return 1;
                        }))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            add(StringArgumentType.getString(context, "item"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            remove(StringArgumentType.getString(context, "item"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("neversell")
                                .then(ClientCommands.literal("add")
                                        .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    neverSellAdd(StringArgumentType.getString(context, "item"));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    neverSellRemove(StringArgumentType.getString(context, "item"));
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("screentitle")
                                .then(ClientCommands.argument("regex", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            setScreenTitle(StringArgumentType.getString(context, "regex"));
                                            return 1;
                                        })))));
    }

    // ---- actions -----------------------------------------------------------------------------------

    private static boolean ready() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ModChat.send(FEATURE, ModChat.bad("Not available in this build."));
            return false;
        }
        return Minecraft.getInstance().player != null;
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands:"));
        ModChat.send(FEATURE, ModChat.value("/autosell on|off"), ModChat.dim(" - master toggle"));
        ModChat.send(FEATURE, ModChat.value("/autosell add held|<IDENTITY>"), ModChat.dim(" - add to the sell list"));
        ModChat.send(FEATURE, ModChat.value("/autosell remove held|<IDENTITY>"), ModChat.dim(" - remove from the sell list"));
        ModChat.send(FEATURE, ModChat.value("/autosell neversell add|remove held|<IDENTITY>"), ModChat.dim(" - the safety list; always wins"));
        ModChat.send(FEATURE, ModChat.value("/autosell list"), ModChat.dim(" - open the sell list as a menu"));
        ModChat.send(FEATURE, ModChat.value("/autosell screentitle <regex>"), ModChat.dim(" - which screen title counts as a sell screen (default matches anything with \"sell\" in it)"));
        ModChat.send(FEATURE, ModChat.dim("Nothing is ever clicked unless it's explicitly on the sell list, and the never-sell list always wins."));
    }

    private static void setEnabled(boolean on) {
        if (!ready()) {
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        cfg.setEnabled(on);
        cfg.save();
        ModChat.send(FEATURE, ModChat.text("Auto Sell "), on ? ModChat.good("ON") : ModChat.bad("OFF"), ModChat.text("."));
    }

    /** {@code "held"} resolves to the identity of whatever's in the main hand right now; anything else is taken
     *  as a raw identity string (a Skyblock id, upper-cased) - lets a real item id be typed straight from a
     *  wiki/NEU page as well as picked up off the item itself. */
    private static String resolveIdentity(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.equalsIgnoreCase("held")) {
            ItemStack held = Minecraft.getInstance().player == null ? ItemStack.EMPTY
                    : Minecraft.getInstance().player.getMainHandItem();
            return held.isEmpty() ? null : ItemIdentity.of(held);
        }
        return t.isBlank() ? null : t;
    }

    private static void add(String raw) {
        if (!ready()) {
            return;
        }
        String identity = resolveIdentity(raw);
        if (identity == null) {
            ModChat.send(FEATURE, ModChat.bad("Hold the item and use "), ModChat.value("/autosell add held"),
                    ModChat.text(", or type its identity directly."));
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        boolean added = cfg.addSellIdentity(identity);
        cfg.save();
        ModChat.send(FEATURE, added ? ModChat.good("Added ") : ModChat.text("Already on the list: "),
                ModChat.value(identity), ModChat.text("."));
    }

    private static void remove(String raw) {
        if (!ready()) {
            return;
        }
        String identity = resolveIdentity(raw);
        if (identity == null) {
            ModChat.send(FEATURE, ModChat.bad("Hold the item and use "), ModChat.value("/autosell remove held"),
                    ModChat.text(", or type its identity directly."));
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        boolean removed = cfg.removeSellIdentity(identity);
        cfg.save();
        ModChat.send(FEATURE, removed ? ModChat.good("Removed ") : ModChat.text("Wasn't on the list: "),
                ModChat.value(identity), ModChat.text("."));
    }

    private static void neverSellAdd(String raw) {
        if (!ready()) {
            return;
        }
        String identity = resolveIdentity(raw);
        if (identity == null) {
            ModChat.send(FEATURE, ModChat.bad("Hold the item and use "), ModChat.value("/autosell neversell add held"),
                    ModChat.text(", or type its identity directly."));
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        boolean added = cfg.addNeverSellIdentity(identity);
        cfg.save();
        ModChat.send(FEATURE, added ? ModChat.good("Added ") : ModChat.text("Already on the never-sell list: "),
                ModChat.value(identity), ModChat.text("."));
    }

    private static void neverSellRemove(String raw) {
        if (!ready()) {
            return;
        }
        String identity = resolveIdentity(raw);
        if (identity == null) {
            ModChat.send(FEATURE, ModChat.bad("Hold the item and use "), ModChat.value("/autosell neversell remove held"),
                    ModChat.text(", or type its identity directly."));
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        boolean removed = cfg.removeNeverSellIdentity(identity);
        cfg.save();
        ModChat.send(FEATURE, removed ? ModChat.good("Removed ") : ModChat.text("Wasn't on the never-sell list: "),
                ModChat.value(identity), ModChat.text("."));
    }

    /** Opens the sell list as a menu, where items can be added or taken off it (killer560, 2026-10-18). */
    private static void list() {
        if (!ready()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        com.killer560.hub.compat.McCompat.setScreen(client, new AutoSellListScreen(com.killer560.hub.compat.McCompat.screen(client)));
    }

    private static void setScreenTitle(String regex) {
        if (!ready()) {
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        String error = cfg.setScreenTitlePattern(regex);
        if (error != null) {
            ModChat.send(FEATURE, ModChat.bad(error));
            return;
        }
        cfg.save();
        ModChat.send(FEATURE, ModChat.good("Sell screen title pattern set to "), ModChat.value(regex), ModChat.text("."));
    }
}
