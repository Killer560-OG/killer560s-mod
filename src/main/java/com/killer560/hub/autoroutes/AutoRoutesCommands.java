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
import com.killer560.hub.util.ModLog;

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
 * {@code /ar list}, {@code /ar delete <n>} (since 2026-10-04 also {@code /ar remove}, both with the number
 * optional, plus {@code /ar undo} and {@code /ar stop} - AP3's set) - and, added the same day, {@code /ar reload} ("reload it while already
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
 * still load - {@link RouteStore} folds them onto the modifier fields.
 * <p>
 * Tightened 2026-10-04 (killer560): "The only commands there should be are boom, breaker, ew/etherwarp, use, and
 * walk ... no other command should work after /ar add", and after the type "only have it show await:x and start",
 * where x must be a number. So {@link #ADD_TYPES} is the whole list - no aliases, no bare {@code /ar add start} /
 * {@code /ar add await} - and {@link #parseModifiers} takes exactly {@code start} and {@code await:<number>}.
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

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

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
        /** killer560, 2026-10-05: two path nodes, and it etherwarps from the first to the second the way the
         *  Interactive Map does - see RoutePathPlanner. */
        ADD_PATH("add_path", "Add Path Node", "/ar add path"),
        /** killer560, 2026-10-05: a node that kills the crypt it looks at with the Crypt Weapon setting's item. */
        ADD_CRYPT("add_crypt", "Add Crypt Node", "/ar add crypt"),
        ADD_BAT("add_bat", "Add Bat Node", "/ar add bat"),
        /** killer560, 2026-10-09: "Add a jump node to auto routes where it just jumps once." */
        ADD_JUMP("add_jump", "Add Jump Node", "/ar add jump <n>"),
        /** killer560, 2026-10-20: "an align node that I can do W2 L2 on" - AP3's align, its box W x L blocks. */
        ADD_ALIGN("add_align", "Add Align Node", "/ar add align w<n> l<n>"),
        EDIT_BREAKER("edit_db", "Edit Breaker Blocks", "/ar edit db"),
        CLEAR("clear", "Clear Room Route", "/ar clear"),
        LIST("list", "List Nodes", "/ar list"),
        /** AP3's UNDO (killer560, 2026-10-04: "the same node numbering system per room and the remove and delete
         *  and undo command"): the most recent add / remove / breaker edit / clear, see {@link RouteHistory}. */
        UNDO("undo", "Undo Last Node", "/ar undo"),
        /** The opposite of UNDO (killer560, 2026-10-04: "a /ar redo where it is the opposite of undo and will
         *  restore things I just deleted or undid"). See {@link RouteHistory}. */
        REDO("redo", "Redo Last Change", "/ar redo"),
        /** AP3's DELETE_NEAREST, id unchanged so an existing binding keeps working. The command takes an optional
         *  number; the key (and the bare command) delete the node you stand clearly nearest. It used to delete the
         *  LAST node. Label has no ':' of its own (2026-09-20 tooltip sweep: SettingTooltips.key() cuts at the
         *  first colon, and the row appends " Key: <name>"). */
        DELETE_NEAREST("delete", "Delete Nearest Node", "/ar delete|remove [n]"),
        RELOAD("reload", "Reload Routes File", "/ar reload"),
        /** AP3's {@code /ap3 stop}: ends the running route and releases every key. Counts as the player taking
         *  over, so the node he stands in will not fire again until he steps off it - which is how a ping-pong of
         *  two nodes landing in each other (see RouteExecutor's arrival re-fire) is broken by command or key. */
        STOP("stop", "Stop Route", "/ar stop");

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

    /** Every word {@code /ar add <type>} accepts, and the only ones - killer560's list, in his order. A walk node
     *  sprints (see {@code RouteExecutor}'s walk hold) even though it is called walk. */
    private static final java.util.Map<String, RouteNode.Type> ADD_TYPES = java.util.Map.ofEntries(
            java.util.Map.entry("boom", RouteNode.Type.BOOM),
            java.util.Map.entry("breaker", RouteNode.Type.DUNGEON_BREAKER),
            java.util.Map.entry("dungeonbreaker", RouteNode.Type.DUNGEON_BREAKER),
            java.util.Map.entry("ew", RouteNode.Type.ETHERWARP),
            java.util.Map.entry("etherwarp", RouteNode.Type.ETHERWARP),
            java.util.Map.entry("use", RouteNode.Type.USE_ITEM),
            java.util.Map.entry("walk", RouteNode.Type.WALK),
            java.util.Map.entry("path", RouteNode.Type.PATH),
            java.util.Map.entry("crypt", RouteNode.Type.CRYPT),
            java.util.Map.entry("bat", RouteNode.Type.BAT),
            java.util.Map.entry("jump", RouteNode.Type.JUMP),
            java.util.Map.entry("align", RouteNode.Type.ALIGN));
    private static final List<String> TYPE_WORDS = List.of("boom", "breaker", "dungeonbreaker", "ew", "etherwarp", "use", "walk", "path",
            "crypt", "bat", "jump", "align");
    /** Modifiers offered after any {@code /ar add <type>}. {@code await:} completes to just that, so the number is
     *  typed straight after it (killer560, 2026-10-06: "make await fill as await: without the x"); the command
     *  refuses {@code await:} followed by anything but a number. */
    private static final List<String> MOD_WORDS = List.of("await:", "start");
    /** Bounded so a typed number can never overflow parseInt; a room never holds anywhere near this many secrets. */
    private static final java.util.regex.Pattern AWAIT_ARG = java.util.regex.Pattern.compile("await:(\\d{1,3})");

    private static final SuggestionProvider<FabricClientCommandSource> TYPE_SUGGEST =
            (ctx, b) -> suggestTokens(b, TYPE_WORDS);
    private static final SuggestionProvider<FabricClientCommandSource> MOD_SUGGEST = (ctx, b) -> {
        // Each modifier once: "start" already typed is not offered again, nor is "await:" once there is one.
        // Only the finished words count - the one being typed is what is being completed.
        String remaining = b.getRemaining().toLowerCase(Locale.ROOT);
        int lastSpace = remaining.lastIndexOf(' ');
        boolean hasStart = false;
        boolean hasAwait = false;
        if (lastSpace > 0) {
            for (String t : remaining.substring(0, lastSpace).trim().split("\\s+")) {
                hasStart |= t.equals("start");
                hasAwait |= t.startsWith("await");
            }
        }
        List<String> left = new java.util.ArrayList<>();
        for (String m : MOD_WORDS) {
            if (m.startsWith("await:") ? !hasAwait : !hasStart) {
                left.add(m);
            }
        }
        return suggestTokens(b, left);
    };

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
        // "/ar and /autoroutes, both are the exact same, just different wording" (killer560, 2026-10-04). The same
        // tree is BUILT TWICE under the two names - ProfileViewerFeature's /pv + /killer560pv pattern - rather than
        // a Brigadier redirect, which forwards the children but not the root's own executes (bare /autoroutes
        // would print nothing) and tab-completes through the alias less reliably.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(root("ar"));
            dispatcher.register(root("autoroutes"));
        });
    }

    /** The whole command tree under {@code name} - {@code ar} or {@code autoroutes}, identical in every branch. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> root(String name) {
        return ClientCommands.literal(name)
                        // Hidden from tab-completion, not merely refused, when Auto Routes is off - his request
                        // 2026-09-28. The refusal message below stays for the case where it is turned off
                        // mid-session with the command already typed.
                        .requires(src -> AutoRoutesConfig.getInstance().isEnabledRaw())
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("start")
                                .then(ClientCommands.literal("record")
                                        .executes(context -> exec(Action.START_RECORD))))
                        .then(ClientCommands.literal("stop")
                                .executes(context -> exec(Action.STOP))
                                .then(ClientCommands.literal("record")
                                        .executes(context -> exec(Action.STOP_RECORD))))
                        // Short forms of "/ar edit db" (killer560, 2026-10-06: "/ar editdb or /ar db").
                        .then(ClientCommands.literal("db").executes(context -> exec(Action.EDIT_BREAKER)))
                        .then(ClientCommands.literal("editdb").executes(context -> exec(Action.EDIT_BREAKER)))
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
                        // "/ar edit <n>" opens the node editor (killer560, 2026-10-04: "the same concept as the AP3
                        // node editor but also a goto button"); "/ar edit db" is unchanged - the literals win over
                        // the number argument, so the two cannot be confused.
                        .then(ClientCommands.literal("edit")
                                .then(ClientCommands.literal("db").executes(context -> exec(Action.EDIT_BREAKER)))
                                .then(ClientCommands.literal("dungeonbreaker").executes(context -> exec(Action.EDIT_BREAKER)))
                                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                                        .suggests(NODE_NUMBER_SUGGEST)
                                        .executes(context -> {
                                            int n = IntegerArgumentType.getInteger(context, "n");
                                            return guarded(() -> openEditor(n - 1)) ? 1 : 0;
                                        })))
                        .then(ClientCommands.literal("clear").executes(context -> exec(Action.CLEAR)))
                        .then(ClientCommands.literal("list").executes(context -> exec(Action.LIST)))
                        .then(ClientCommands.literal("reload").executes(context -> exec(Action.RELOAD)))
                        // Re-plans every saved path pair with the exact (fewest-warps) planner - see replan().
                        .then(ClientCommands.literal("replan").executes(context -> guarded(AutoRoutesCommands::replan)
                                ? 1 : 0))
                        .then(ClientCommands.literal("undo").executes(context -> exec(Action.UNDO)))
                        .then(ClientCommands.literal("redo").executes(context -> exec(Action.REDO)))
                        // "/ar delete [n]" and "/ar remove [n]" are the same command, as "/ap3 delete|remove [n]"
                        // are. <n> is the 1-based number "/ar list" and the world labels show; with no number the
                        // node you stand clearly nearest goes. Converted to 0-based exactly here.
                        .then(deleteBranch("delete"))
                        .then(deleteBranch("remove"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> deleteBranch(String word) {
        return ClientCommands.literal(word)
                .executes(context -> exec(Action.DELETE_NEAREST))
                .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                        .suggests(NODE_NUMBER_SUGGEST)
                        .executes(context -> {
                            int n = IntegerArgumentType.getInteger(context, "n");
                            return guarded(() -> delete(n - 1)) ? 1 : 0;
                        }));
    }

    /** Offers the numbers this room's nodes have right now, 1..size - the numbers on the world labels. */
    private static final SuggestionProvider<FabricClientCommandSource> NODE_NUMBER_SUGGEST = (ctx, b) -> {
        int size;
        try {
            size = AutoRoutesFeature.currentRouteNodes().size();
        } catch (Exception e) {
            size = 0;
        }
        String typed = b.getRemaining();
        for (int i = 1; i <= size; i++) {
            String n = Integer.toString(i);
            if (n.startsWith(typed)) {
                b.suggest(n);
            }
        }
        return b.buildFuture();
    };

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

    /** The text the Add Jump Node key leaves in chat, cursor at the end, for him to finish with the count. */
    public static final String JUMP_PROMPT = "/ar add jump ";

    /** Opens chat prefilled with {@link #JUMP_PROMPT}, as the client's own command key does with "/". Queued like
     *  {@link #openEditor}: set from inside a key handler or command, a screen can be closed again the same tick. */
    private static void openJumpPrompt() {
        openPrompt(JUMP_PROMPT);
    }

    /** The text the Add Align Node key leaves in chat, for him to finish with the box (w2 l2). */
    public static final String ALIGN_PROMPT = "/ar add align ";

    private static void openPrompt(String text) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> com.killer560.hub.compat.McCompat.setScreen(client,
                new net.minecraft.client.gui.screens.ChatScreen(text, false)));
    }

    private static void dispatch(Action action) {
        LOGGER.info("[AutoRoutes] command {}: room {}, running={} recording={} editMode={}", action, roomName(),
                RouteExecutor.isRunning(), RouteRecorder.isRecording(), AutoRoutesFeature.isEditMode());
        switch (action) {
            case START_RECORD -> startRecord();
            case STOP_RECORD -> stopRecord();
            case ADD_ETHERWARP -> add(RouteNode.Type.ETHERWARP);
            case ADD_BREAKER -> add(RouteNode.Type.DUNGEON_BREAKER);
            case ADD_USE_ITEM -> add(RouteNode.Type.USE_ITEM);
            case ADD_WALK -> add(RouteNode.Type.WALK);
            case ADD_BOOM -> add(RouteNode.Type.BOOM);
            case ADD_PATH -> add(RouteNode.Type.PATH);
            case ADD_CRYPT -> add(RouteNode.Type.CRYPT);
            case ADD_BAT -> add(RouteNode.Type.BAT);
            // A key cannot type the count (killer560, 2026-10-12: "Type the command out with the player ready to type in the
            // number of jumps"): it opens chat holding "/ar add jump " and sends nothing - Enter does, after the number.
            case ADD_JUMP -> openJumpPrompt();
            // The same for an align: the key cannot type the box, so chat opens holding "/ar add align ".
            case ADD_ALIGN -> openPrompt(ALIGN_PROMPT);
            case EDIT_BREAKER -> toggleEditMode();
            case CLEAR -> clear();
            case LIST -> list();
            case UNDO -> AutoRoutesFeature.undo();
            case REDO -> AutoRoutesFeature.redo();
            case DELETE_NEAREST -> deleteNearest();
            case RELOAD -> reload();
            case STOP -> stopRoute();
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
            LOGGER.info("[AutoRoutes] command refused: not a cheat build");
            ModChat.send(FEATURE, ModChat.bad("Not available in this build."));
            return false;
        }
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            LOGGER.info("[AutoRoutes] command refused: Skyblock gate closed");
            ModChat.send(FEATURE, ModChat.bad("Mods are paused outside Skyblock / p3sim."));
            return false;
        }
        if (!AutoRoutesConfig.getInstance().isEnabledRaw()) {
            LOGGER.info("[AutoRoutes] command refused: Auto Routes is off");
            ModChat.send(FEATURE, ModChat.bad("Auto Routes is OFF - turn it on in the Dungeon tab first."));
            return false;
        }
        if (Minecraft.getInstance().player == null) {
            LOGGER.info("[AutoRoutes] command refused: no player yet");
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
        ModChat.send(FEATURE, ModChat.value("/ar edit <n>"), ModChat.dim(" - Node Editor (type, start, await, position, look, item, Go To)"));
        ModChat.send(FEATURE, ModChat.value("/ar replan"), ModChat.dim(" - Re-plan every saved path pair (fewest warps)"));
        ModChat.send(FEATURE, ModChat.dim("/autoroutes works everywhere /ar does."));
        ModChat.send(FEATURE, ModChat.dim("Types: boom, breaker, ew (or etherwarp), use, walk, path, crypt, jump <number of jumps>, align w<width> l<length> (AP3's align box, e.g. align w2 l2). After the type (and a jump's number), any order: "),
                ModChat.value("start"), ModChat.dim(" (this room's start node), "), ModChat.value("await:<number>"),
                ModChat.dim(" (wait for that many secrets first) - e.g. "), ModChat.value("/ar add ew start await:2"),
                ModChat.dim("."));
    }

    /**
     * {@code /ar edit <n>}: opens {@link AutoRoutesEditScreen} on node {@code index} (0-based) of this room's route.
     * Opened through {@code client.execute}, as {@code /ap3 edit} is: a client command runs while the chat screen is
     * still up, and setting a screen from inside it closes the new one again on the same tick.
     */
    private static void openEditor(int index) {
        Route route = AutoRoutesFeature.editableRoute();
        List<RouteNode> nodes = route == null ? List.of() : route.nodes();
        if (index < 0 || index >= nodes.size()) {
            ModChat.send(FEATURE, ModChat.bad("No node #" + (index + 1)),
                    ModChat.text(nodes.isEmpty() ? " - " + roomName() + " has no route." : " - there are " + nodes.size() + "."));
            return;
        }
        RouteNode node = nodes.get(index);
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> com.killer560.hub.compat.McCompat.setScreen(client, new AutoRoutesEditScreen(route, node)));
    }

    /**
     * {@code /ar replan}: every saved path plan in every room is thrown away and planned again with the exact planner -
     * this room's pairs now, one after another, every other room's when its route next fires the pair or is edited.
     */
    private static void replan() {
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("re-planning paths");
        }
        int[] done = RoutePathPlanner.invalidateAll();
        ModChat.send(FEATURE, ModChat.text("Cleared "), ModChat.value(done[0] + " saved path plan" + (done[0] == 1 ? "" : "s")),
                ModChat.text(" in "), ModChat.value(done[1] + " room" + (done[1] == 1 ? "" : "s")),
                ModChat.dim(" - this room's re-plan now, the rest the next time their route runs or is edited."));
        RoutePathPlanner.refreshStale(AutoRoutesFeature.editableRoute(), AutoRoutesFeature.editableFrame());
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
        add(type, modifiers, null);
    }

    private static void add(RouteNode.Type type, RouteRecorder.NodeModifiers modifiers, String argument) {
        // The recorder owns where the node lands (look target for etherwarp, feet for walk, held item for use...)
        // and returns null having already said why when it refuses. This used to ignore that and print
        // "Added Use Item node" straight after "Hold the item to use first." (2026-09-16 review) - and threw
        // away the detail the recorder had earned: the matched item id, the await condition, and the
        // "no etherwarpable block in sight" warning.
        String status = RouteRecorder.addNode(type, modifiers, argument);
        if (status == null) {
            return;
        }
        Component tail = switch (type) {
            case DUNGEON_BREAKER -> ModChat.dim(" - /ar db, then right-click its blocks.");
            case USE_ITEM -> ModChat.dim(status.contains("(empty hand)") ? " - right-clicks the block it looks at, by hand."
                    : " - matched on the item, not the slot.");
            case PATH -> ModChat.dim(" - path nodes pair up in number order; the first warps to the second (in a trap room it walks there, planned by AP3).");
            case CRYPT -> ModChat.dim(" - place it at the crypt; uses the Crypt Weapon straight down until a crypt or prince dies (a Spirit Sceptre then aims at each crypt mob, two uses each).");
            case BAT -> ModChat.dim(" - place it where the bat flies; uses the Bat Weapon straight down until a secret bat dies.");
            case JUMP -> ModChat.dim(" - presses jump that many times when it fires.");
            case ALIGN -> ModChat.dim(" - walking into its box lines you up on the block center, as an AP3 align does.");
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
            RouteNode.Type type = ADD_TYPES.get(key);
            LOGGER.info("[AutoRoutes] command /ar add {} {}: type {}", typeWord, mods == null ? "" : mods, type);
            if (type == null) {
                ModChat.send(FEATURE, ModChat.bad("Unknown node type "), ModChat.value(typeWord),
                        ModChat.dim(" - boom, breaker, ew (or etherwarp), use, walk, path, crypt, jump <number> or align w<n> l<n>. start and await:<number> go after it, e.g. "),
                        ModChat.value("/ar add ew start await:2"), ModChat.dim("."));
                return;
            }
            String rest = mods;
            String argument = null;
            if (type == RouteNode.Type.JUMP) {
                // "/ar add jump <n> [start] [await:<n>]": the count comes first and is required (killer560, 2026-10-12).
                String[] parts = mods == null ? new String[0] : mods.trim().split("\\s+", 2);
                if (parts.length == 0 || RouteNode.parseJumps(parts[0]) < 0) {
                    LOGGER.info("[AutoRoutes] /ar add jump refused: no valid jump count");
                    ModChat.send(FEATURE, ModChat.bad("Please add a number of jumps: /ar add jump <number>"),
                            ModChat.dim(" (" + RouteNode.MIN_JUMPS + "-" + RouteNode.MAX_JUMPS + "). Nothing was added."));
                    return;
                }
                argument = parts[0];
                rest = parts.length > 1 ? parts[1] : "";
            }
            if (type == RouteNode.Type.ALIGN) {
                // "/ar add align w2 l2 [start] [await:<n>]": AP3's box words (w<n> l<n>, width:<n> length:<n>, <w>x<l>),
                // either or both, anywhere among the modifiers; a side not given is 1 block (killer560, 2026-10-20).
                double[] box = {RouteNode.DEFAULT_ALIGN_SIZE, RouteNode.DEFAULT_ALIGN_SIZE};
                StringBuilder others = new StringBuilder();
                if (mods != null && !mods.isBlank()) {
                    for (String raw : mods.trim().split("\\s+")) {
                        int took = RouteNode.parseAlignToken(raw, box);
                        if (took < 0) {
                            LOGGER.info("[AutoRoutes] /ar add align refused: box size \"{}\" out of range", raw);
                            ModChat.send(FEATURE, ModChat.bad("\"" + raw + "\" is not a box size"),
                                    ModChat.dim(" - " + RouteNode.fmtSize(RouteNode.MIN_ALIGN_SIZE) + " to "
                                            + RouteNode.fmtSize(RouteNode.MAX_ALIGN_SIZE) + " blocks, e.g. "),
                                    ModChat.value("/ar add align w2 l2"), ModChat.dim(". Nothing was added."));
                            return;
                        }
                        if (took == 0) {
                            others.append(raw).append(' ');
                        }
                    }
                }
                argument = RouteNode.fmtSize(box[0]) + " " + RouteNode.fmtSize(box[1]);
                rest = others.toString().trim();
            }
            RouteRecorder.NodeModifiers modifiers = parseModifiers(rest);
            if (modifiers == null) {
                return; // parseModifiers already said why
            }
            add(type, modifiers, argument);
        });
    }

    /**
     * The modifiers after {@code /ar add <type>}: {@code start} (this route's start node) and {@code await:<number>}
     * (wait for that many secrets before this node fires). Any order, either, neither, or both - and nothing else.
     * A bare {@code await} or {@code await:} waits for 1. {@code await:x} and {@code await:0} are refused: the x is a
     * placeholder for a number (killer560, 2026-10-04).
     * @return null (after saying why in chat) on anything unrecognised, so a typo never adds the node at all.
     */
    private static RouteRecorder.NodeModifiers parseModifiers(String mods) {
        boolean start = false;
        boolean awaitEnabled = false;
        int amount = 1;
        if (mods != null && !mods.isBlank()) {
            for (String raw : mods.trim().split("\\s+")) {
                String t = raw.toLowerCase(Locale.ROOT);
                if (t.equals("start")) {
                    start = true;
                    continue;
                }
                if (t.equals("await") || t.startsWith("await:")) {
                    // A bare "await" / "await:" means 1 (killer560, 2026-10-06: "tab fill await and press enter
                    // itll default to 1 even if it is await:"). await:x and await:0 are still refused.
                    java.util.regex.Matcher m = AWAIT_ARG.matcher(t);
                    int n = t.equals("await") || t.equals("await:") ? 1
                            : m.matches() ? Integer.parseInt(m.group(1)) : 0;
                    if (n < 1) {
                        LOGGER.info("[AutoRoutes] /ar add refused: modifier \"{}\" needs a number", raw);
                        ModChat.send(FEATURE, ModChat.bad("\"" + raw + "\" needs a number of secrets"),
                                ModChat.dim(" - e.g. "), ModChat.value("await:2"), ModChat.dim(". Nothing was added."));
                        return null;
                    }
                    awaitEnabled = true;
                    amount = n;
                    continue;
                }
                LOGGER.info("[AutoRoutes] /ar add refused: unknown modifier \"{}\"", raw);
                ModChat.send(FEATURE, ModChat.bad("Unknown modifier \"" + raw + "\""),
                        ModChat.dim(" - only start and await:<number>. Nothing was added."));
                return null;
            }
        }
        return new RouteRecorder.NodeModifiers(start, awaitEnabled, RouteNode.AwaitCondition.SECRET, amount);
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
                    ModChat.text(" - hold your Dungeon Breaker: right-click adds a block, shift-right-click removes it."
                            + " Any other item clicks normally."));
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
        ModChat.send(FEATURE, ModChat.dim("/ar edit <n> opens a node's editor; /ar delete <n> removes one by its number; /ar delete alone takes the nearest; /ar undo / /ar redo the last change."));
    }

    private static void deleteNearest() {
        int index = AutoRoutesFeature.nearestNodeIndex();
        if (index >= 0) {
            delete(index);
        }
    }

    private static void stopRoute() {
        boolean stopped = RouteExecutor.stopByUser();
        LOGGER.info("[AutoRoutes] /ar stop: {}", stopped ? "route stopped by you" : "no route was running");
        if (!stopped) {
            ModChat.send(FEATURE, ModChat.text("No route is running."));
        }
    }

    /** {@code index} is 0-based here; the command and the tab both convert from the 1-based display number. */
    public static void delete(int index) {
        List<RouteNode> nodes = AutoRoutesFeature.currentRouteNodes();
        if (index < 0 || index >= nodes.size()) {
            LOGGER.info("[AutoRoutes] /ar delete {} refused: route has {} node(s)", index + 1, nodes.size());
            ModChat.send(FEATURE, ModChat.bad("No node #" + (index + 1)),
                    ModChat.text(nodes.isEmpty() ? " - the route is empty." : " - there are " + nodes.size() + "."));
            return;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("node deleted");
        }
        String what = nodes.get(index).type.label();
        if (AutoRoutesFeature.deleteNode(index)) {
            // AP3's line: "Deleted #3 Etherwarp from <room>". Every later node's number has just dropped by one.
            ModChat.send(FEATURE, ModChat.text("Deleted "), ModChat.value("#" + (index + 1) + " " + what),
                    ModChat.dim(" from " + roomName()));
        }
    }

    /**
     * "/ar reload ... in case you change your folder" (killer560, 2026-09-16): re-reads the one routes file
     * ({@code config/killer560/dungeons/autoroutes/killer560smod-autoroutes.json} - "I should only have to share one file") so a friend's copy
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
        LOGGER.info("[AutoRoutes] /ar reload: file exists={}, parse error={}, {} route(s) loaded", exists, parseError,
                loaded);
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
        return RouteStore.routesFile();
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
