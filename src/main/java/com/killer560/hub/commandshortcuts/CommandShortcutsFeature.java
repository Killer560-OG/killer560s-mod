package com.killer560.hub.commandshortcuts;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * killer560's item 8.3: "Command shortcuts: /f7, /m7, /infernal, every cata and Kuudra tier." Client-side
 * aliases that expand to the one real Hypixel command that actually queues a dungeon/Kuudra instance -
 * {@code /joininstance <id>} - so {@code /f7} sends {@code /joininstance catacombs_floor_seven} exactly the
 * way a real "/f7" typed by a party leader would.
 * <p>
 * <b>Where the real syntax came from.</b> Not guessed - this mod already sends {@code /joininstance} for the
 * exact same instance ids from {@code partycommands.PartyCommandsFeature} (Odin's ported {@code !f1}..{@code !f7}/
 * {@code !m1}..{@code !m7}/{@code !t1}..{@code !t5} floor-queue command, {@code instanceFor()}/{@code QUEUE_INSTANCE}):
 * {@code catacombs_floor_<word>}, {@code master_catacombs_floor_<word>}, {@code kuudra_<tier>} with
 * {@code tier} one of {@code normal, hot, burning, fiery, infernal} (Hypixel's own internal name for the
 * tier killer560 and the in-game menu call "Basic" is {@code normal} - confirmed against both
 * {@code PartyCommandsFeature.KUUDRA_TIERS} and Devonian's {@code kuudraTiers}/{@code DevonianCommand.kt},
 * which send the identical {@code joininstance kuudra_<tier>} for its own {@code !t1}..{@code !t5}).
 * NoammAddons' {@code PartyHelper.kt}/{@code DungeonUtils.kt} independently confirm the same
 * {@code CATACOMBS_FLOOR_<WORD>} / {@code MASTER_CATACOMBS_FLOOR_<WORD>} ids (Hypixel's own command parsing is
 * case-insensitive; this mod sends them lower-case to match {@code PartyCommandsFeature}'s existing, already
 * shipped strings).
 * <p>
 * <b>The one id this mod had no prior example for: Catacombs Floor 0 (the entrance).</b>
 * {@code PartyCommandsFeature}'s own floor pattern only accepts 1-7 (Odin never had an "!f0"), so there is no
 * already-shipped, already-tested string to copy for it. NoammAddons builds its floor id from one array,
 * {@code FLOOR_NAMES = ["ENTRANCE", "ONE", ... "SEVEN"]}, plugged into the SAME {@code CATACOMBS_FLOOR_<WORD>}
 * template used for every other floor - i.e. {@code catacombs_floor_entrance} - so that is what {@code /f0}
 * sends here, for consistency with every other floor id in this file. Devonian instead special-cases the
 * entrance as {@code catacombs_entrance} (no "floor") sent through the DIFFERENT {@code /joindungeon} command
 * (not {@code /joininstance}) for every floor including the entrance - a second, older-looking convention
 * that disagrees with both NoammAddons and this mod's own shipped {@code /joininstance} usage. Flagged in the
 * staging notes as unverified; if {@code /f0} doesn't work in-game, {@code catacombs_entrance} via
 * {@code /joindungeon} is the fallback to try.
 * <p>
 * <b>Master Mode has no floor 0</b> - Hypixel's Master Mode starts at floor 1, so there is no {@code /m0}
 * (matches {@code PartyCommandsFeature}'s own "!m1".."!m7" range).
 * <p>
 * <b>Registration and the collision risk.</b> Modelled on {@code profileviewer.ProfileViewerFeature}'s
 * {@code /pv} - a plain {@code ClientCommandRegistrationCallback} literal per alias, no gating inside a
 * shared command tree. Every alias here is a short, generic word ({@code f7}, {@code m7}, {@code basic},
 * {@code hot}, {@code burning}, {@code fiery}, {@code infernal}, {@code kuudra}) that ANOTHER mod (or a future
 * vanilla/Hypixel client command) could equally plausibly register - Brigadier does not arbitrate that, it is
 * whichever mod's {@code ClientCommandRegistrationCallback} listener runs last for that literal. That is
 * exactly why each one gets its own on/off toggle ({@link CommandShortcutsConfig}): a shortcut that is turned
 * OFF is never added to the dispatcher at all (checked once, right here, when the event fires - see
 * {@link #register()}), instead of being registered-but-a-no-op, so switching one off actually frees that
 * literal for whatever else wants it. The trade-off (noted in the staging notes as a real limitation): Fabric
 * rebuilds the client command dispatcher when it (re)connects to a server/world, not the instant a checkbox is
 * clicked, so a toggle flipped mid-session takes effect on the next join, not immediately - same as every
 * other "must rejoin" caveat already in this codebase (e.g. {@code SkyblockGate}'s sidebar-based detection).
 */
public final class CommandShortcutsFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-commandshortcuts");

    public static final String FEATURE = "Command Shortcuts";

    /**
     * Every shortcut this feature ships. {@code literal} is the bare chat command word ({@code id} doubles as
     * the config key via {@code name()}); {@code instanceId} is the exact argument {@code /joininstance} takes;
     * {@code label} is what the settings tab and tooltip show.
     */
    public enum Shortcut {
        F0("f0", "catacombs_floor_entrance", "F0 (Catacombs Entrance)"),
        F1("f1", "catacombs_floor_one", "F1"),
        F2("f2", "catacombs_floor_two", "F2"),
        F3("f3", "catacombs_floor_three", "F3"),
        F4("f4", "catacombs_floor_four", "F4"),
        F5("f5", "catacombs_floor_five", "F5"),
        F6("f6", "catacombs_floor_six", "F6"),
        F7("f7", "catacombs_floor_seven", "F7"),
        M1("m1", "master_catacombs_floor_one", "M1"),
        M2("m2", "master_catacombs_floor_two", "M2"),
        M3("m3", "master_catacombs_floor_three", "M3"),
        M4("m4", "master_catacombs_floor_four", "M4"),
        M5("m5", "master_catacombs_floor_five", "M5"),
        M6("m6", "master_catacombs_floor_six", "M6"),
        M7("m7", "master_catacombs_floor_seven", "M7"),
        /** Hypixel/the API call this tier "normal" internally; the menu and killer560 call it "Basic". */
        KUUDRA_BASIC("basic", "kuudra_normal", "Kuudra Basic"),
        KUUDRA_HOT("hot", "kuudra_hot", "Kuudra Hot"),
        KUUDRA_BURNING("burning", "kuudra_burning", "Kuudra Burning"),
        KUUDRA_FIERY("fiery", "kuudra_fiery", "Kuudra Fiery"),
        KUUDRA_INFERNAL("infernal", "kuudra_infernal", "Kuudra Infernal");

        public final String literal;
        public final String instanceId;
        public final String label;

        Shortcut(String literal, String instanceId, String label) {
            this.literal = literal;
            this.instanceId = instanceId;
            this.label = label;
        }

        /** What the tab shows next to each row, and what the "off" chat line points at. */
        public String expandsTo() {
            return "/joininstance " + instanceId;
        }

        public boolean isKuudra() {
            return instanceId.startsWith("kuudra_");
        }
    }

    /**
     * 2026-09-27 regroup (killer560: "clump them into groups like catacombs/mastermode as one toggle for
     * each floor. Same for kuudra.") - the settings tab used to show one toggle per {@link Shortcut} (20
     * rows: 8 Catacombs + 7 Master Mode + 5 Kuudra), which made for a very long list. Now there is one
     * toggle per {@code Group} instead: each Catacombs floor's toggle (F1-F7) covers BOTH that floor's
     * normal Catacombs shortcut AND its Master Mode shortcut together, since killer560 always wants a
     * floor's two variants on/off as one unit; F0 has no Master Mode so its group is just itself. Kuudra
     * already had exactly one shortcut per tier, so grouping it "the same way" just means every tier gets
     * its own {@code Group} too, on the same storage as everything else - see
     * {@link CommandShortcutsConfig}. The individual {@code /f1}, {@code /m1}, etc. commands are
     * untouched - only how they're enabled/displayed changed, {@link #members()} still points at the real
     * {@link Shortcut}s a group's toggle turns on or off.
     */
    public enum Group {
        CATA_F0("F0", List.of(Shortcut.F0)),
        CATA_F1("F1 & M1", List.of(Shortcut.F1, Shortcut.M1)),
        CATA_F2("F2 & M2", List.of(Shortcut.F2, Shortcut.M2)),
        CATA_F3("F3 & M3", List.of(Shortcut.F3, Shortcut.M3)),
        CATA_F4("F4 & M4", List.of(Shortcut.F4, Shortcut.M4)),
        CATA_F5("F5 & M5", List.of(Shortcut.F5, Shortcut.M5)),
        CATA_F6("F6 & M6", List.of(Shortcut.F6, Shortcut.M6)),
        CATA_F7("F7 & M7", List.of(Shortcut.F7, Shortcut.M7)),
        KUUDRA_BASIC("Basic", List.of(Shortcut.KUUDRA_BASIC)),
        KUUDRA_HOT("Hot", List.of(Shortcut.KUUDRA_HOT)),
        KUUDRA_BURNING("Burning", List.of(Shortcut.KUUDRA_BURNING)),
        KUUDRA_FIERY("Fiery", List.of(Shortcut.KUUDRA_FIERY)),
        KUUDRA_INFERNAL("Infernal", List.of(Shortcut.KUUDRA_INFERNAL));

        // Deliberately NOT "/f0", "/f1 + /m1", etc: SettingTooltips.key() keeps a label's leading
        // character(s) verbatim (only §-formatting and a leading ▶/▼ are stripped), so a "/"-prefixed
        // button label would need its tooltip key written with that same leading slash baked in - easy to
        // typo and easy to silently break again later. Plain names avoid that trap; the actual /f1, /m1
        // words still show up in aliasList() next to the toggle.
        public final String label;
        private final List<Shortcut> members;

        Group(String label, List<Shortcut> members) {
            this.label = label;
            this.members = members;
        }

        /** The real {@link Shortcut}s this one toggle enables/disables together. */
        public List<Shortcut> members() {
            return members;
        }

        /** The actual chat command word(s) this toggle covers, e.g. {@code "/f1, /m1"}. */
        public String aliasList() {
            return members.stream().map(m -> "/" + m.literal).collect(Collectors.joining(", "));
        }

        /** What the tab shows next to this group's toggle: every member's real {@code /joininstance}
         *  argument, so a collision is easy to trace back to the exact instance id sent. */
        public String expandsTo() {
            return members.stream().map(m -> m.instanceId).collect(Collectors.joining(", "));
        }

        /** The one group whose toggle governs the given shortcut - every {@link Shortcut} belongs to
         *  exactly one group. */
        public static Group forShortcut(Shortcut s) {
            for (Group g : values()) {
                if (g.members.contains(s)) {
                    return g;
                }
            }
            return null;
        }
    }

    /** The words {@code /kuudra <tier>} accepts, mapped to the matching {@link Shortcut} - "normal" is
     *  accepted as an alias of "basic" since that's Hypixel's own name for the same tier. */
    private static Shortcut kuudraTierFor(String word) {
        String t = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "basic", "normal" -> Shortcut.KUUDRA_BASIC;
            case "hot" -> Shortcut.KUUDRA_HOT;
            case "burning" -> Shortcut.KUUDRA_BURNING;
            case "fiery" -> Shortcut.KUUDRA_FIERY;
            case "infernal" -> Shortcut.KUUDRA_INFERNAL;
            default -> null;
        };
    }

    private static final SuggestionProvider<FabricClientCommandSource> KUUDRA_TIER_SUGGEST = (ctx, b) -> {
        for (String tier : new String[]{"basic", "hot", "burning", "fiery", "infernal"}) {
            if (tier.startsWith(b.getRemaining().toLowerCase(Locale.ROOT))) {
                b.suggest(tier);
            }
        }
        return b.buildFuture();
    };

    private CommandShortcutsFeature() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} - see this wave's staging notes for the
     *  exact line, since that file is outside this package. Safe to call even if the config file doesn't
     *  exist yet ({@link CommandShortcutsConfig#getInstance()} creates all-on defaults). */
    public static void register() {
        CommandShortcutsConfig.getInstance();
        registerCustom();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            CommandShortcutsConfig cfg = CommandShortcutsConfig.getInstance();
            if (!cfg.isEnabledRaw()) {
                // Master switch off: register NOTHING, so every one of these words is fully free for
                // vanilla/another mod, not just a no-op in our own hands.
                return;
            }
            for (Shortcut s : Shortcut.values()) {
                if (!cfg.isOn(s)) {
                    // This one alias is off - skip only its literal, exactly like the master switch above,
                    // so a real collision with another mod's command can be resolved by disabling just this
                    // shortcut without losing the rest.
                    continue;
                }
                dispatcher.register(ClientCommands.literal(s.literal).executes(ctx -> exec(s)));
            }
            // "/kuudra <tier>" - the one multi-word form the brief also asked for. Registered whenever the
            // master switch is on; the per-tier toggle is still honoured inside exec() so turning off e.g.
            // "Kuudra Infernal" also blocks "/kuudra infernal", it just can't free the "kuudra" word itself
            // for another mod the way the bare aliases above can (there is no per-tier sub-literal to omit
            // here without losing tab completion for the tiers that ARE still on).
            dispatcher.register(ClientCommands.literal("kuudra")
                    .executes(ctx -> {
                        ModChat.send(FEATURE, ModChat.text("Usage: "), ModChat.value("/kuudra <basic|hot|burning|fiery|infernal>"));
                        return 1;
                    })
                    .then(ClientCommands.argument("tier", StringArgumentType.word())
                            .suggests(KUUDRA_TIER_SUGGEST)
                            .executes(ctx -> {
                                String tier = StringArgumentType.getString(ctx, "tier");
                                Shortcut s = kuudraTierFor(tier);
                                if (s == null) {
                                    ModChat.send(FEATURE, ModChat.bad("Unknown Kuudra tier "), ModChat.value(tier),
                                            ModChat.text(" - basic, hot, burning, fiery, infernal."));
                                    return 0;
                                }
                                return exec(s);
                            })));
        });
    }

    // ---- custom shortcuts (killer560, 2026-10-07: "There should be a custom section on command shortcuts.") -------
    //
    // How they register. Fabric fires ClientCommandRegistrationCallback once per join, into a fresh dispatcher
    // (fabric-command-api-v2 ClientPacketListenerMixin.onGameJoin, javap 3.0.5), and executes a typed command against
    // ClientCommandInternals.getActiveDispatcher(); a CommandSyntaxException of type "unknown command" makes it hand the
    // line to the server instead (executeCommand / isIgnoredException). Brigadier can add a root to a live dispatcher but
    // has no way to take one out, so every custom root is registered behind requires(isLive(name)): a removed, renamed
    // or switched-off shortcut stops parsing at once and the line falls through to the server exactly as if it had never
    // been registered, and a new or renamed one is added to the active dispatcher LIVE, a moment after he stops typing
    // (SYNC_QUIET_MS, so "w", "wa", "war" are not each registered on the way to "warp"). Tab completion is re-merged
    // the way CommandTreeRefresh does it; a removed name keeps its completion until the next join.

    /** Runs after every other mod's default-phase registration, so the dispatcher already holds their roots. */
    private static final net.minecraft.resources.Identifier CUSTOM_PHASE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "custom_shortcuts");
    private static final long SYNC_QUIET_MS = 750L;

    /** The dispatcher {@link #REGISTERED} describes; a join replaces it. */
    private static CommandDispatcher<FabricClientCommandSource> registeredIn;
    /** Custom roots this feature added to {@link #registeredIn} (live or no longer live). */
    private static final Set<String> REGISTERED = new LinkedHashSet<>();
    private static long lastEditMs;
    private static boolean syncPending;

    private static void registerCustom() {
        ClientCommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, CUSTOM_PHASE);
        ClientCommandRegistrationCallback.EVENT.register(CUSTOM_PHASE, (dispatcher, registryAccess) -> {
            registeredIn = dispatcher;
            REGISTERED.clear();
            syncInto(dispatcher);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (syncPending && System.currentTimeMillis() - lastEditMs >= SYNC_QUIET_MS) {
                    syncPending = false;
                    syncNow();
                }
            } catch (Exception e) {
                LOGGER.warn("[CommandShortcuts] custom sync failed", e);
            }
        });
    }

    /** The tab (or a profile load) changed the custom list: register what is new once he has stopped typing. */
    public static void customsChanged() {
        lastEditMs = System.currentTimeMillis();
        syncPending = true;
    }

    /** Adds every live custom shortcut the active dispatcher does not have yet, and re-merges tab completion. */
    public static void syncNow() {
        CommandDispatcher<FabricClientCommandSource> d = ClientCommandInternals.getActiveDispatcher();
        if (d == null) {
            return;
        }
        if (d != registeredIn) {
            registeredIn = d;
            REGISTERED.clear();
        }
        if (!syncInto(d)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            CommandDispatcher<FabricClientCommandSource> tree = (CommandDispatcher) mc.getConnection().getCommands();
            ClientCommandInternals.addCommands(tree, (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider());
        }
    }

    /** @return true if a root was added. */
    private static boolean syncInto(CommandDispatcher<FabricClientCommandSource> d) {
        CommandShortcutsConfig cfg = CommandShortcutsConfig.getInstance();
        boolean added = false;
        for (CommandShortcutsConfig.CustomShortcut c : cfg.customs()) {
            String name = c.name();
            if (!isLive(c) || REGISTERED.contains(name)) {
                continue;
            }
            String problem = nameProblem(name, c);
            if (problem != null) {
                LOGGER.warn("[CommandShortcuts] custom /{} not registered: {}", name, problem);
                continue;
            }
            d.register(ClientCommands.literal(name)
                    .requires(src -> isLive(CommandShortcutsConfig.getInstance().customNamed(name)))
                    .executes(ctx -> execCustom(name)));
            REGISTERED.add(name);
            added = true;
        }
        return added;
    }

    /** Whether typing this shortcut should run it right now (master switch, its own toggle, a name and a command). */
    public static boolean isLive(CommandShortcutsConfig.CustomShortcut c) {
        return c != null && c.isEnabled() && !c.name().isEmpty() && !commandOf(c).isEmpty()
                && CommandShortcutsConfig.getInstance().isEnabledRaw();
    }

    /** Custom roots in the active dispatcher - his own data, which the testkit's command sweep (306) leaves out. */
    public static Set<String> customRoots() {
        if (ClientCommandInternals.getActiveDispatcher() != registeredIn) {
            return Set.of();
        }
        return Set.copyOf(REGISTERED);
    }

    /** Whether {@code name} is in the active dispatcher as one of his shortcuts (the tab's "is it on" answer). */
    public static boolean isRegistered(String name) {
        return name != null && customRoots().contains(name);
    }

    /** The command to send, slash and outer spaces removed. */
    public static String commandOf(CommandShortcutsConfig.CustomShortcut c) {
        String s = c == null ? "" : c.command().trim();
        while (s.startsWith("/")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    /**
     * Why {@code name} cannot be a custom shortcut, or null when it can. Refused: anything that is not a legal literal,
     * a built-in shortcut's word (on or off), another custom row's name, and any root another mod, Fabric or this mod
     * registered in the client dispatcher, or the server sent in its command tree (vanilla's and Hypixel's). The last
     * two can only be checked in a world; outside one the join-time registration skips a name that turns out taken.
     * Case-insensitive, because Hypixel's commands are.
     */
    public static String nameProblem(String name, CommandShortcutsConfig.CustomShortcut self) {
        if (name == null || name.isEmpty()) {
            return "Type a name.";
        }
        if (!CommandShortcutsConfig.CUSTOM_NAME.matcher(name).matches()) {
            return "Use letters, digits and _ only, up to " + CommandShortcutsConfig.MAX_CUSTOM_NAME + ".";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("kuudra")) {
            return "/" + name + " is a built-in shortcut.";
        }
        for (Shortcut s : Shortcut.values()) {
            if (s.literal.equals(lower)) {
                return "/" + name + " is a built-in shortcut.";
            }
        }
        for (CommandShortcutsConfig.CustomShortcut c : CommandShortcutsConfig.getInstance().customs()) {
            if (c != self && c.name().equalsIgnoreCase(name)) {
                return "/" + name + " is already one of your shortcuts.";
            }
        }
        Set<String> ours = customRoots();
        Set<String> client = new java.util.HashSet<>();
        CommandDispatcher<FabricClientCommandSource> d = ClientCommandInternals.getActiveDispatcher();
        if (d != null) {
            for (CommandNode<FabricClientCommandSource> root : d.getRoot().getChildren()) {
                String n = root.getName();
                client.add(n.toLowerCase(Locale.ROOT));
                if (n.equalsIgnoreCase(name) && !ours.contains(n)) {
                    return "/" + name + " is already a client command (this mod or another) - pick another name.";
                }
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            for (CommandNode<?> root : mc.getConnection().getCommands().getRoot().getChildren()) {
                String n = root.getName();
                if (n.equalsIgnoreCase(name) && !ours.contains(n) && !client.contains(n.toLowerCase(Locale.ROOT))) {
                    return "/" + name + " is already a server command - pick another name.";
                }
            }
        }
        return null;
    }

    private static int execCustom(String name) {
        try {
            CommandShortcutsConfig.CustomShortcut c = CommandShortcutsConfig.getInstance().customNamed(name);
            if (!isLive(c)) {
                return 0;   // unreachable through the dispatcher: requires() already refused it
            }
            if (!SkyblockGate.allows()) {
                ModChat.send(FEATURE, ModChat.bad("Command Shortcuts are paused outside Skyblock / p3sim."));
                return 0;
            }
            return ServerCommands.toServer(commandOf(c)) ? 1 : 0;
        } catch (Exception e) {
            LOGGER.warn("[CommandShortcuts] custom /{} failed", name, e);
            return 0;
        }
    }

    /** Shared body every alias (and {@code /kuudra <tier>}) runs through - never throws. */
    private static int exec(Shortcut s) {
        try {
            CommandShortcutsConfig cfg = CommandShortcutsConfig.getInstance();
            if (!cfg.isEnabledRaw() || !cfg.isOn(s)) {
                // Only reachable via "/kuudra <tier>" for a tier whose own toggle was turned off after this
                // dispatcher was built (the bare alias command literal itself is never registered in that
                // case at all - see register()).
                ModChat.send(FEATURE, ModChat.bad(s.label + " is turned off"),
                        ModChat.text(" - turn it back on in the Command Shortcuts tab."));
                return 0;
            }
            if (!SkyblockGate.allows()) {
                ModChat.send(FEATURE, ModChat.bad("Command Shortcuts are paused outside Skyblock / p3sim."));
                return 0;
            }
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return 0;
            }
            client.player.connection.sendCommand("joininstance " + s.instanceId);
            return 1;
        } catch (Exception e) {
            LOGGER.warn("[CommandShortcuts] {} failed", s, e);
            return 0;
        }
    }
}
