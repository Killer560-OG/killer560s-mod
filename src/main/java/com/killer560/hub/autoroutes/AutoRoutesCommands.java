package com.killer560.hub.autoroutes;

import com.google.gson.JsonParser;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The whole {@code /ar} command tree for Auto Routes, plus the one place every action's real body lives.
 * <p>
 * killer560's list (2026-09-16), verbatim: {@code /ar start record}, {@code /ar stop record}, {@code /ar add ew},
 * {@code /ar add breaker}, {@code /ar add use}, {@code /ar add walk}, {@code /ar add boom}, {@code /ar add await},
 * {@code /ar add start}, {@code /ar edit db} (also {@code /ar edit dungeonbreaker}), {@code /ar clear},
 * {@code /ar list}, {@code /ar delete <n>} - and, added the same day, {@code /ar reload} ("reload it while already
 * in game in case you change your folder" - now the one shareable routes file, since he then settled on "I should
 * only have to share one file"). "Keybinds for every command" is the other half of that request, so
 * every action is an {@link Action} constant with a stable id: {@link AutoRoutesKeybinds} polls the id's key and
 * calls exactly the same {@link Action#run()} the command does, and {@link com.killer560.hub.gui.tab.AutoRoutesTab}
 * lists the same constants for its keybind rows. Adding a command means adding one constant here and one
 * {@code .then(...)} in {@link #register()} - nothing else.
 * <p>
 * {@code /ar add} REDESIGN (killer560, 2026-09-2x): "start should not be a node. I should do something like
 * /ar add etherwarp start, and that is the start node. same with await. So i could do /ar add etherwarp start
 * await:2." {@code start} and {@code await:<n>} used to be their own node types; they are trailing MODIFIERS now,
 * in any order, on top of any real type - {@code /ar add <type> [start] [await:<n>]}, mirroring
 * {@code Ap3Commands}' {@code /ap3 add <type> [mods...]} (single word type argument, one greedy modifiers
 * argument, both tab-completed). {@link #addCommand} does the parsing; {@link RouteRecorder.NodeModifiers} carries
 * the result into {@link RouteRecorder#addNode}. Old routes files with real {@code START}/{@code AWAIT} nodes
 * still load - {@link RouteStore} folds them onto the modifier fields - and {@code /ar add start} / {@code /ar
 * add await} (no type) still work as deprecated aliases for "a plain walk node with just that one modifier".
 * <p>
 * Registered as its own root ({@code /ar}) rather than under {@code /killer560} because that's the syntax killer560
 * typed, same reasoning as {@code /posmsg} in {@code Killer560ModClient}. Feedback is local orange chat via
 * {@link ModChat} - the messages are multi-part (counts, node names, file names) and the hotbar overlay
 * ({@code ModOverlayMessage}) is one line that vanishes; a route listing needs to stay readable.
 * <p>
 * Cheat build only: every action bails through {@link #ready()} on the legit jar, outside Skyblock/p3sim, and while
 * the master toggle is off, so the command exists but does nothing there - same as Lever Aura's config gating.
 * Every refusal on the way from a typed command to a node actually appearing in the room now says why in chat - a
 * command that silently does nothing is itself the bug (2026-09-2x review, chasing a report that {@code /ar add}
 * "isn't doing anything, not showing any waypoints": the only silent path found was {@link #ready()}'s /
 * {@link RouteRecorder#addNode}'s {@code player == null} check; the renderer draws every node in the room's
 * route regardless of recording state, so a node that really gets added - now confirmed by a chat line either
 * way - was never being hidden by it).
 */
public final class AutoRoutesCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod/autoroutes");

    /** Chat prefix - "[Auto Routes]" in the mod's orange. */
    public static final String FEATURE = "Auto Routes";

    /**
     * Every {@code /ar} action. {@code id} is the key the config stores the keybind under and never changes once
     * shipped (it's in people's config files); {@code label} is what the settings tab shows; {@code command} is
     * the chat syntax, shown in {@code /ar} help and next to the keybind row.
     */
    public enum Action {
        START_RECORD("start_record", "Start Recording", "/ar start record"),
        STOP_RECORD("stop_record", "Stop Recording", "/ar stop record"),
        ADD_ETHERWARP("add_ew", "Add Etherwarp Node", "/ar add ew"),
        ADD_BREAKER("add_breaker", "Add Dungeon Breaker Node", "/ar add breaker"),
        ADD_USE_ITEM("add_use", "Add Use Item Node", "/ar add use"),
        ADD_WALK("add_walk", "Add Walk Node", "/ar add walk"),
        ADD_BOOM("add_boom", "Add Superboom Node", "/ar add boom"),
        ADD_AWAIT("add_await", "Add Await Node", "/ar add await"),
        ADD_START("add_start", "Add Start Node", "/ar add start"),
        EDIT_BREAKER("edit_db", "Edit Breaker Blocks", "/ar edit db"),
        CLEAR("clear", "Clear Room Route", "/ar clear"),
        LIST("list", "List Nodes", "/ar list"),
        /** The command takes a number; a key can't, so the keybind deletes the LAST node (the one you just added).
         *  Label has no ':' of its own (2026-09-20 tooltip sweep: the old "Delete Node (key: last)" put a colon
         *  in front of the row's own " Key: <name>" suffix, so SettingTooltips.key() cut at the wrong one and no
         *  tooltip could ever match). */
        DELETE_LAST("delete", "Delete Last Node", "/ar delete <n>"),
        RELOAD("reload", "Reload Routes File", "/ar reload");

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
            AutoRoutesCommands.run(this);
        }
    }

    private AutoRoutesCommands() {
    }

    // ---- tab completion (same shape as Ap3Commands - a fixed type-word list, plus modifiers offered after any
    //      type; killer560's tab-completion ask from Ap3 applies here just as much: typing a node type or a
    //      modifier should show its options, not require memorising them) ----

    /** The node-type words {@code /ar add <type>} accepts. {@code start} and {@code await} are deliberately NOT
     *  offered here any more - they are modifiers now (see {@link #MOD_SUGGEST}) - but {@link #addCommand} still
     *  accepts them bare as deprecated aliases. */
    private static final List<String> TYPE_WORDS = List.of("walk", "ew", "etherwarp", "use", "breaker", "boom");
    /** Modifiers offered after any {@code /ar add <type>}. */
    private static final List<String> MOD_WORDS = List.of("start", "await:1", "await:2", "await:5");

    private static final SuggestionProvider<FabricClientCommandSource> TYPE_SUGGEST =
            (ctx, b) -> suggestTokens(b, TYPE_WORDS);
    private static final SuggestionProvider<FabricClientCommandSource> MOD_SUGGEST =
            (ctx, b) -> suggestTokens(b, MOD_WORDS);

    /** Suggests {@code options} for the LAST whitespace-separated token of the argument's input, so completion
     *  works inside the greedy modifiers string ("ew st" -> "start") as well as for a single word. */
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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ar")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("start")
                                .then(ClientCommands.literal("record")
                                        .executes(context -> exec(Action.START_RECORD))))
                        .then(ClientCommands.literal("stop")
                                .then(ClientCommands.literal("record")
                                        .executes(context -> exec(Action.STOP_RECORD))))
                        // "/ar add <type> [modifiers...]" - one word for the type, the rest free-form modifiers
                        // (start, await:<n>), same shape as Ap3Commands' "/ap3 add <type> [mods...]". A bare
                        // "/ar add <type>" (no modifiers argument at all) still works - see addCommand.
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
                        .then(ClientCommands.literal("edit")
                                .then(ClientCommands.literal("db").executes(context -> exec(Action.EDIT_BREAKER)))
                                .then(ClientCommands.literal("dungeonbreaker").executes(context -> exec(Action.EDIT_BREAKER))))
                        .then(ClientCommands.literal("clear").executes(context -> exec(Action.CLEAR)))
                        .then(ClientCommands.literal("list").executes(context -> exec(Action.LIST)))
                        .then(ClientCommands.literal("reload").executes(context -> exec(Action.RELOAD)))
                        // "/ar delete <n>" takes the 1-based number "/ar list" prints, not a 0-based index -
                        // the list is what the user is looking at when they type this.
                        .then(ClientCommands.literal("delete")
                                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            int n = IntegerArgumentType.getInteger(context, "n");
                                            return guarded(() -> delete(n - 1)) ? 1 : 0;
                                        })))));
    }

    private static int exec(Action action) {
        run(action);
        return 1;
    }

    // ---- the single code path commands, keybinds and tab buttons all share ----

    /** Runs {@code action} with the shared gating ({@link #ready()}) and exception fence. Never throws. */
    public static void run(Action action) {
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
            LOGGER.warn("[AutoRoutes] action failed", e);
            ModChat.send(FEATURE, ModChat.bad("That failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            return false;
        }
    }

    private static void dispatch(Action action) {
        switch (action) {
            case START_RECORD -> startRecord();
            case STOP_RECORD -> stopRecord();
            case ADD_ETHERWARP -> add(RouteNode.Type.ETHERWARP);
            case ADD_BREAKER -> add(RouteNode.Type.DUNGEON_BREAKER);
            case ADD_USE_ITEM -> add(RouteNode.Type.USE_ITEM);
            case ADD_WALK -> add(RouteNode.Type.WALK);
            case ADD_BOOM -> add(RouteNode.Type.BOOM);
            // Deprecated (keybinds only reach here - the chat command aliases these too, see addCommand): a
            // plain walk node carrying just the one modifier, since START/AWAIT stopped being node types.
            case ADD_AWAIT -> add(RouteNode.Type.WALK, RouteRecorder.NodeModifiers.awaitDefault());
            case ADD_START -> add(RouteNode.Type.WALK, RouteRecorder.NodeModifiers.startOnly());
            case EDIT_BREAKER -> toggleEditMode();
            case CLEAR -> clear();
            case LIST -> list();
            case DELETE_LAST -> deleteLast();
            case RELOAD -> reload();
        }
    }

    /**
     * Shared gate. Cheat jar only (the legit jar keeps the command so a shared config can't crash it, it just
     * says so), Skyblock/p3sim only like every other feature command in {@code Killer560ModClient}, and the master
     * toggle must be on - a route command silently doing nothing while the feature is off was the first thing a
     * tester would report as "broken".
     */
    private static boolean ready() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ModChat.send(FEATURE, ModChat.bad("Not available in this build."));
            return false;
        }
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            ModChat.send(FEATURE, ModChat.bad("Mods are paused outside Skyblock / p3sim."));
            return false;
        }
        if (!AutoRoutesConfig.getInstance().isEnabledRaw()) {
            ModChat.send(FEATURE, ModChat.bad("Auto Routes is OFF - turn it on in the New tab first."));
            return false;
        }
        if (Minecraft.getInstance().player == null) {
            // Only reachable while the world isn't fully loaded (a command typed at the "Joining world" screen,
            // or right as it disconnects) - every other refusal above already prints a reason, and a command
            // that silently does nothing is indistinguishable from a broken one (2026-09-2x review: this was
            // the one refusal /ar add could hit without saying why).
            ModChat.send(FEATURE, ModChat.bad("Not fully loaded into the world yet - try again in a moment."));
            return false;
        }
        return true;
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands:"));
        for (Action a : Action.values()) {
            ModChat.send(FEATURE, ModChat.value(a.command), ModChat.dim(" - " + a.label));
        }
        ModChat.send(FEATURE, ModChat.dim("Add modifiers after any /ar add <type>, any order: "),
                ModChat.value("start"), ModChat.dim(" (this room's start node), "), ModChat.value("await:<n>"),
                ModChat.dim(" (wait for n secrets first) - e.g. "), ModChat.value("/ar add ew start await:2"),
                ModChat.dim("."));
    }

    private static void startRecord() {
        String status = RouteRecorder.startRecording();
        if (status == null) {
            return; // the recorder said why - "already recording", "room not identified yet", ...
        }
        ModChat.send(FEATURE, ModChat.text(status));
    }

    private static void stopRecord() {
        if (!RouteRecorder.isRecording()) {
            ModChat.send(FEATURE, ModChat.text("Not recording. "), ModChat.dim("/ar start record"), ModChat.text(" to begin."));
            return;
        }
        String status = RouteRecorder.stopRecording();
        ModChat.send(FEATURE, ModChat.text(status == null ? "Recording stopped." : status));
    }

    private static void add(RouteNode.Type type) {
        add(type, RouteRecorder.NodeModifiers.NONE);
    }

    private static void add(RouteNode.Type type, RouteRecorder.NodeModifiers modifiers) {
        // The recorder owns where the node lands (look target for etherwarp, feet for walk, held item for use...)
        // and returns null having already said why when it refuses. This used to ignore that and print
        // "Added Use Item node" straight after "Hold the item to use first." (2026-09-16 review) - and threw
        // away the detail the recorder had earned: the matched item id, the await condition, and the
        // "no etherwarpable block in sight" warning.
        String status = RouteRecorder.addNode(type, modifiers);
        if (status == null) {
            return;
        }
        Component tail = switch (type) {
            case DUNGEON_BREAKER -> ModChat.dim(" - /ar edit db, then right-click its blocks.");
            case USE_ITEM -> ModChat.dim(" - matched on the item, not the slot.");
            default -> ModChat.text("");
        };
        ModChat.send(FEATURE, ModChat.text(status), tail);
    }

    /**
     * {@code /ar add <type> [start] [await:<n>]} from chat - the redesign killer560 asked for: "start should not
     * be a node ... i should do something like /ar add etherwarp start, and that is the start node. same with
     * await. So i could do /ar add etherwarp start await:2." {@code type}/{@code mods} come straight from
     * Brigadier's word / greedy-string arguments (see {@link #register()}), the same split
     * {@code Ap3Commands#addCommand} uses.
     */
    private static void addCommand(String typeWord, String mods) {
        guarded(() -> {
            String key = typeWord.trim().toLowerCase(Locale.ROOT);
            // Deprecated aliases: START and AWAIT used to be their own node types (killer560's original /ar add
            // start / /ar add await). They're modifiers on any node now, but a BARE "/ar add start" or "/ar add
            // await" (no further modifiers text) still works - a plain walk node carrying just that one
            // modifier, which is exactly what a START/AWAIT node already behaved like on its own (see
            // RouteExecutor#tickAction). "start" or "await:<n>" typed as the TYPE by mistake (e.g. someone
            // half-remembering the new syntax as "/ar add start await:2") falls through to the unknown-type
            // message below instead, which points at the real syntax - silently ignoring the "await:2" they
            // typed would be worse than saying so.
            if ((key.equals("start") || key.equals("await") || key.equals("wait")) && (mods == null || mods.isBlank())) {
                dispatch(key.equals("start") ? Action.ADD_START : Action.ADD_AWAIT);
                return;
            }
            RouteNode.Type type = RouteNode.Type.parse(key);
            if (type == null || type == RouteNode.Type.START || type == RouteNode.Type.AWAIT) {
                ModChat.send(FEATURE, ModChat.bad("Unknown node type "), ModChat.value(typeWord),
                        ModChat.dim(" - walk, ew, use, breaker, boom. start and await:<n> are modifiers now, e.g. "),
                        ModChat.value("/ar add ew start await:2"), ModChat.dim("."));
                return;
            }
            RouteRecorder.NodeModifiers modifiers = parseModifiers(mods);
            if (modifiers == null) {
                return; // parseModifiers already said why
            }
            add(type, modifiers);
        });
    }

    /**
     * The modifiers after {@code /ar add <type>}: {@code start} (this route's start node) and {@code await:<n>}
     * (wait for {@code n} secrets before this node fires) or {@code await:delay:<ms>} (wait a fixed delay
     * instead - the AWAIT node's two conditions, {@link RouteNode.AwaitCondition#SECRET} /
     * {@link RouteNode.AwaitCondition#DELAY}, carried over unchanged). Any order, either, neither, or both.
     * @return null (after saying why in chat) on an unrecognised token, so a typo never silently adds the node
     * without the modifier the player asked for.
     */
    private static RouteRecorder.NodeModifiers parseModifiers(String mods) {
        boolean start = false;
        boolean awaitEnabled = false;
        RouteNode.AwaitCondition condition = RouteNode.AwaitCondition.SECRET;
        int amount = 1;
        if (mods != null && !mods.isBlank()) {
            for (String raw : mods.trim().split("\\s+")) {
                String t = raw.toLowerCase(Locale.ROOT);
                if (t.equals("start")) {
                    start = true;
                } else if (t.equals("await")) {
                    awaitEnabled = true;
                } else if (t.startsWith("await:")) {
                    awaitEnabled = true;
                    String rest = t.substring("await:".length());
                    if (rest.startsWith("delay:")) {
                        condition = RouteNode.AwaitCondition.DELAY;
                        amount = parseAmount(rest.substring("delay:".length()), 500);
                    } else {
                        condition = RouteNode.AwaitCondition.SECRET;
                        amount = parseAmount(rest, 1);
                    }
                } else {
                    ModChat.send(FEATURE, ModChat.bad("Unknown modifier \"" + raw + "\""),
                            ModChat.dim(" - start, await:<n> (secrets), or await:delay:<ms>."));
                    return null;
                }
            }
        }
        return new RouteRecorder.NodeModifiers(start, awaitEnabled, condition, amount);
    }

    private static int parseAmount(String s, int fallback) {
        try {
            return Math.max(0, Math.min(600_000, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * "/ar edit db lets me right-click blocks to add them to that breaker and shift-right-click to remove them"
     * (killer560). Toggles; {@link AutoRoutesEditInput} does the clicking half.
     */
    private static void toggleEditMode() {
        boolean on = !AutoRoutesFeature.isEditMode();
        AutoRoutesFeature.setEditMode(on);
        if (on) {
            ModChat.send(FEATURE, ModChat.text("Breaker edit mode "), ModChat.good("ON"),
                    ModChat.text(" - right-click a block to add it, shift-right-click to remove. Your held item won't fire."));
            ModChat.send(FEATURE, ModChat.dim("/ar edit db again to finish."));
        } else {
            AutoRoutesEditInput.reset();
            ModChat.send(FEATURE, ModChat.text("Breaker edit mode "), ModChat.bad("OFF"), ModChat.text("."));
        }
    }

    private static void clear() {
        int count = AutoRoutesFeature.currentRouteNodes().size();
        if (count == 0) {
            ModChat.send(FEATURE, ModChat.text("No route in "), ModChat.value(roomName()), ModChat.text("."));
            return;
        }
        // Yanking the nodes out from under a running playback would leave the executor seeking toward a sample
        // that no longer exists - stop it first, with a reason it can echo.
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("route cleared");
        }
        if (AutoRoutesFeature.isEditMode()) {
            AutoRoutesFeature.setEditMode(false);
            AutoRoutesEditInput.reset();
        }
        AutoRoutesFeature.clearCurrentRoute();
        ModChat.send(FEATURE, ModChat.text("Cleared "), ModChat.value(count + " node" + (count == 1 ? "" : "s")),
                ModChat.text(" in "), ModChat.value(roomName()), ModChat.text("."));
    }

    private static void list() {
        List<RouteNode> nodes = AutoRoutesFeature.currentRouteNodes();
        if (nodes.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No route in "), ModChat.value(roomName()),
                    ModChat.dim(" - /ar start record, or /ar add <type>."));
            return;
        }
        ModChat.send(FEATURE, ModChat.value(roomName()), ModChat.text(": " + nodes.size() + " node" + (nodes.size() == 1 ? "" : "s")),
                RouteRecorder.isRecording() ? ModChat.good(" (recording)") : ModChat.text(""),
                RouteExecutor.isRunning() ? ModChat.good(" (running)") : ModChat.text(""));
        for (int i = 0; i < nodes.size(); i++) {
            ModChat.send(FEATURE, ModChat.dim("#" + (i + 1) + " "), ModChat.value(describe(nodes.get(i))));
        }
        ModChat.send(FEATURE, ModChat.dim("/ar delete <n> removes one by its number."));
    }

    private static void deleteLast() {
        int size = AutoRoutesFeature.currentRouteNodes().size();
        if (size == 0) {
            ModChat.send(FEATURE, ModChat.text("No nodes to delete in "), ModChat.value(roomName()), ModChat.text("."));
            return;
        }
        delete(size - 1);
    }

    /** {@code index} is 0-based here; the command and the tab both convert from the 1-based display number. */
    public static void delete(int index) {
        List<RouteNode> nodes = AutoRoutesFeature.currentRouteNodes();
        if (index < 0 || index >= nodes.size()) {
            ModChat.send(FEATURE, ModChat.bad("No node #" + (index + 1)),
                    ModChat.text(nodes.isEmpty() ? " - the route is empty." : " - there are " + nodes.size() + "."));
            return;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("node deleted");
        }
        String what = describe(nodes.get(index));
        AutoRoutesFeature.deleteNode(index);
        ModChat.send(FEATURE, ModChat.text("Deleted "), ModChat.dim("#" + (index + 1) + " "), ModChat.value(what), ModChat.text("."));
    }

    /**
     * "/ar reload ... in case you change your folder" (killer560, 2026-09-16): re-reads the one routes file
     * ({@code config/killer560smod-autoroutes.json} - "I should only have to share one file") so a friend's copy
     * dropped in, or a Notepad edit, works without a restart. The JSON syntax check is done here first so a broken
     * file gets NAMED in chat instead of quietly loading as zero routes - a route that "just doesn't trigger" is
     * far harder to debug than "killer560smod-autoroutes.json failed to parse: ...". Only syntax is checked here;
     * the store decides what's a valid route.
     */
    private static void reload() {
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("routes reloaded");
        }
        Path file = routesFile();
        String parseError = null;
        boolean exists = Files.isRegularFile(file);
        if (exists) {
            try {
                JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                parseError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        RouteStore.reload();
        int loaded = RouteStore.getInstance().routes().size();
        if (parseError != null) {
            ModChat.send(FEATURE, ModChat.bad("Failed to parse "), ModChat.value(file.getFileName().toString()),
                    ModChat.bad(": " + parseError));
            ModChat.send(FEATURE, ModChat.dim("Fix the file (or paste a good copy back in) and /ar reload again."));
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Reloaded "), ModChat.value(loaded + " route" + (loaded == 1 ? "" : "s")),
                ModChat.text(" from "), ModChat.value(file.getFileName().toString()), ModChat.text("."));
        if (!exists) {
            ModChat.send(FEATURE, ModChat.dim("No routes file yet - it's written the first time you save a route."));
        }
    }

    /** Name of the single shareable routes file inside {@link RouteStore#routesDirectory()}. Must match the store. */
    public static final String ROUTES_FILE_NAME = "killer560smod-autoroutes.json";

    /** The one routes file - {@link RouteStore#routesDirectory()} is the folder the "Open Routes Folder" button opens. */
    public static Path routesFile() {
        return RouteStore.routesDirectory().resolve(ROUTES_FILE_NAME);
    }

    // ---- small shared helpers (also used by the tab) ----

    /** Current room's name for messages, or "this room" when the live map hasn't identified one. */
    public static String roomName() {
        try {
            RoomEntry room = LiveMapFeature.currentRoomEntry();
            if (room != null && room.name != null && !room.name.isBlank()) {
                return room.name;
            }
        } catch (Exception ignored) {
            // live map not ready - fall through
        }
        return "this room";
    }

    /** "Dungeon Breaker", "Use Item", ... from the enum constant. */
    /** One name per node type everywhere - this used to say "Boom" while the world label, the colour button
     *  and the keybind row all said "Superboom" (2026-09-16 review). */
    public static String typeName(RouteNode.Type type) {
        return type.label();
    }

    /** One-line description of a node for {@code /ar list} and the tab's node rows. Delegates to the node's
     *  own describe(), which carries the item id, block count, await amount and coordinates - this used to
     *  return the bare type name and throw all of that away (2026-09-16 review). */
    public static String describe(RouteNode node) {
        return node.describe();
    }
}
