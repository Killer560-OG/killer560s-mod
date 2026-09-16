package com.killer560.hub.partycommands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;

/**
 * Persisted Party Commands settings - see {@link PartyCommandsFeature} for the ported OdinLegacy behaviour and
 * the teammate-only gate.
 * <p>
 * Every command is its own toggle and every one of them ships OFF, plus a second master switch
 * ({@link #isAllowDestructive()}) that the party-mutating ones ({@link Command#isDestructive()}: warp, warp +
 * transfer, kick, re-invite, demote, floor queue) need on top of their own toggle. Turning the feature on
 * therefore does nothing at all until the user deliberately picks which commands teammates may run.
 * <p>
 * Toggles live in one {@code EnumMap} rather than a field per command (there are 14 of them); each still gets
 * its own JSON key ("cmd.warp", "cmd.kick", ...) read through {@link ConfigJson}, so a hand-edited or
 * type-changed key only resets that one command instead of the whole file (2026-09-15 persistence rule).
 */
public final class PartyCommandsConfig {

    /** The ported OdinLegacy command set. {@code triggers} are the "!" words, first one is the canonical name. */
    public enum Command {
        HELP("Help", false, "help", "h"),
        WARP("Warp", true, "warp", "w"),
        WARP_TRANSFER("Warp + Transfer", true, "warptransfer", "wt"),
        ALL_INVITE("All Invite", false, "allinvite", "allinv"),
        TRANSFER("Transfer To Sender", false, "pt", "ptme", "transfer"),
        INVITE("Invite", false, "invite", "inv"),
        KICK("Kick", true, "kick", "k"),
        REINVITE("Reinvite", true, "reinv", "reinvite"),
        DEMOTE("Demote", true, "demote"),
        PROMOTE("Promote", false, "promote"),
        BOOP("Boop", false, "boop"),
        DOWNTIME("Downtime", false, "dt", "downtime"),
        UN_DOWNTIME("Un-Downtime", false, "undt", "undowntime"),
        QUEUE_INSTANCE("Queue Floor (!f7/!m7/!t5)", true, "f1"),
        RACISM("Racism (joke)", false, "racism");

        private final String label;
        private final boolean destructive;
        private final String[] triggers;

        Command(String label, boolean destructive, String... triggers) {
            this.label = label;
            this.destructive = destructive;
            this.triggers = triggers;
        }

        public String label() {
            return label;
        }

        /** Party-mutating / run-starting commands: also need {@link PartyCommandsConfig#isAllowDestructive()}. */
        public boolean isDestructive() {
            return destructive;
        }

        public String[] triggers() {
            return triggers.clone();
        }

        /** The canonical "!word" shown in {@code !help} replies and the settings tab. */
        public String canonical() {
            return triggers[0];
        }

        String key() {
            return "cmd." + name().toLowerCase(Locale.US);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-partycommands.json");

    private static PartyCommandsConfig instance;

    private boolean enabled = false;
    private boolean allowDestructive = false;
    private boolean confirmInvites = true;
    private final EnumMap<Command, Boolean> commands = new EnumMap<>(Command.class);

    private PartyCommandsConfig() {
        for (Command c : Command.values()) {
            commands.put(c, false);
        }
    }

    public static PartyCommandsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new PartyCommandsConfig();
            return;
        }
        PartyCommandsConfig cfg = new PartyCommandsConfig();
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.allowDestructive = ConfigJson.getBool(obj, "allowDestructive", false);
            cfg.confirmInvites = ConfigJson.getBool(obj, "confirmInvites", true);
            for (Command c : Command.values()) {
                cfg.commands.put(c, ConfigJson.getBool(obj, c.key(), false));
            }
        } catch (Exception ignored) {
            // Unreadable file: keep whatever the per-key readers managed, everything else stays at its default.
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("allowDestructive", allowDestructive);
            obj.addProperty("confirmInvites", confirmInvites);
            for (Command c : Command.values()) {
                obj.addProperty(c.key(), commands.getOrDefault(c, false));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated like every other feature getter - off entirely outside Skyblock/p3sim while "Skyblock Only" is on. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The real saved value, for the settings screen (which must show what is actually turned on). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowDestructive() {
        return allowDestructive;
    }

    public void setAllowDestructive(boolean allowDestructive) {
        this.allowDestructive = allowDestructive;
    }

    /** When on (default), {@code !invite} only prints a click-to-invite prompt instead of inviting by itself. */
    public boolean isConfirmInvites() {
        return confirmInvites;
    }

    public void setConfirmInvites(boolean confirmInvites) {
        this.confirmInvites = confirmInvites;
    }

    public boolean isOn(Command command) {
        return command != null && Boolean.TRUE.equals(commands.get(command));
    }

    /** Whether the command may actually run right now: feature on, its own toggle on, and - for the
     *  destructive ones - the separate destructive switch on too. */
    public boolean allows(Command command) {
        if (!isEnabled() || !isOn(command)) {
            return false;
        }
        return !command.isDestructive() || allowDestructive;
    }

    public void setOn(Command command, boolean value) {
        if (command != null) {
            commands.put(command, value);
        }
    }
}
