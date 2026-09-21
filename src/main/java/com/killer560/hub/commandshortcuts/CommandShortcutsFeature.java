package com.killer560.hub.commandshortcuts;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-commandshortcuts");

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
