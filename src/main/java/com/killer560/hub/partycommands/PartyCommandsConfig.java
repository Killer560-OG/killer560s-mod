package com.killer560.hub.partycommands;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;

/**
 * Persisted Party Commands settings - see {@link PartyCommandsFeature}.
 * <p>
 * <b>Three sections, 2026-10-08.</b> killer560: <i>"Remove the destructive commands section, remove the confirms
 * invites, remove the (joke) by racism, remove info commands and put those all under one set. The only 3 sets should
 * be the general toggle, which chats can use (kc gc pc ac etc) then the commands you want able to be used. The
 * messages need to have a very slight delay after it actually receives the command..."</i> So this file now holds
 * exactly: {@link #isEnabled the master toggle}, one toggle per {@link Channel}, one toggle per {@link Command}
 * (party management and the old informational "Chat Commands" replies in ONE list), and {@link #getReplyDelayMs}.
 * <p>
 * <b>Migration (file version 2).</b> A version-1 file - or none at all beside an old
 * {@code killer560smod-chatcommands.json} - is read once and rewritten:
 * <ul>
 * <li>master: on if EITHER old master was on (the merged tab already showed it that way);</li>
 * <li>a party-management command keeps its own toggle, except that one of the old "destructive" ones (warp, warp +
 *     transfer, kick, kick offline, reinvite, demote, queue floor) is only kept ON when "Allow Destructive Commands"
 *     was on too - that is what actually ran before, and switching one on by removing the gate would make his client
 *     start kicking or warping off a teammate's message when it never did;</li>
 * <li>each informational command keeps its toggle from the old Chat Commands file (its own default when unset);</li>
 * <li>channels: party on if either feature used it, guild/private/co-op from the old Chat Commands channel toggles,
 *     all chat off, Mod Chat on;</li>
 * <li>"Confirm Invites" and the "racism" command are not carried over - both are gone.</li>
 * </ul>
 * The decision reads the OLD files' keys only, never this object's fields after a carry-over (docs/LESSONS.md).
 */
public final class PartyCommandsConfig {

    /** Where a "!" line came from, and so where its reply goes. {@code prefix} is the Hypixel command that posts
     *  into it ("msg" takes the sender's name after it); Mod Chat's "kc" is this mod's own and goes to the relay. */
    public enum Channel {
        PARTY("Party", "pc", true),
        GUILD("Guild", "gc", false),
        ALL("All Chat", "ac", false),
        MOD_CHAT("Mod Chat", "kc", true),
        PRIVATE("Private", "msg", true),
        COOP("Co-op", "cc", true);

        private final String label;
        private final String prefix;
        private final boolean defaultOn;

        Channel(String label, String prefix, boolean defaultOn) {
            this.label = label;
            this.prefix = prefix;
            this.defaultOn = defaultOn;
        }

        /** Button text, e.g. "Party (pc)". */
        public String label() {
            return label + " (" + prefix + ")";
        }

        public String prefix() {
            return prefix;
        }

        String key() {
            return "channel." + name().toLowerCase(Locale.US);
        }
    }

    /** What a command does to the world - decides the teammate gate in {@link PartyCommandsFeature}. */
    public enum Kind {
        /** Changes the party or the run (warp, kick, invite, queue, downtime...): only a real teammate may ask. */
        PARTY,
        /** Answers with a line of text (coords, ping, 8ball...) or a harmless social action (boop). */
        INFO
    }

    /** Every command, in the order the tab lists them. {@code triggers} are the "!" words, the first canonical. */
    public enum Command {
        HELP("Help", Kind.INFO, false, "help", "h"),
        WARP("Warp", Kind.PARTY, false, "warp", "w"),
        WARP_TRANSFER("Warp + Transfer", Kind.PARTY, false, "warptransfer", "wt"),
        ALL_INVITE("All Invite", Kind.PARTY, false, "allinvite", "allinv"),
        TRANSFER("Transfer To Sender", Kind.PARTY, false, "pt", "ptme", "transfer"),
        INVITE("Invite", Kind.PARTY, false, "invite", "inv"),
        KICK("Kick", Kind.PARTY, false, "kick", "k"),
        KICK_OFFLINE("Kick Offline", Kind.PARTY, false, "kickoffline", "ko"),
        REINVITE("Reinvite", Kind.PARTY, false, "reinv", "reinvite"),
        DEMOTE("Demote", Kind.PARTY, false, "demote"),
        PROMOTE("Promote", Kind.PARTY, false, "promote"),
        QUEUE_INSTANCE("Queue Floor (!f7/!m7/!t5)", Kind.PARTY, false, "f1"),
        DOWNTIME("Downtime", Kind.PARTY, false, "dt", "downtime"),
        UN_DOWNTIME("Un-Downtime", Kind.PARTY, false, "undt", "undowntime"),
        BOOP("Boop", Kind.INFO, false, "boop"),
        // The informational replies, formerly the separate "Chat Commands" (chatcommands/) list. The eight that
        // already existed before per-command toggles default ON, as they always have; the rest OFF.
        COORDS("Coords", Kind.INFO, true, "coords", "co"),
        PING("Ping", Kind.INFO, true, "ping"),
        FPS("FPS", Kind.INFO, true, "fps"),
        TIME("Time", Kind.INFO, true, "time"),
        HOLDING("Holding", Kind.INFO, true, "holding"),
        COINFLIP("Coinflip", Kind.INFO, true, "cf", "coinflip"),
        EIGHT_BALL("8Ball", Kind.INFO, true, "8ball"),
        DICE("Dice", Kind.INFO, true, "dice"),
        TPS("TPS", Kind.INFO, false, "tps"),
        LOCATION("Location", Kind.INFO, false, "location"),
        DISCORD("Discord", Kind.INFO, false, "odin", "od", "killer560", "k560");

        private final String label;
        private final Kind kind;
        private final boolean defaultOn;
        private final String[] triggers;

        Command(String label, Kind kind, boolean defaultOn, String... triggers) {
            this.label = label;
            this.kind = kind;
            this.defaultOn = defaultOn;
            this.triggers = triggers;
        }

        public String label() {
            return label;
        }

        public Kind kind() {
            return kind;
        }

        public boolean defaultOn() {
            return defaultOn;
        }

        public String[] triggers() {
            return triggers.clone();
        }

        /** The canonical "!word" shown in {@code !help} replies. */
        public String canonical() {
            return triggers[0];
        }

        String key() {
            return "cmd." + name().toLowerCase(Locale.US);
        }
    }

    /** The old "destructive" set, which also needed "Allow Destructive Commands" - read only by the migration. */
    private static final java.util.Set<Command> LEGACY_DESTRUCTIVE = java.util.EnumSet.of(Command.WARP,
            Command.WARP_TRANSFER, Command.KICK, Command.KICK_OFFLINE, Command.REINVITE, Command.DEMOTE,
            Command.QUEUE_INSTANCE);
    /** The old Chat Commands file's informational commands, by their key in that file (same "cmd.x" shape). */
    private static final java.util.Set<Command> LEGACY_INFO = java.util.EnumSet.of(Command.COORDS, Command.PING,
            Command.FPS, Command.TIME, Command.HOLDING, Command.COINFLIP, Command.EIGHT_BALL, Command.DICE,
            Command.TPS, Command.LOCATION, Command.DISCORD);

    static final int VERSION = 2;
    public static final int DEFAULT_REPLY_DELAY_MS = 200;
    public static final int MAX_REPLY_DELAY_MS = 1000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-partycommands.json");
    /** The retired Chat Commands file; read by the version-1 migration, never written. */
    private static final Path LEGACY_CHAT_PATH = ModPaths.config("killer560smod-chatcommands.json");

    private static PartyCommandsConfig instance;

    private boolean enabled = false;
    private int replyDelayMs = DEFAULT_REPLY_DELAY_MS;
    private final EnumMap<Channel, Boolean> channels = new EnumMap<>(Channel.class);
    private final EnumMap<Command, Boolean> commands = new EnumMap<>(Command.class);

    private PartyCommandsConfig() {
        for (Channel ch : Channel.values()) {
            channels.put(ch, ch.defaultOn);
        }
        for (Command c : Command.values()) {
            commands.put(c, c.defaultOn());
        }
    }

    public static PartyCommandsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PartyCommandsConfig cfg = new PartyCommandsConfig();
        JsonObject obj = read(CONFIG_PATH);
        JsonObject legacyChat = read(LEGACY_CHAT_PATH);
        // Decided by the key's PRESENCE: only version 2+ writes "version", so a version-1 file is exactly one without it.
        boolean current = obj != null && obj.has("version");
        if (current) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.replyDelayMs = clampDelay(ConfigJson.getInt(obj, "replyDelayMs", DEFAULT_REPLY_DELAY_MS));
            for (Channel ch : Channel.values()) {
                cfg.channels.put(ch, ConfigJson.getBool(obj, ch.key(), ch.defaultOn));
            }
            for (Command c : Command.values()) {
                cfg.commands.put(c, ConfigJson.getBool(obj, c.key(), c.defaultOn()));
            }
            instance = cfg;
            return;
        }
        if (obj == null && legacyChat == null) {
            instance = cfg;   // a fresh install: defaults, nothing to migrate, nothing written until he changes one
            return;
        }
        migrate(cfg, obj, legacyChat);
        instance = cfg;
        cfg.save();
    }

    /** Version-1 files to version 2 - see the class doc. Reads only the OLD objects' keys. */
    static void migrate(PartyCommandsConfig cfg, JsonObject oldParty, JsonObject oldChat) {
        boolean partyOn = oldParty != null && ConfigJson.getBool(oldParty, "enabled", false);
        boolean chatOn = oldChat != null && ConfigJson.getBool(oldChat, "enabled", false);
        cfg.enabled = partyOn || chatOn;
        boolean allowDestructive = oldParty != null && ConfigJson.getBool(oldParty, "allowDestructive", false);
        for (Command c : Command.values()) {
            if (LEGACY_INFO.contains(c)) {
                cfg.commands.put(c, oldChat == null ? c.defaultOn() : ConfigJson.getBool(oldChat, c.key(), c.defaultOn()));
            } else {
                boolean own = oldParty != null && ConfigJson.getBool(oldParty, c.key(), false);
                cfg.commands.put(c, own && (!LEGACY_DESTRUCTIVE.contains(c) || allowDestructive));
            }
        }
        boolean chatParty = oldChat == null || ConfigJson.getBool(oldChat, "partyEnabled", true);
        cfg.channels.put(Channel.PARTY, partyOn || chatParty || oldParty != null);
        cfg.channels.put(Channel.GUILD, oldChat != null && ConfigJson.getBool(oldChat, "guildEnabled", false));
        cfg.channels.put(Channel.PRIVATE, oldChat == null || ConfigJson.getBool(oldChat, "privateEnabled", true));
        cfg.channels.put(Channel.COOP, oldChat == null || ConfigJson.getBool(oldChat, "coopEnabled", true));
        cfg.channels.put(Channel.ALL, false);
        cfg.channels.put(Channel.MOD_CHAT, true);
        cfg.replyDelayMs = DEFAULT_REPLY_DELAY_MS;
    }

    private static JsonObject read(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;   // unreadable: treated as absent, so defaults (never a half-read mix)
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("version", VERSION);
            obj.addProperty("enabled", enabled);
            obj.addProperty("replyDelayMs", replyDelayMs);
            for (Channel ch : Channel.values()) {
                obj.addProperty(ch.key(), channels.getOrDefault(ch, ch.defaultOn));
            }
            for (Command c : Command.values()) {
                obj.addProperty(c.key(), commands.getOrDefault(c, c.defaultOn()));
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

    public boolean isChannelOn(Channel channel) {
        return channel != null && Boolean.TRUE.equals(channels.get(channel));
    }

    public void setChannelOn(Channel channel, boolean value) {
        if (channel != null) {
            channels.put(channel, value);
        }
    }

    public boolean isOn(Command command) {
        return command != null && Boolean.TRUE.equals(commands.get(command));
    }

    public void setOn(Command command, boolean value) {
        if (command != null) {
            commands.put(command, value);
        }
    }

    /** Whether the command may run right now: feature on and its own toggle on. */
    public boolean allows(Command command) {
        return isEnabled() && isOn(command);
    }

    /** How long after a command arrives its reply or action is sent (default 200 ms). Hypixel answers "You are
     *  sending commands too fast" to a command sent in the same instant as the chat line that asked for it. */
    public int getReplyDelayMs() {
        return replyDelayMs;
    }

    public void setReplyDelayMs(int replyDelayMs) {
        this.replyDelayMs = clampDelay(replyDelayMs);
    }

    private static int clampDelay(int ms) {
        return Math.max(0, Math.min(MAX_REPLY_DELAY_MS, ms));
    }
}
