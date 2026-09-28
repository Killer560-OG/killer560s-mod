package com.killer560.hub.roomsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModChat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The hotbar and inventory the sim gives you every time a dungeon opens.
 *
 * <p>killer560 (2026-09-28): "Add an option somewhere to set a default hot bar/inventory loadout every time you
 * generate a new dungeon or load into one."
 *
 * <p>It matters more than convenience. Muscle memory for a route is partly muscle memory for WHICH SLOT things
 * are in - a pearl on 7 and the breaker on 3 - and a sim that hands you items in whatever order a command
 * produced them would train the wrong reach. Saving the layout once means every practice run starts identical
 * to the last, and identical to what he actually plays with.
 *
 * <p>Saved as Skyblock ITEM IDS per slot rather than as serialised stacks. An id is readable, hand-editable and
 * survives a change to how sim items are built; a serialised stack is none of those and would quietly rot the
 * first time an item's construction changed.
 */
public final class SimLoadout {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-sim-loadout.json");

    /** Hotbar plus the main inventory - the whole player inventory, in slot order. */
    private static final int SLOTS = 36;

    /** Skyblock id per slot, or null for an empty slot. */
    private static String[] slots = new String[SLOTS];
    private static boolean applyOnLoad = true;
    private static boolean loaded;

    private SimLoadout() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simloadout")
                        .then(ClientCommands.literal("save").executes(ctx -> {
                            save(Minecraft.getInstance());
                            return 1;
                        }))
                        .then(ClientCommands.literal("apply").executes(ctx -> {
                            apply(Minecraft.getInstance());
                            return 1;
                        }))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            slots = new String[SLOTS];
                            persist();
                            ModChat.send("Sim", ModChat.text("Loadout cleared"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("auto").executes(ctx -> {
                            applyOnLoad = !applyOnLoad;
                            persist();
                            ModChat.send("Sim", ModChat.text("Apply on entering the sim: "),
                                    ModChat.value(applyOnLoad ? "ON" : "OFF"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("slot")
                                .then(ClientCommands.argument("index", IntegerArgumentType.integer(0, SLOTS - 1))
                                        .then(ClientCommands.argument("id",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ctx -> {
                                                    setSlot(IntegerArgumentType.getInteger(ctx, "index"),
                                                            com.mojang.brigadier.arguments.StringArgumentType
                                                                    .getString(ctx, "id"));
                                                    return 1;
                                                }))))
                        .executes(ctx -> {
                            show();
                            return 1;
                        })));
    }

    /**
     * Records the inventory exactly as it is now.
     *
     * <p>Taken from what he is holding rather than asked for as a list: arranging a hotbar in game and pressing
     * save is a thing he can do in ten seconds, and typing nine item ids in the right order is not.
     */
    public static void save(Minecraft client) {
        if (client.player == null) {
            return;
        }
        load();
        String[] next = new String[SLOTS];
        int filled = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack);
            if (id != null) {
                next[i] = id;
                filled++;
            }
        }
        slots = next;
        persist();
        ModChat.send("Sim", ModChat.text("Loadout saved - "), ModChat.value(String.valueOf(filled)),
                ModChat.text(" item(s), slots kept exactly as they are"));
    }

    /** Puts the saved loadout back, in the same slots. */
    public static void apply(Minecraft client) {
        load();
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Loadouts only apply inside the sim."));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        var uuid = client.player.getUUID();
        String[] wanted = slots.clone();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            for (int i = 0; i < SLOTS; i++) {
                String id = wanted[i];
                if (id == null) {
                    continue;
                }
                ItemStack stack = SimItems.build(id);
                if (!stack.isEmpty()) {
                    // setItem, not add: the slot IS the point. Adding would drop things wherever there was
                    // room and undo the layout this feature exists to preserve.
                    sp.getInventory().setItem(i, stack);
                }
            }
        });
    }

    /** Called when a sim world finishes loading. */
    public static void onSimEntered(Minecraft client) {
        load();
        if (applyOnLoad && hasAnything()) {
            apply(client);
        }
    }

    private static boolean hasAnything() {
        for (String s : slots) {
            if (s != null) {
                return true;
            }
        }
        return false;
    }

    private static void setSlot(int index, String id) {
        load();
        slots[index] = "none".equalsIgnoreCase(id) || "empty".equalsIgnoreCase(id) ? null : id.toUpperCase();
        persist();
        ModChat.send("Sim", ModChat.text("Slot " + index + " = "),
                ModChat.value(slots[index] == null ? "empty" : slots[index]));
    }

    private static void show() {
        load();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            parts.add(i + ":" + (slots[i] == null ? "-" : slots[i]));
        }
        ModChat.send("Sim", ModChat.text("Hotbar "), ModChat.dim(String.join("  ", parts)));
        ModChat.send("Sim", ModChat.dim("/simloadout save | apply | clear | auto | slot <0-35> <ID>"));
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            applyOnLoad = !o.has("applyOnLoad") || o.get("applyOnLoad").getAsBoolean();
            JsonArray arr = o.getAsJsonArray("slots");
            for (int i = 0; arr != null && i < arr.size() && i < SLOTS; i++) {
                slots[i] = arr.get(i).isJsonNull() ? null : arr.get(i).getAsString();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the sim loadout", e);
        }
    }

    private static void persist() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("applyOnLoad", applyOnLoad);
            JsonArray arr = new JsonArray();
            for (String s : slots) {
                if (s == null) {
                    arr.add((String) null);
                } else {
                    arr.add(s);
                }
            }
            o.add("slots", arr);
            Files.writeString(FILE, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the sim loadout", e);
        }
    }
}
