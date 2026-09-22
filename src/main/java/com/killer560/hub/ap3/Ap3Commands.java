package com.killer560.hub.ap3;

import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The whole {@code /ap3} command tree, plus the one place every action's real body lives.
 * <p>
 * Mirrors {@link com.killer560.hub.autoroutes.AutoRoutesCommands} on purpose - the spec says "an /ap3 command tree
 * mirroring Auto Routes'" with "every command gets an assignable keybind". So every action is an {@link Action}
 * constant with a stable id: {@link Ap3Keybinds} polls the id's key and calls exactly the same {@link Action#run()}
 * the command does, and {@link com.killer560.hub.gui.tab.Ap3Tab} lists the same constants for its buttons and
 * keybind rows. Adding a command means one constant here and one {@code .then(...)} in {@link #register()}.
 * <p>
 * <b>{@code /ap3 add <type> [modifiers]}</b> (2026-09-20 rework): the type is one word, everything after it is
 * modifiers parsed by {@link #parseSpec} - {@code w1 l1} (trigger box in blocks), {@code wait:1000}, {@code close},
 * {@code precise}, a class name for a leap ({@code bers}), a count for a leap counter. They apply to any node type
 * that has a use for them; a modifier the type cannot use is refused by name.
 * <p>
 * <b>One chat line per action.</b> Auto Routes shipped with the inner API ({@code deleteNode}, {@code setEditMode})
 * and the command both talking, and the inner one used 0-based numbers. Here the command layer owns ALL success
 * feedback and does its own range checks before calling into {@link Ap3Feature}, so the feature's own "no node #n"
 * paths never run from here. The core must stay quiet on success (see INTEGRATION.md); it may still explain a
 * refusal (not in the boss, section unknown), in which case this layer prints nothing extra.
 * <p>
 * <b>Node numbers are 1-based everywhere the player sees them</b> - {@code /ap3 list}, {@code /ap3 delete <n>},
 * the tab's rows and every "Added #n" line. Conversion to the 0-based index happens in exactly one place,
 * {@link #delete(int)}'s callers, and {@link #describeNumbered} is the only formatter.
 * <p>
 * Cheat build only: every action bails through {@link #ready()} on the legit jar, outside Skyblock/p3sim, and while
 * the master toggle is off. The one exception is {@link Action#STOP}: "the tab's own stop button must both work at
 * any time" (spec), so stopping only needs the cheat jar - never a toggle, never a server check.
 */
public final class Ap3Commands {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod/ap3");

    /** Chat prefix - "[AP3]" in the mod's orange. */
    public static final String FEATURE = "AP3";

    /** {@code wait:<ms>} bounds. Two minutes is longer than any boss section lasts. */
    public static final int MIN_WAIT_MS = 1;
    public static final int MAX_WAIT_MS = 120_000;

    /**
     * Every {@code /ap3} action. {@code id} is the key the config stores the keybind under and never changes once
     * shipped (so renamed commands keep their old id); {@code label} is what the settings tab shows (kept distinct
     * from Auto Routes' labels so the two tabs' tooltips can't cross-match - SettingTooltips keys on the label);
     * {@code command} is the chat syntax.
     */
    public enum Action {
        ADD_ALIGN("add_line", "Add Align Node", "/ap3 add align [precise] [w<n> l<n>]"),
        ADD_AXIS_ALIGN("add_axisline", "Add Axis Align Node", "/ap3 add axisalign [precise]"),
        /** "(No Turn)" because that IS the point of AP3's walk: "it does not actually make my character face that
         *  way, but it will move that way" (killer560). Also keeps the label distinct from Auto Routes' walk node. */
        ADD_WALK("add_walk", "Add Walk (No Turn) Node", "/ap3 add walk [w<n> l<n>]"),
        ADD_RUN("add_run", "Add Run (No Turn) Node", "/ap3 add run [w<n> l<n>]"),
        ADD_LEAP("add_leap", "Add Leap Node", "/ap3 add leap [mage|archer|bers|tank|healer|ign <name>]"),
        ADD_LEAP_COUNTER("add_leapdetector", "Add Leap Counter Node", "/ap3 add leapcounter [<count>]"),
        ADD_TERMINAL("add_terminal", "Add Terminal Node", "/ap3 add terminal"),
        ADD_STOP("add_stop", "Add Stop Node", "/ap3 add stop"),
        ADD_LOOK("add_look", "Add Look Node", "/ap3 add look"),
        ADD_BOOM("add_boom", "Add Boom Node", "/ap3 add boom"),
        ADD_STOPWATCH("add_stopwatch", "Add Stopwatch Node", "/ap3 add stopwatch [name]"),
        ADD_JUMP("add_jump", "Add Jump Node", "/ap3 add jump"),
        ADD_EDGE("add_edge", "Add Edge Jump Node", "/ap3 add edge"),
        ADD_BLOCK("add_block", "Add Block Node", "/ap3 add block"),
        LIST("list", "List Chain", "/ap3 list"),
        UNDO("undo", "Undo Last Node", "/ap3 undo"),
        /** The command takes an optional number; the key (and the bare command) delete the node you stand nearest. */
        DELETE_NEAREST("delete", "Delete Nearest Node", "/ap3 delete|remove [n]"),
        /** The key re-places the LAST node where you now stand and look - "I added it, stepped to a better spot"
         *  without a delete + add that would lose the node's modifiers. */
        REPLACE_LAST("replace_last", "Re-place Last Chain Node", "/ap3 replace <n> [pos|look]"),
        CLEAR("clear", "Clear Chain", "/ap3 clear"),
        RELOAD("reload", "Reload Chains File", "/ap3 reload"),
        /** "Stop AP3", not "Stop Chain": there is no running sequence to stop any more - this ends the node being
         *  performed, the held walk and everything queued, and releases every key. */
        STOP("stop", "Stop AP3", "/ap3 stop"),
        TEST_MODE("testmode", "Test Mode", "/ap3 testmode"),
        /** Freeze State (Ap3FreezeState): these three work without AP3 switched on and outside Skyblock - they are
         *  for placing nodes in singleplayer / p3sim. The two step keys refuse on Hypixel (type /rewind there). */
        FREEZE_STATE("freezestate", "Freeze State", "/freezestate"),
        REWIND_TICK("rewind_tick", "Rewind 1 Tick", "/rewind [ticks]"),
        FORWARD_TICK("forward_tick", "Forward 1 Tick", "/rewind forward [ticks]");

        public final String id;
        public final String label;
        public final String command;

        Action(String id, String label, String command) {
            this.id = id;
            this.label = label;
            this.command = command;
        }

        /** Runs this action exactly as its command would - keybinds and tab buttons go through here. */
        public void run() {
            Ap3Commands.run(this);
        }
    }

    private Ap3Commands() {
    }

    // ---- tab completion (killer560, 2026-09-20 in-game test: "Make sure tab will fill in things like walk and
    //      axisalign and whatnot and show them as options, same for the mods to them.") ----

    /** The node-type words {@code /ap3 add <type>} accepts, in the order they are offered. */
    private static final List<String> TYPE_WORDS = List.of("align", "axisalign", "walk", "run", "leap",
            "leapcounter", "terminal", "stop", "look", "boom", "stopwatch", "jump", "edge", "block");
    /** Modifiers offered after any {@code /ap3 add <type>}. */
    private static final List<String> COMMON_MODS = List.of("w1", "l1", "wait:", "close", "precise", "jump", "edge");

    private static final SuggestionProvider<FabricClientCommandSource> TYPE_SUGGEST =
            (ctx, b) -> suggestTokens(b, TYPE_WORDS);

    /** Context-aware modifier completion: the common modifiers for every type, plus the leap target words for a
     *  Leap and the counts for a Leap Counter. */
    private static final SuggestionProvider<FabricClientCommandSource> MOD_SUGGEST = (ctx, b) -> {
        List<String> opts = new ArrayList<>(COMMON_MODS);
        try {
            Ap3Node.Type t = Ap3Node.Type.parse(StringArgumentType.getString(ctx, "type"));
            if (t == Ap3Node.Type.LEAP) {
                opts.addAll(List.of("default", "class", "ign", "mage", "archer", "bers", "tank", "healer"));
            } else if (t == Ap3Node.Type.LEAP_COUNTER) {
                opts.addAll(List.of("1", "2", "3", "4"));
            }
        } catch (Exception ignored) {
            // no type yet - just offer the common modifiers
        }
        return suggestTokens(b, opts);
    };

    private static final SuggestionProvider<FabricClientCommandSource> CLASS_SUGGEST =
            (ctx, b) -> suggestTokens(b, List.of("mage", "archer", "bers", "tank", "healer"));

    /**
     * Suggests {@code options} for the LAST whitespace-separated token of the argument's input, so completion works
     * inside a greedy modifier string ("walk w1 cl" -> "close") as well as for a single word.
     */
    private static CompletableFuture<Suggestions> suggestTokens(SuggestionsBuilder b, List<String> options) {
        String remaining = b.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        String token = lastSpace < 0 ? remaining : remaining.substring(lastSpace + 1);
        SuggestionsBuilder offset = b.createOffset(b.getStart() + lastSpace + 1);
        String low = token.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(low)) {
                offset.suggest(o);
            }
        }
        return offset.buildFuture();
    }

    // ---- registration ----

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see INTEGRATION.md). */
    public static void register() {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Freeze State. The typed /rewind is the only way to step on Hypixel (the keys refuse there) and every
            // use there prints the ban warning first - see Ap3FreezeState.
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                dispatcher.register(ClientCommands.literal("freezestate").executes(context -> {
                    Ap3FreezeState.toggle();
                    return 1;
                }));
                dispatcher.register(ClientCommands.literal("rewind")
                        .executes(context -> {
                            Ap3FreezeState.step(-1, 1, true);
                            return 1;
                        })
                        .then(ClientCommands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, Ap3Config.MAX_REWIND_TICKS))
                                .executes(context -> {
                                    Ap3FreezeState.step(-1, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "ticks"), true);
                                    return 1;
                                }))
                        .then(ClientCommands.literal("forward")
                                .executes(context -> {
                                    Ap3FreezeState.step(1, 1, true);
                                    return 1;
                                })
                                .then(ClientCommands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, Ap3Config.MAX_REWIND_TICKS))
                                        .executes(context -> {
                                            Ap3FreezeState.step(1, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "ticks"), true);
                                            return 1;
                                        }))));
            });
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ap3")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        // "/ap3 add <type> [modifiers...]" - one word for the type, the rest free-form modifiers.
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("type", StringArgumentType.word())
                                        .suggests(TYPE_SUGGEST)
                                        .executes(context -> {
                                            addCommand(StringArgumentType.getString(context, "type"), "");
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("mods", StringArgumentType.greedyString())
                                                .suggests(MOD_SUGGEST)
                                                .executes(context -> {
                                                    addCommand(StringArgumentType.getString(context, "type"),
                                                            StringArgumentType.getString(context, "mods"));
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("list").executes(context -> exec(Action.LIST)))
                        .then(ClientCommands.literal("undo").executes(context -> exec(Action.UNDO)))
                        .then(ClientCommands.literal("clear").executes(context -> exec(Action.CLEAR)))
                        .then(ClientCommands.literal("reload").executes(context -> exec(Action.RELOAD)))
                        .then(ClientCommands.literal("stop").executes(context -> exec(Action.STOP)))
                        .then(ClientCommands.literal("testmode").executes(context -> exec(Action.TEST_MODE)))
                        // "/ap3 delete [n]" and "/ap3 remove [n]" are the same command (killer560: "Both delete and
                        // remove must exist and do the same thing"). <n> is the 1-based number "/ap3 list" prints;
                        // with no number the node you stand clearly nearest goes. Converted to 0-based exactly here.
                        .then(deleteBranch("delete"))
                        .then(deleteBranch("remove"))
                        // ---- in-place editing (killer560: "make it easier to edit them"). Every <n> and <m> is
                        // the 1-based number "/ap3 list" and the world labels show; converted to 0-based right here.
                        // "/ap3 move <n> up|down|to <m>"
                        .then(ClientCommands.literal("move")
                                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                                        .then(ClientCommands.literal("up").executes(context -> {
                                            int n = IntegerArgumentType.getInteger(context, "n");
                                            return move(n - 1, n - 2) ? 1 : 0;
                                        }))
                                        .then(ClientCommands.literal("down").executes(context -> {
                                            int n = IntegerArgumentType.getInteger(context, "n");
                                            return move(n - 1, n) ? 1 : 0;
                                        }))
                                        .then(ClientCommands.literal("to")
                                                .then(ClientCommands.argument("m", IntegerArgumentType.integer(1))
                                                        .executes(context -> {
                                                            int n = IntegerArgumentType.getInteger(context, "n");
                                                            int m = IntegerArgumentType.getInteger(context, "m");
                                                            return move(n - 1, m - 1) ? 1 : 0;
                                                        })))))
                        // "/ap3 replace <n>" = position AND look; "pos" / "look" for just one half.
                        .then(ClientCommands.literal("replace")
                                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                                        .executes(context -> replace(IntegerArgumentType.getInteger(context, "n") - 1, true, true) ? 1 : 0)
                                        .then(ClientCommands.literal("pos").executes(context ->
                                                replace(IntegerArgumentType.getInteger(context, "n") - 1, true, false) ? 1 : 0))
                                        .then(ClientCommands.literal("look").executes(context ->
                                                replace(IntegerArgumentType.getInteger(context, "n") - 1, false, true) ? 1 : 0))))
                        // "/ap3 set <n> length|width|wait|close|precise|count|leap|colour ..." - the per-node fields.
                        .then(ClientCommands.literal("set")
                                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                                        .then(ClientCommands.literal("length")
                                                .then(ClientCommands.argument("v", DoubleArgumentType.doubleArg(Ap3Node.MIN_LENGTH, Ap3Node.MAX_LENGTH))
                                                        .executes(context -> setLength(
                                                                IntegerArgumentType.getInteger(context, "n") - 1,
                                                                DoubleArgumentType.getDouble(context, "v")) ? 1 : 0)))
                                        .then(ClientCommands.literal("width")
                                                .then(ClientCommands.argument("v", DoubleArgumentType.doubleArg(Ap3Node.MIN_WIDTH, Ap3Node.MAX_WIDTH))
                                                        .executes(context -> setWidth(
                                                                IntegerArgumentType.getInteger(context, "n") - 1,
                                                                DoubleArgumentType.getDouble(context, "v")) ? 1 : 0)))
                                        .then(ClientCommands.literal("wait")
                                                .then(ClientCommands.argument("ms", IntegerArgumentType.integer(0, MAX_WAIT_MS))
                                                        .executes(context -> setWaitMs(
                                                                IntegerArgumentType.getInteger(context, "n") - 1,
                                                                IntegerArgumentType.getInteger(context, "ms")) ? 1 : 0)))
                                        .then(ClientCommands.literal("close")
                                                .then(ClientCommands.literal("on").executes(context -> setClose(
                                                        IntegerArgumentType.getInteger(context, "n") - 1, true) ? 1 : 0))
                                                .then(ClientCommands.literal("off").executes(context -> setClose(
                                                        IntegerArgumentType.getInteger(context, "n") - 1, false) ? 1 : 0)))
                                        .then(ClientCommands.literal("precise")
                                                .then(ClientCommands.literal("on").executes(context -> setPrecise(
                                                        IntegerArgumentType.getInteger(context, "n") - 1, true) ? 1 : 0))
                                                .then(ClientCommands.literal("off").executes(context -> setPrecise(
                                                        IntegerArgumentType.getInteger(context, "n") - 1, false) ? 1 : 0)))
                                        .then(ClientCommands.literal("count")
                                                .then(ClientCommands.argument("k", IntegerArgumentType.integer(1, Ap3Node.MAX_LEAP_COUNT))
                                                        .executes(context -> setLeapCount(
                                                                IntegerArgumentType.getInteger(context, "n") - 1,
                                                                IntegerArgumentType.getInteger(context, "k")) ? 1 : 0)))
                                        .then(ClientCommands.literal("leap")
                                                .then(ClientCommands.literal("default").executes(context -> setLeap(
                                                        IntegerArgumentType.getInteger(context, "n") - 1,
                                                        Ap3Node.LeapMode.DEFAULT, null, null) ? 1 : 0))
                                                .then(ClientCommands.literal("class")
                                                        .then(ClientCommands.argument("c", StringArgumentType.word())
                                                                .suggests(CLASS_SUGGEST)
                                                                .executes(context -> setLeap(
                                                                        IntegerArgumentType.getInteger(context, "n") - 1,
                                                                        Ap3Node.LeapMode.CLASS,
                                                                        parseClass(StringArgumentType.getString(context, "c")), null) ? 1 : 0)))
                                                .then(ClientCommands.literal("ign")
                                                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                                                .executes(context -> setLeap(
                                                                        IntegerArgumentType.getInteger(context, "n") - 1,
                                                                        Ap3Node.LeapMode.IGN, null,
                                                                        StringArgumentType.getString(context, "name")) ? 1 : 0))))
                                        .then(ClientCommands.literal("colour")
                                                .then(ClientCommands.literal("reset").executes(context -> setColour(
                                                        IntegerArgumentType.getInteger(context, "n") - 1, null) ? 1 : 0))
                                                .then(ClientCommands.argument("hex", StringArgumentType.word())
                                                        .executes(context -> setColour(
                                                                IntegerArgumentType.getInteger(context, "n") - 1,
                                                                StringArgumentType.getString(context, "hex")) ? 1 : 0)))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> deleteBranch(String word) {
        return ClientCommands.literal(word)
                .executes(context -> exec(Action.DELETE_NEAREST))
                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            int n = IntegerArgumentType.getInteger(context, "n");
                            return guarded(() -> delete(n - 1)) ? 1 : 0;
                        }));
    }

    /** The non-keybind commands, for {@link #help()} - they all take a node number, which a key can't carry. */
    private static final String[][] EDIT_HELP = {
            {"/ap3 move <n> up|down|to <m>", "Reorder a node"},
            {"/ap3 replace <n> [pos|look]", "Re-place node n where you stand / look"},
            {"/ap3 set <n> width|length <v>", "Trigger box in blocks (any node)"},
            {"/ap3 set <n> wait <ms>", "Wait after the node, 0 = none (any node)"},
            {"/ap3 set <n> close on|off", "Fire only on left click / after a GUI closes (any node)"},
            {"/ap3 set <n> precise on|off", "Align: exact coordinates instead of the block centre"},
            {"/ap3 set <n> count <k>", "Leap Counter: teammates that must leap"},
            {"/ap3 set <n> leap default|class <c>|ign <name>", "Leap node target"},
            {"/ap3 set <n> colour <hex>|reset", "Per-node marker colour"},
    };

    private static int exec(Action action) {
        run(action);
        return 1;
    }

    // ---- the single code path commands, keybinds and tab buttons all share ----

    /** Runs {@code action} with the shared gating ({@link #ready()}) and exception fence. Never throws. */
    public static void run(Action action) {
        if (action == Action.FREEZE_STATE || action == Action.REWIND_TICK || action == Action.FORWARD_TICK) {
            if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                return;
            }
            try {
                switch (action) {
                    case FREEZE_STATE -> Ap3FreezeState.toggle();
                    case REWIND_TICK -> Ap3FreezeState.step(-1, 1, false);
                    default -> Ap3FreezeState.step(1, 1, false);
                }
            } catch (Exception e) {
                LOGGER.warn("[AP3] freeze state action failed", e);
            }
            return;
        }
        if (action == Action.STOP) {
            // Stopping must work at ANY time - even with the toggle off or outside Skyblock, if something is somehow
            // still moving the player, this is the panic button. Only the cheat-jar check applies.
            if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                return;
            }
            try {
                stopChain();
            } catch (Exception e) {
                LOGGER.warn("[AP3] stop failed", e);
            }
            return;
        }
        guarded(() -> dispatch(action));
    }

    /** @return false if the action was refused by {@link #ready()} or threw. */
    private static boolean guarded(Runnable body) {
        if (!ready()) {
            return false;
        }
        try {
            body.run();
            return true;
        } catch (Exception e) {
            // A bad state inside the engine must never surface as a command exception in chat (Brigadier would
            // print a stack trace to the player) or kill a keybind tick - report it and carry on.
            LOGGER.warn("[AP3] action failed", e);
            ModChat.send(FEATURE, ModChat.bad("That failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            return false;
        }
    }

    private static void dispatch(Action action) {
        switch (action) {
            case ADD_ALIGN -> add(Ap3Node.Type.ALIGN);
            case ADD_AXIS_ALIGN -> add(Ap3Node.Type.AXIS_ALIGN);
            case ADD_WALK -> add(Ap3Node.Type.WALK);
            case ADD_RUN -> add(Ap3Node.Type.RUN);
            case ADD_LEAP -> add(Ap3Node.Type.LEAP);
            case ADD_LEAP_COUNTER -> add(Ap3Node.Type.LEAP_COUNTER);
            case ADD_TERMINAL -> add(Ap3Node.Type.TERMINAL);
            case ADD_STOP -> add(Ap3Node.Type.STOP);
            case ADD_LOOK -> add(Ap3Node.Type.LOOK);
            case ADD_BOOM -> add(Ap3Node.Type.BOOM);
            case ADD_STOPWATCH -> add(Ap3Node.Type.STOPWATCH);
            case ADD_JUMP -> add(Ap3Node.Type.JUMP);
            case ADD_EDGE -> add(Ap3Node.Type.EDGE);
            case ADD_BLOCK -> add(Ap3Node.Type.BLOCK);
            case LIST -> list();
            case UNDO -> undo();
            case DELETE_NEAREST -> deleteNearest();
            case REPLACE_LAST -> replaceLast();
            case CLEAR -> clear();
            case RELOAD -> reload();
            case STOP -> stopChain();
            case TEST_MODE -> toggleTestMode();
            case FREEZE_STATE, REWIND_TICK, FORWARD_TICK -> {
                // handled in run() before the AP3 readiness check
            }
        }
    }

    /**
     * Shared gate. Cheat jar only (the legit jar keeps the command so a shared config can't crash it, it just says
     * so), Skyblock/p3sim only like every other feature command, and the master toggle must be on - a chain command
     * silently doing nothing while the feature is off is the first thing a tester reports as "broken". Being in the
     * boss is NOT checked here: the core decides what needs it (placing a node needs an area; listing / reloading /
     * clearing a chain you placed earlier shouldn't need you to be standing in the boss room).
     */
    private static boolean ready() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ModChat.send(FEATURE, ModChat.bad("Not available in this build."));
            return false;
        }
        if (!com.killer560.hub.util.SkyblockGate.allows() && !Ap3Feature.isForceDungeon()) {
            ModChat.send(FEATURE, ModChat.bad("Mods are paused outside Skyblock / p3sim (Force Dungeon in the AP3 tab lets AP3 test anywhere)."));
            return false;
        }
        if (!Ap3Config.getInstance().isEnabledRaw()) {
            ModChat.send(FEATURE, ModChat.bad("AP3 is OFF - turn it on in the New tab first."));
            return false;
        }
        return Minecraft.getInstance().player != null;
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands (F7/M7 boss only - any phase, one chain per area P1 / P2 / S1-S5 / P4 / P5):"));
        ModChat.send(FEATURE, ModChat.dim("There is no start and no order: every node is armed, walking INTO its box fires it. Same tick: stop > align > look > walk > boom > leap, one per tick."));
        for (Action a : Action.values()) {
            ModChat.send(FEATURE, ModChat.value(a.command), ModChat.dim(" - " + a.label));
        }
        for (String[] line : EDIT_HELP) {
            ModChat.send(FEATURE, ModChat.value(line[0]), ModChat.dim(" - " + line[1]));
        }
        ModChat.send(FEATURE, ModChat.dim("Modifiers after any /ap3 add: w<n> l<n> (trigger box in blocks), wait:<ms>, close, precise."));
        ModChat.send(FEATURE, ModChat.dim("<n> is the node's number from /ap3 list and its world label (first node = 1)."));
    }

    // ---- actions ----

    private static void add(Ap3Node.Type type) {
        // The core owns where the node lands (block-centre snap, current yaw for walk/run, held class for leap...)
        // AND announces the result itself, refusal or success. Deliberately silent here: echoing a second,
        // differently-worded line is exactly the double-chat bug Auto Routes shipped with (2026-09-16).
        Ap3Feature.addNode(type);
    }

    /** {@code /ap3 add <type> [modifiers]} from chat. */
    private static void addCommand(String typeWord, String mods) {
        guarded(() -> {
            String key = typeWord.trim().toLowerCase(Locale.ROOT);
            switch (key) {
                case "wait", "delay" -> {
                    ModChat.send(FEATURE, ModChat.bad("Wait is a modifier now"), ModChat.text(" - e.g. "),
                            ModChat.value("/ap3 add walk wait:1000"), ModChat.text(" waits 1000 ms after that node."));
                    return;
                }
                case "breaker", "db", "dungeonbreaker" -> {
                    ModChat.send(FEATURE, ModChat.bad("The Breaker node is gone"), ModChat.text(" - Breaker Aura covers it."));
                    return;
                }
                case "detector" -> {
                    ModChat.send(FEATURE, ModChat.bad("Detector is gone"), ModChat.text(" - "),
                            ModChat.value("/ap3 add leapcounter <n>"), ModChat.text(" waits for n teammates to leap to you."));
                    return;
                }
                default -> {
                }
            }
            Ap3Node.Type type = Ap3Node.Type.parse(key);
            if (type == null) {
                ModChat.send(FEATURE, ModChat.bad("Unknown node type "), ModChat.value(typeWord),
                        ModChat.text(" - align, axisalign, walk, run, leap, leapcounter, terminal, stop, look, boom, stopwatch."));
                return;
            }
            Ap3Feature.NodeSpec spec = parseSpec(type, mods);
            if (spec == null) {
                return; // parseSpec said what was wrong
            }
            Ap3Feature.addNode(type, spec);
        });
    }

    /**
     * The modifiers after {@code /ap3 add <type>}: {@code w<n>} / {@code l<n>} (or {@code width:<n>} /
     * {@code length:<n>} / {@code <w>x<l>}), {@code wait:<ms>}, {@code close}, {@code precise}, a class word or
     * {@code ign <name>} for a leap, a bare count for a leap counter. Returns null (after saying why) on anything it
     * does not understand, so a typo never silently adds a node with the wrong shape.
     */
    static Ap3Feature.NodeSpec parseSpec(Ap3Node.Type type, String mods) {
        Ap3Feature.NodeSpec spec = new Ap3Feature.NodeSpec();
        if (mods == null || mods.isBlank()) {
            return spec;
        }
        String[] tokens = mods.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String raw = tokens[i];
            String t = raw.toLowerCase(Locale.ROOT);
            try {
                if (t.equals("close")) {
                    spec.close = true;
                } else if (t.equals("jump") && type != Ap3Node.Type.JUMP && type != Ap3Node.Type.EDGE) {
                    spec.jumpMod = Ap3Node.JumpMod.JUMP;
                } else if ((t.equals("edge") || t.equals("edgejump")) && type != Ap3Node.Type.JUMP && type != Ap3Node.Type.EDGE) {
                    spec.jumpMod = Ap3Node.JumpMod.EDGE;
                } else if (t.equals("precise") || t.equals("exact")) {
                    spec.precise = true;
                } else if (t.startsWith("wait:") || t.startsWith("wait=")) {
                    spec.waitMs = clampWait(Integer.parseInt(t.substring(5)));
                } else if (t.startsWith("width:") || t.startsWith("width=")) {
                    spec.width = Double.parseDouble(t.substring(6));
                } else if (t.startsWith("length:") || t.startsWith("length=")) {
                    spec.length = Double.parseDouble(t.substring(7));
                } else if (t.startsWith("len:")) {
                    spec.length = Double.parseDouble(t.substring(4));
                } else if (t.matches("w(\\d+(\\.\\d+)?|\\.\\d+)")) { // w1, w2.5, w.5
                    spec.width = Double.parseDouble(t.substring(1));
                } else if (t.matches("l(\\d+(\\.\\d+)?|\\.\\d+)")) { // l1, l2.5, l.5
                    spec.length = Double.parseDouble(t.substring(1));
                } else if (t.matches("(\\d+(\\.\\d+)?|\\.\\d+)x(\\d+(\\.\\d+)?|\\.\\d+)")) { // 2x3, .5x.5
                    String[] wl = t.split("x");
                    spec.width = Double.parseDouble(wl[0]);
                    spec.length = Double.parseDouble(wl[1]);
                } else if (type == Ap3Node.Type.LEAP && (t.equals("default") || t.equals("fastleap") || t.equals("auto"))) {
                    spec.leapMode = Ap3Node.LeapMode.DEFAULT;
                } else if (type == Ap3Node.Type.LEAP && t.startsWith("ign:")) {
                    spec.leapMode = Ap3Node.LeapMode.IGN;
                    spec.leapIgn = raw.substring(4);
                } else if (type == Ap3Node.Type.LEAP && (t.equals("ign") || t.equals("name") || t.equals("player")) && i + 1 < tokens.length) {
                    spec.leapMode = Ap3Node.LeapMode.IGN;
                    spec.leapIgn = tokens[++i];
                } else if (type == Ap3Node.Type.LEAP && t.equals("class") && i + 1 < tokens.length) {
                    DungeonClass c = parseClass(tokens[++i]);
                    if (c == null) {
                        ModChat.send(FEATURE, ModChat.bad("Unknown class "), ModChat.value(tokens[i]),
                                ModChat.text(" - mage / archer / bers / tank / healer."));
                        return null;
                    }
                    spec.leapMode = Ap3Node.LeapMode.CLASS;
                    spec.leapClass = c;
                } else if (type == Ap3Node.Type.LEAP && parseClass(t) != null) {
                    // "/ap3 add leap bers" leaps to the berserker (and the other classes the same way).
                    spec.leapMode = Ap3Node.LeapMode.CLASS;
                    spec.leapClass = parseClass(t);
                } else if (type == Ap3Node.Type.LEAP_COUNTER && t.matches("x?\\d+")) {
                    spec.leapCount = Integer.parseInt(t.startsWith("x") ? t.substring(1) : t);
                } else if (type == Ap3Node.Type.LEAP_COUNTER && t.startsWith("count:")) {
                    spec.leapCount = Integer.parseInt(t.substring(6));
                } else if (type == Ap3Node.Type.STOPWATCH && raw.matches("[A-Za-z0-9_\\-]{1,16}") && spec.name == null) {
                    // "/ap3 add stopwatch s3" - the name the time is reported under.
                    spec.name = raw;
                } else {
                    ModChat.send(FEATURE, ModChat.bad("Unknown modifier "), ModChat.value(raw),
                            ModChat.text(" for " + type.label() + " - w<n> l<n>, wait:<ms>, close, precise"
                                    + (type == Ap3Node.Type.LEAP ? ", a class, ign <name>" : "")
                                    + (type == Ap3Node.Type.LEAP_COUNTER ? ", a count" : "")
                                    + (type == Ap3Node.Type.STOPWATCH ? ", a name (one word)" : "") + "."));
                    return null;
                }
            } catch (NumberFormatException e) {
                ModChat.send(FEATURE, ModChat.bad("Bad number in "), ModChat.value(raw), ModChat.text("."));
                return null;
            }
        }
        if (spec.width != null && (spec.width < Ap3Node.MIN_WIDTH || spec.width > Ap3Node.MAX_WIDTH)
                || spec.length != null && (spec.length < Ap3Node.MIN_LENGTH || spec.length > Ap3Node.MAX_LENGTH)) {
            ModChat.send(FEATURE, ModChat.bad("Box size out of range"), ModChat.text(String.format(Locale.US,
                    " - %.1f to %.0f blocks.", Ap3Node.MIN_WIDTH, Ap3Node.MAX_WIDTH)));
            return null;
        }
        return spec;
    }

    private static int clampWait(int ms) {
        return Math.max(MIN_WAIT_MS, Math.min(MAX_WAIT_MS, ms));
    }

    /** The class words a leap accepts: the full names, {@code DungeonClass.byName}'s spellings, and the short
     *  forms people actually type ({@code bers}, {@code arch}, {@code m}/{@code a}/{@code b}/{@code t}/{@code h}). */
    public static DungeonClass parseClass(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "mage", "m" -> DungeonClass.MAGE;
            case "archer", "arch", "a" -> DungeonClass.ARCHER;
            case "berserker", "berserk", "bers", "berz", "b" -> DungeonClass.BERSERKER;
            case "tank", "t" -> DungeonClass.TANK;
            case "healer", "heal", "h" -> DungeonClass.HEALER;
            default -> DungeonClass.byName(s);
        };
    }

    private static void toggleTestMode() {
        boolean on = !Ap3Executor.isTestMode();
        Ap3Executor.setTestMode(on);
        if (on) {
            ModChat.send(FEATURE, ModChat.text("Test mode "), ModChat.good("ON"),
                    ModChat.dim(" - nodes still fire when you walk into them, as a dry run: terminals, leap counters and close gates are skipped, a failed leap is skipped, and ANY key or button stops everything. /ap3 testmode again to leave."));
        } else {
            if (Ap3Executor.isRunning()) {
                Ap3Executor.stop("test mode off");
            }
            ModChat.send(FEATURE, ModChat.text("Test mode "), ModChat.bad("OFF"), ModChat.text("."));
        }
    }

    private static void list() {
        List<Ap3Node> nodes = Ap3Feature.currentChainNodes();
        if (nodes.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No chain for "), ModChat.value(areaName()),
                    ModChat.dim(" - /ap3 add <type> while standing where the node goes."));
            return;
        }
        ModChat.send(FEATURE, ModChat.value(areaName()), ModChat.text(": " + nodes.size() + " node" + (nodes.size() == 1 ? "" : "s")),
                Ap3Executor.isRunning() ? ModChat.good(" (running)") : ModChat.text(""),
                Ap3Executor.isTestMode() ? ModChat.good(" (test mode)") : ModChat.text(""));
        for (int i = 0; i < nodes.size(); i++) {
            ModChat.send(FEATURE, ModChat.dim("#" + (i + 1) + " "), ModChat.value(describe(nodes.get(i))));
        }
        ModChat.send(FEATURE, ModChat.dim("/ap3 delete <n> removes one by its number; /ap3 delete alone takes the nearest; /ap3 undo the last added."));
    }

    private static void undo() {
        Ap3Feature.undoLastAdded();
    }

    private static void deleteNearest() {
        Ap3Feature.deleteNearestNode();
    }

    /**
     * {@code index} is 0-based here; the command and the tab both convert from the 1-based display number. The
     * range check lives HERE so the core's own "no node" message (whatever numbering it uses) never reaches chat
     * from a command - one line, with the number the player typed.
     */
    public static void delete(int index) {
        List<Ap3Node> nodes = Ap3Feature.currentChainNodes();
        if (index < 0 || index >= nodes.size()) {
            ModChat.send(FEATURE, ModChat.bad("No node #" + (index + 1)),
                    ModChat.text(nodes.isEmpty() ? " - the chain is empty." : " - there are " + nodes.size() + "."));
            return;
        }
        if (Ap3Executor.isRunning()) {
            // Yanking a node out from under the executor would leave it performing / queueing a node that no
            // longer exists - stop it first, with a reason it can echo.
            Ap3Executor.stop("node deleted");
        }
        Ap3Feature.deleteNode(index);
    }

    /** The keybind half of {@code /ap3 replace}: the LAST node, position and look. */
    private static void replaceLast() {
        int size = Ap3Feature.currentChainNodes().size();
        if (size == 0) {
            ModChat.send(FEATURE, ModChat.text("No nodes to re-place in "), ModChat.value(areaName()), ModChat.text("."));
            return;
        }
        Ap3Feature.replaceNode(size - 1, true, true);
    }

    // ---- in-place editing: 0-BASED indices, shared by the command tree and the tab's edit page ----
    // Same contract as delete(): the range check lives here so the one chat line carries the number the player
    // typed, and each goes through guarded() so a tab button gets the same gating + exception fence as a command.

    /** {@code /ap3 move <n> ...} - the core does the shifting and prints the "Moved" line. */
    public static boolean move(int index, int newIndex) {
        return guarded(() -> {
            if (!checkIndex(index)) {
                return;
            }
            Ap3Feature.moveNode(index, newIndex);
        });
    }

    /** {@code /ap3 replace <n> [pos|look]}. */
    public static boolean replace(int index, boolean position, boolean look) {
        return guarded(() -> {
            if (!checkIndex(index)) {
                return;
            }
            Ap3Feature.replaceNode(index, position, look);
        });
    }

    public static boolean setLength(int index, double v) {
        return edit(index, "length " + fmt(v), n -> true, "", n -> n.setLength(v));
    }

    public static boolean setWidth(int index, double v) {
        return edit(index, "width " + fmt(v), n -> true, "", n -> n.setWidth(v));
    }

    public static boolean setWaitMs(int index, int ms) {
        return edit(index, ms == 0 ? "no wait" : "wait " + ms + " ms", n -> true, "", n -> n.setWaitAfterMs(ms));
    }

    public static boolean setClose(int index, boolean on) {
        return edit(index, "close " + (on ? "on" : "off"), n -> true, "", n -> n.closeGate = on);
    }

    public static boolean setPrecise(int index, boolean on) {
        return edit(index, "precise " + (on ? "on" : "off"), n -> n.type().isAlign(),
                "precise applies to Align and Axis Align nodes", n -> n.precise = on);
    }

    public static boolean setLeapCount(int index, int k) {
        return edit(index, "count " + k, n -> n.type() == Ap3Node.Type.LEAP_COUNTER,
                "count applies to Leap Counter nodes", n -> n.setLeapCount(k));
    }

    /** {@code /ap3 set <n> leap default | class <c> | ign <name>} - the same three modes a leap node is added with. */
    public static boolean setLeap(int index, Ap3Node.LeapMode mode, DungeonClass clazz, String ign) {
        if (mode == Ap3Node.LeapMode.CLASS && clazz == null) {
            ModChat.send(FEATURE, ModChat.bad("Unknown class"), ModChat.text(" - mage / archer / bers / tank / healer."));
            return false;
        }
        String cleanIgn = ign == null || ign.isBlank() ? null : ign.trim();
        if (mode == Ap3Node.LeapMode.IGN && cleanIgn == null) {
            ModChat.send(FEATURE, ModChat.bad("Leap IGN missing."));
            return false;
        }
        String what = switch (mode) {
            case CLASS -> "leap -> " + clazz.displayName();
            case IGN -> "leap -> " + cleanIgn;
            default -> "leap -> Fast Leap target";
        };
        return edit(index, what, n -> n.type() == Ap3Node.Type.LEAP, "the leap target applies to Leap nodes", n -> {
            n.leapMode = mode;
            n.leapClass = mode == Ap3Node.LeapMode.CLASS ? clazz : null;
            n.leapIgn = mode == Ap3Node.LeapMode.IGN ? cleanIgn : null;
        });
    }

    /** {@code /ap3 set <n> colour <hex>|reset}: "RRGGBB" or "AARRGGBB", '#' optional; null clears the override. */
    public static boolean setColour(int index, String hex) {
        Integer argb = null;
        if (hex != null) {
            String t = hex.trim();
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            try {
                if (t.length() != 6 && t.length() != 8) {
                    throw new NumberFormatException();
                }
                long v = Long.parseLong(t, 16);
                argb = (int) (t.length() == 6 ? v | 0xFF000000L : v);
            } catch (NumberFormatException e) {
                ModChat.send(FEATURE, ModChat.bad("Bad colour "), ModChat.value(hex),
                        ModChat.text(" - use RRGGBB hex (e.g. FFA040) or \"reset\"."));
                return false;
            }
        }
        Integer picked = argb;
        return edit(index, picked == null ? "colour reset" : String.format(Locale.ROOT, "colour #%06X", picked & 0xFFFFFF),
                n -> true, "", n -> n.colour = picked);
    }

    /** One field edit: index check, "does this type have that field" check, then the core applies + saves + prints. */
    private static boolean edit(int index, String what, java.util.function.Predicate<Ap3Node> applies, String why,
                                java.util.function.Consumer<Ap3Node> change) {
        return guarded(() -> {
            if (!checkIndex(index)) {
                return;
            }
            Ap3Node node = Ap3Feature.currentChainNodes().get(index);
            if (!applies.test(node)) {
                ModChat.send(FEATURE, ModChat.bad("#" + (index + 1) + " is a " + typeName(node.type()) + " node"),
                        ModChat.text(" - " + why + "."));
                return;
            }
            Ap3Feature.editNode(index, what, change);
        });
    }

    /** Range check with the player's own number in the message; 0-based in, 1-based out. */
    private static boolean checkIndex(int index) {
        List<Ap3Node> nodes = Ap3Feature.currentChainNodes();
        if (index < 0 || index >= nodes.size()) {
            ModChat.send(FEATURE, ModChat.bad("No node #" + (index + 1)),
                    ModChat.text(nodes.isEmpty() ? " - the chain is empty." : " - there are " + nodes.size() + "."));
            return false;
        }
        return true;
    }

    private static void clear() {
        int count = Ap3Feature.currentChainNodes().size();
        if (count == 0) {
            ModChat.send(FEATURE, ModChat.text("No chain for "), ModChat.value(areaName()), ModChat.text("."));
            return;
        }
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain cleared");
        }
        Ap3Feature.clearCurrentChain();
    }

    /**
     * Same job as {@code /ar reload}: re-reads the one chains file so a friend's copy dropped into the folder, or
     * a Notepad edit, works without a restart. Syntax is checked here first so a broken file gets NAMED in chat
     * instead of quietly loading as zero chains. Only syntax - the store decides what is a valid chain.
     */
    private static void reload() {
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chains reloaded");
        }
        Path file = chainsFile();
        String parseError = null;
        boolean exists = Files.isRegularFile(file);
        if (exists) {
            try {
                JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                parseError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        Ap3Store.getInstance().reload();
        int loaded = Ap3Store.getInstance().chains().size();
        if (parseError != null) {
            ModChat.send(FEATURE, ModChat.bad("Failed to parse "), ModChat.value(file.getFileName().toString()),
                    ModChat.bad(": " + parseError), ModChat.dim(" - fix the file (or paste a good copy back in) and /ap3 reload again."));
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Reloaded "), ModChat.value(loaded + " chain" + (loaded == 1 ? "" : "s")),
                ModChat.text(" from "), ModChat.value(file.getFileName().toString()),
                ModChat.dim(exists ? "." : " - no file yet; it's written the first time you add a node."));
    }

    private static void stopChain() {
        if (!Ap3Executor.isRunning()) {
            ModChat.send(FEATURE, ModChat.text("Nothing to stop - no node is being performed, no walk is held, nothing is queued."));
            return;
        }
        Ap3Executor.stop("/ap3 stop");
    }

    // ---- shared helpers (also used by the tab) ----

    /** The chains file in use ("Choose AP3 Config") - {@code Ap3Store.directory()} is the folder the "Open Folder"
     *  button opens, and every file in it is one he can switch to. */
    public static Path chainsFile() {
        return Ap3Store.file();
    }

    /**
     * True in the F7/M7 boss fight with a known phase - the only place AP3 ever drives. Never throws. The SAME gate
     * node placement uses ({@link Ap3Feature#isBossLive()}): this used to be chat-phase only, so on p3sim (no Goldor
     * line) {@code /ap3 add} accepted nodes while {@code /ap3 start}, Start Chain and the status line all refused.
     */
    public static boolean inBoss() {
        try {
            return Ap3Feature.isBossLive();
        } catch (Exception e) {
            return false;
        }
    }

    /** "S1".."S5" / "P1" / "P2" / "P4" / "P5" for the boss area you are standing in (the same answer {@code /ap3 add}
     *  files a node under), or "this area" when there isn't one yet. */
    public static String areaName() {
        try {
            Ap3Area area = Ap3Feature.currentArea();
            if (area != null) {
                return area.label();
            }
        } catch (Exception ignored) {
            // tracker not ready - fall through
        }
        return "this area";
    }

    /** "Axis Align", "Leap Counter", ... from the enum constant. */
    public static String typeName(Ap3Node.Type type) {
        String[] words = type.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * One-line description of a node for {@code /ap3 list} and the tab's rows: type, the per-type modifier
     * (leap target, count, wall side), the general modifiers (box, wait, close, precise) and the position.
     */
    public static String describe(Ap3Node node) {
        StringBuilder sb = new StringBuilder(typeName(node.type()));
        switch (node.type()) {
            case LEAP -> sb.append(switch (node.leapMode()) {
                case DEFAULT -> " (Fast Leap target)";
                case CLASS -> " -> " + (node.leapClass() == null ? "?" : node.leapClass().displayName());
                case IGN -> " -> " + (node.leapIgn() == null ? "?" : node.leapIgn());
            });
            case LEAP_COUNTER -> sb.append(" x").append(node.leapCount());
            case AXIS_ALIGN -> sb.append(" wall ").append(node.wallDir() == null ? "?" : node.wallDir().getName());
            default -> {
            }
        }
        if (!node.hasDefaultBox()) {
            sb.append(" box ").append(Ap3Node.fmt(node.width())).append('x').append(Ap3Node.fmt(node.length()));
        }
        if (node.precise() && node.type().isAlign()) {
            sb.append(" precise");
        }
        if (node.waitAfterMs() > 0) {
            sb.append(" wait:").append(node.waitAfterMs());
        }
        if (node.closeGate()) {
            sb.append(" close");
        }
        if (node.jumpMod != Ap3Node.JumpMod.NONE) {
            sb.append(node.jumpMod == Ap3Node.JumpMod.EDGE ? " edge" : " jump");
        }
        return sb.append(" @ ").append(fmt(node.x())).append(", ").append(fmt(node.y())).append(", ").append(fmt(node.z())).toString();
    }

    /** "#3 Align box 3x3 @ ..." - the tab's row text, so the tab and {@code /ap3 list} can't disagree. */
    public static String describeNumbered(int index, Ap3Node node) {
        return "#" + (index + 1) + " " + describe(node);
    }

    /** Block-centre coordinates need one decimal ("12.5"); a precise align keeps two ("12.73"). */
    private static String fmt(double v) {
        return v * 10 == Math.rint(v * 10) ? String.format(Locale.US, "%.1f", v) : String.format(Locale.US, "%.2f", v);
    }
}
