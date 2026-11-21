package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModChat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.util.ModLog;

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
 * <p>Saved as Skyblock ITEM IDS per slot rather than as serialized stacks. An id is readable, hand-editable and
 * survives a change to how sim items are built; a serialized stack is none of those and would quietly rot the
 * first time an item's construction changed.
 */
public final class SimLoadout {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            ModPaths.config("killer560smod-sim-loadout.json");

    /** Hotbar plus the main inventory - the whole player inventory, in slot order. */
    private static final int SLOTS = 36;

    /** Skyblock id per slot, or null for an empty slot. */
    private static String[] slots = new String[SLOTS];
    private static boolean applyOnLoad = true;
    private static SimArmor armor = SimArmor.NONE;
    /** Hypixel loadout spot (1-12) each armor set is saved in, by ordinal (NONE unused): Terror 1, Thermodynamic 2. */
    private static final int[] DEFAULT_SPOTS = {0, 1, 2};
    private static final int[] spots = DEFAULT_SPOTS.clone();
    /** Per set (by ordinal), the piece ids its wardrobe slot holds since it was last left (SimArmor#held); null = its own pieces. */
    private static final String[][] kept = new String[SimArmor.values().length][];
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
        if (client.player == null) return;
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
        if (server == null) return;
        var uuid = client.player.getUUID();
        String[] wanted = slots.clone();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            for (int i = 0; i < SLOTS; i++) {
                String id = wanted[i];
                if (id == null) continue;
                ItemStack stack = SimItems.build(id);
                if (!stack.isEmpty()) {
                    // setItem, not add: the slot IS the point. Adding would drop things wherever there was
                    // room and undo the layout this feature exists to preserve.
                    sp.getInventory().setItem(i, stack);
                }
            }
        });
    }

    /** This client's saved loadout, one id or "" per slot (a co-op guest sends it to the host). */
    static java.util.List<String> slotsForRequest() {
        load();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : slots) out.add(id == null ? "" : id);
        return out;
    }

    /** Called when a sim world finishes loading. */
    public static void onSimEntered(Minecraft client) {
        load();
        if (applyOnLoad && hasAnything()) {
            apply(client);
        }
        applyArmor(client);
    }

    private static boolean hasAnything() {
        for (String s : slots) {
            if (s != null) return true;
        }
        return false;
    }

    private static void setSlot(int index, String id) {
        load();
        slots[index] = "none".equalsIgnoreCase(id) || "empty".equalsIgnoreCase(id) ? null : id.toUpperCase(java.util.Locale.ROOT);
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

    /** The armor set the sim wears (Sim Settings "Sim Armor"), saved in the loadout file. */
    public static synchronized SimArmor armor() {
        load();
        return armor;
    }

    /** Selects the set, saves it and puts it on (or takes it off) at once when a sim is running. */
    public static synchronized void setArmor(SimArmor set) {
        load();
        armor = set;
        persist();
        applyArmor(Minecraft.getInstance());
    }

    /** The /loadouts spot (1-12) {@code set} is saved in, 0 for NONE. */
    public static synchronized int spot(SimArmor set) {
        load();
        return spots[set.ordinal()];
    }

    /** The set saved in spot {@code n}, or NONE for an empty spot. */
    public static synchronized SimArmor setAt(int n) {
        load();
        for (SimArmor a : SimArmor.values()) if (a != SimArmor.NONE && spots[a.ordinal()] == n) return a;
        return SimArmor.NONE;
    }

    /** Saves {@code set} in spot {@code n} (1-12); a set already there takes this set's old spot, so two never share one. */
    public static synchronized void setSpot(SimArmor set, int n) {
        load();
        if (set == SimArmor.NONE || n < 1 || n > 12) return;
        SimArmor other = setAt(n);
        if (other != SimArmor.NONE && other != set) spots[other.ordinal()] = spots[set.ordinal()];
        spots[set.ordinal()] = n;
        persist();
    }

    /** The id of piece {@code i} that {@code set}'s slot keeps ("" empty), or null for the set's own piece. */
    static synchronized String keptId(SimArmor set, int i) {
        load();
        String[] k = kept[set.ordinal()];
        return k == null ? null : k[i];
    }

    /** {@code set}'s slot now holds {@code ids} (what was worn as it was left), saved with the loadout. */
    static synchronized void keep(SimArmor set, String[] ids) {
        load();
        kept[set.ordinal()] = ids.clone();
        persist();
    }

    /** Every wardrobe slot back to its own set's pieces (tests; a hand-swapped piece kept in a slot is dropped). */
    public static synchronized void resetKept() {
        load();
        java.util.Arrays.fill(kept, null);
        persist();
    }

    /** Puts the selected set on the sim player; a no-op outside a sim. */
    static void applyArmor(Minecraft client) {
        if (client.player == null || !SimState.canAct(client)) return;
        var server = client.getSingleplayerServer();
        if (server == null) return;
        var uuid = client.player.getUUID();
        SimArmor set = armor;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) SimArmor.wear(sp, set);
        });
    }

    /** Reads the file again, defaults first (a profile applied: ProfileManager.reloadAllConfigs, 2026-11-02). */
    public static synchronized void reload() {
        loaded = false;
        slots = new String[SLOTS];
        applyOnLoad = true;
        armor = SimArmor.NONE;
        System.arraycopy(DEFAULT_SPOTS, 0, spots, 0, spots.length);
        java.util.Arrays.fill(kept, null);
        load();
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            applyOnLoad = !o.has("applyOnLoad") || o.get("applyOnLoad").getAsBoolean();
            if (o.has("armor")) {
                try {
                    armor = SimArmor.valueOf(o.get("armor").getAsString());
                } catch (IllegalArgumentException ignored) {
                    armor = SimArmor.NONE;
                }
            }
            if (o.has("spots")) {
                JsonObject sp = o.getAsJsonObject("spots");
                for (SimArmor a : SimArmor.values()) {
                    if (a != SimArmor.NONE && sp.has(a.name())) spots[a.ordinal()] = Math.max(1, Math.min(12, sp.get(a.name()).getAsInt()));
                }
                if (spots[1] == spots[2]) System.arraycopy(DEFAULT_SPOTS, 0, spots, 0, spots.length);
            }
            JsonObject k = o.has("kept") ? o.getAsJsonObject("kept") : null;
            for (SimArmor a : SimArmor.values()) {
                if (k == null || a == SimArmor.NONE || !k.has(a.name())) continue;
                JsonArray ka = k.getAsJsonArray(a.name());
                if (ka.size() != 4) continue;
                String[] ids = new String[4];
                for (int i = 0; i < 4; i++) ids[i] = ka.get(i).isJsonNull() ? null : ka.get(i).getAsString();
                kept[a.ordinal()] = ids;
            }
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
            o.addProperty("armor", armor.name());
            JsonObject sp = new JsonObject();
            for (SimArmor a : SimArmor.values()) if (a != SimArmor.NONE) sp.addProperty(a.name(), spots[a.ordinal()]);
            o.add("spots", sp);
            JsonObject k = new JsonObject();
            for (SimArmor a : SimArmor.values()) {
                if (kept[a.ordinal()] == null) continue;
                JsonArray ka = new JsonArray();
                for (String id : kept[a.ordinal()]) ka.add(id);
                k.add(a.name(), ka);
            }
            o.add("kept", k);
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
