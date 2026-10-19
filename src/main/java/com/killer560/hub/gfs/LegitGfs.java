package com.killer560.hub.gfs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SackCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Legit GFS (killer560, 2026-10-19): {@code /k gfs pearl}, {@code boom}, {@code leap} or {@code jerry} refills that item from
 * your sacks, and each item can have its own keybind. It sends the same {@code /gfs <sack> <amount>} the game's own command
 * does, one request at a time, and never clicks anything. Auto GFS (cheat) is a separate feature.
 */
public final class LegitGfs {

    /** The four refillable items: the chat name, the sack name, and a full stack to ask for. */
    public enum Item {
        PEARL("pearl", "ender_pearl", 16, "Ender Pearl"),
        BOOM("boom", "superboom_tnt", 64, "Superboom TNT"),
        LEAP("leap", "spirit_leap", 16, "Spirit Leap"),
        JERRY("jerry", "inflatable_jerry", 64, "Inflatable Jerry");

        public final String word;
        public final String sack;
        public final int stack;
        public final String label;

        Item(String word, String sack, int stack, String label) {
            this.word = word;
            this.sack = sack;
            this.stack = stack;
            this.label = label;
        }

        static Item byWord(String word) {
            for (Item item : values()) {
                if (item.word.equalsIgnoreCase(word)) {
                    return item;
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-legitgfs.json");
    private static final String FEATURE = "GFS";

    private static final Map<Item, Integer> KEYS = new EnumMap<>(Item.class);
    private static final Map<Item, Boolean> WAS_DOWN = new EnumMap<>(Item.class);
    private static boolean loaded;
    /** The main switch: off stops the keybinds and /k gfs (killer560, 2026-10-19: "legit gfs should have a main toggle"). */
    private static boolean enabled = true;

    private LegitGfs() {
    }

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static void setEnabled(boolean v) {
        ensureLoaded();
        enabled = v;
        save();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(LegitGfs::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("k")
                        .then(ClientCommands.literal("gfs")
                                .then(ClientCommands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> {
                                            request(com.mojang.brigadier.arguments.StringArgumentType.getString(context, "item"));
                                            return 1;
                                        })))));
    }

    /** Asks for one item by its chat name ({@code pearl}, {@code boom}, {@code leap}, {@code jerry}). */
    /** How many of {@code item} the player carries, counted by the name each stack shows. */
    private static int carriedCount(Minecraft client, Item item) {
        int total = 0;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().contains(item.label)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    static void request(String word) {
        if (!isEnabled()) {
            return;
        }
        Item item = Item.byWord(word);
        if (item == null) {
            ModChat.send(FEATURE, ModChat.bad("Unknown item. Use "), ModChat.value("pearl, boom, leap or jerry"), ModChat.text("."));
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        // Only what it takes to top the stack off (killer560, 2026-10-19: "it should only get enough to top off the stack").
        int missing = Math.max(0, item.stack - carriedCount(client, item));
        if (missing == 0) {
            ModChat.send(FEATURE, ModChat.text(item.label + " is already full"));
            return;
        }
        if (!SackCommands.send(client, item.sack + " " + missing)) {
            ModChat.send(FEATURE, ModChat.bad("Can't refill " + item.label + " here - it needs Skyblock and no request in flight."));
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Refilling "), ModChat.value(item.label));
    }

    /** The key bound to an item, or {@link KeyUtil#NONE}. */
    public static int keyFor(Item item) {
        ensureLoaded();
        return KEYS.getOrDefault(item, KeyUtil.NONE);
    }

    /** Binds an item to a key ({@link KeyUtil#NONE} clears it) and saves. */
    public static void setKey(Item item, int code) {
        ensureLoaded();
        KEYS.put(item, KeyUtil.sanitizeBind(code));
        save();
    }

    private static void tick(Minecraft client) {
        ensureLoaded();
        if (!enabled || client.getWindow() == null || McCompat.screen(client) != null) {
            WAS_DOWN.clear();
            return;
        }
        for (Item item : Item.values()) {
            int code = KEYS.getOrDefault(item, KeyUtil.NONE);
            boolean down = code != KeyUtil.NONE && KeyUtil.isBindDown(client.getWindow(), code);
            boolean wasDown = WAS_DOWN.getOrDefault(item, false);
            if (down && !wasDown) {
                request(item.word);
            }
            WAS_DOWN.put(item, down);
        }
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            if (obj.has("enabled")) {
                enabled = obj.get("enabled").getAsBoolean();
            }
            for (Item item : Item.values()) {
                String key = item.name().toLowerCase(Locale.ROOT);
                if (obj.has(key)) {
                    KEYS.put(item, KeyUtil.sanitizeBind(obj.get(key).getAsInt()));
                }
            }
        } catch (Exception ignored) {
            KEYS.clear();
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            for (Item item : Item.values()) {
                obj.addProperty(item.name().toLowerCase(Locale.ROOT), KEYS.getOrDefault(item, KeyUtil.NONE));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
