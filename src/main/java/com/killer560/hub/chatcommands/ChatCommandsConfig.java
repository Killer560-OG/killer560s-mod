package com.killer560.hub.chatcommands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;

/** Persisted Chat Commands settings - see {@link ChatCommandsFeature}'s class doc for the real
 *  Odin-ported "!command" reply system this is built on (scoped down to informational replies only).
 *  Ships disabled by default, same as every other new feature in this mod.
 *  <p>
 *  Since 2026-09-21 this shares the "Party Commands" settings tab with {@code partycommands.PartyCommandsConfig}
 *  behind a single master toggle (killer560: "make it one toggle not two separate settings") - the tab keeps
 *  both this config's {@link #isEnabled()} and Party Commands' in sync, but they stay two files/classes so
 *  neither one's already-saved keys move or get migrated (2026-09-15 persistence rule: never silently reset
 *  what he has configured). */
public final class ChatCommandsConfig {

    /** One informational "!" reply, ported from Odin's {@code ChatCommands.kt}. {@code triggers} are the "!"
     *  words, first one canonical. {@code defaultOn} keeps the 8 commands that already existed before
     *  per-command toggles behaving exactly as before (any of them fired whenever Chat Commands + the channel
     *  were on) - only the 3 new ones added for Odin parity (2026-09-21 gap review) default OFF like every new
     *  feature in this mod. */
    public enum InfoCommand {
        COORDS("Coords", true, "coords", "co"),
        PING("Ping", true, "ping"),
        FPS("FPS", true, "fps"),
        TIME("Time", true, "time"),
        HOLDING("Holding", true, "holding"),
        COINFLIP("Coinflip", true, "cf", "coinflip"),
        EIGHT_BALL("8Ball", true, "8ball"),
        DICE("Dice", true, "dice"),
        // Odin has this one and we didn't (2026-09-21 gap review vs Odin 0.3.1). Needs a ping-driven
        // ServerTickClock window, so it reports "Unknown" instead of a made-up number when that isn't running.
        TPS("TPS", false, "tps"),
        // Odin has this one too; reads the same tab-list "Area:"/"Dungeon:" line as routes.SkyblockArea, but
        // with its own on-demand read since that class only ticks while Waypoint Routes is on (same reason
        // pathfinding.IslandDetector keeps its own copy - see that class's doc).
        LOCATION("Location", false, "location"),
        // Odin's own "!odin"/"!od" replies with ITS Discord invite - kept verbatim so every Odin trigger is
        // still present (killer560: wants every one of them), but the reply points at this mod's own Discord
        // instead of Odin's, plus "!killer560"/"!k560" as this mod's own branded alias for the same reply.
        DISCORD("Discord", false, "odin", "od", "killer560", "k560");

        private final String label;
        private final boolean defaultOn;
        private final String[] triggers;

        InfoCommand(String label, boolean defaultOn, String... triggers) {
            this.label = label;
            this.defaultOn = defaultOn;
            this.triggers = triggers;
        }

        public String label() {
            return label;
        }

        boolean defaultOn() {
            return defaultOn;
        }

        public String[] triggers() {
            return triggers.clone();
        }

        String key() {
            return "cmd." + name().toLowerCase(Locale.US);
        }

        static InfoCommand forTrigger(String word) {
            for (InfoCommand c : values()) {
                for (String trigger : c.triggers) {
                    if (trigger.equals(word)) {
                        return c;
                    }
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-chatcommands.json");

    private static ChatCommandsConfig instance;

    private boolean enabled = false;
    private boolean partyEnabled = true;
    private boolean guildEnabled = false;
    private boolean privateEnabled = true;
    private boolean coopEnabled = true;
    private final EnumMap<InfoCommand, Boolean> commands = new EnumMap<>(InfoCommand.class);

    private ChatCommandsConfig() {
        for (InfoCommand c : InfoCommand.values()) {
            commands.put(c, c.defaultOn());
        }
    }

    public static ChatCommandsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ChatCommandsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ChatCommandsConfig cfg = new ChatCommandsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.partyEnabled = ConfigJson.getBool(obj, "partyEnabled", true);
            cfg.guildEnabled = ConfigJson.getBool(obj, "guildEnabled", false);
            cfg.privateEnabled = ConfigJson.getBool(obj, "privateEnabled", true);
            cfg.coopEnabled = ConfigJson.getBool(obj, "coopEnabled", true);
            for (InfoCommand c : InfoCommand.values()) {
                cfg.commands.put(c, ConfigJson.getBool(obj, c.key(), c.defaultOn()));
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new ChatCommandsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("partyEnabled", partyEnabled);
            obj.addProperty("guildEnabled", guildEnabled);
            obj.addProperty("privateEnabled", privateEnabled);
            obj.addProperty("coopEnabled", coopEnabled);
            for (InfoCommand c : InfoCommand.values()) {
                obj.addProperty(c.key(), commands.getOrDefault(c, c.defaultOn()));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The real saved value, for the settings screen - see {@link com.killer560.hub.partycommands.PartyCommandsConfig#isEnabledRaw()}. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPartyEnabled() {
        return partyEnabled;
    }

    public void setPartyEnabled(boolean partyEnabled) {
        this.partyEnabled = partyEnabled;
    }

    public boolean isGuildEnabled() {
        return guildEnabled;
    }

    public void setGuildEnabled(boolean guildEnabled) {
        this.guildEnabled = guildEnabled;
    }

    public boolean isPrivateEnabled() {
        return privateEnabled;
    }

    public void setPrivateEnabled(boolean privateEnabled) {
        this.privateEnabled = privateEnabled;
    }

    public boolean isCoopEnabled() {
        return coopEnabled;
    }

    public void setCoopEnabled(boolean coopEnabled) {
        this.coopEnabled = coopEnabled;
    }

    public boolean isOn(InfoCommand command) {
        return command != null && Boolean.TRUE.equals(commands.get(command));
    }

    public void setOn(InfoCommand command, boolean value) {
        if (command != null) {
            commands.put(command, value);
        }
    }
}
