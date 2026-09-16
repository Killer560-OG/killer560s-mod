package com.killer560.hub.ap3;

import com.google.gson.JsonParser;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The whole {@code /ap3} command tree, plus the one place every action's real body lives.
 * <p>
 * Mirrors {@link com.killer560.hub.autoroutes.AutoRoutesCommands} on purpose - the spec says "an /ap3 command tree
 * mirroring Auto Routes'" with "every command gets an assignable keybind". So every action is an {@link Action}
 * constant with a stable id: {@link Ap3Keybinds} polls the id's key and calls exactly the same {@link Action#run()}
 * the command does, and {@link com.killer560.hub.gui.tab.Ap3Tab} lists the same constants for its buttons and
 * keybind rows. Adding a command means one constant here and one {@code .then(...)} in {@link #register()}.
 * <p>
 * <b>One chat line per action.</b> Auto Routes shipped with the inner API ({@code deleteNode}, {@code setEditMode})
 * and the command both talking, and the inner one used 0-based numbers. Here the command layer owns ALL success
 * feedback and does its own range checks before calling into {@link Ap3Feature}, so the feature's own "no node #n"
 * paths never run from here. The core must stay quiet on success (see INTEGRATION.md); it may still explain a
 * refusal (not in P3, section unknown), in which case this layer prints nothing extra.
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

    /** Name of the single shareable chains file inside {@code Ap3Store.getInstance().directory()}. Must match the store. */
    public static final String CHAINS_FILE_NAME = "killer560smod-ap3.json";

    /** {@code /ap3 add wait <ms>} bounds. Two minutes is longer than any P3 section lasts. */
    public static final int MIN_WAIT_MS = 1;
    public static final int MAX_WAIT_MS = 120_000;

    /**
     * The millisecond value the NEXT wait node gets. {@code /ap3 add wait <ms>} sets it and adds; the keybind and
     * the tab's "Add Wait Node" button add with whatever it currently is (a key can't carry a number - same reason
     * the delete keybind deletes the last node). Session-only scratch, not a setting: killer560's "add a wait
     * modifier in milliseconds" is a per-node value, and it's stored on the node.
     */
    private static int pendingWaitMillis = 1000;

    /**
     * Every {@code /ap3} action. {@code id} is the key the config stores the keybind under and never changes once
     * shipped; {@code label} is what the settings tab shows (kept distinct from Auto Routes' labels so the two tabs'
     * tooltips can't cross-match - SettingTooltips keys on the label); {@code command} is the chat syntax.
     */
    public enum Action {
        ADD_LINE("add_line", "Add Line Node", "/ap3 add line"),
        ADD_AXIS_LINE("add_axisline", "Add Axis Line Node", "/ap3 add axisline"),
        /** "(No Turn)" because that IS the point of AP3's walk: "it does not actually make my character face that
         *  way, but it will move that way" (killer560). Also keeps the label distinct from Auto Routes' walk node. */
        ADD_WALK("add_walk", "Add Walk (No Turn) Node", "/ap3 add walk"),
        ADD_RUN("add_run", "Add Run (No Turn) Node", "/ap3 add run"),
        ADD_LEAP("add_leap", "Add Leap Node", "/ap3 add leap"),
        ADD_LEAP_DETECTOR("add_leapdetector", "Add Leap Detector Node", "/ap3 add leapdetector"),
        ADD_TERMINAL("add_terminal", "Add Terminal Node", "/ap3 add terminal"),
        /** The command takes a number; the key adds with {@link #getPendingWaitMillis()}. */
        ADD_WAIT("add_wait", "Add Wait Node", "/ap3 add wait <ms>"),
        ADD_STOP("add_stop", "Add Stop Node", "/ap3 add stop"),
        ADD_LOOK("add_look", "Add Look Node", "/ap3 add look"),
        ADD_BREAKER("add_breaker", "Add Breaker Node", "/ap3 add breaker"),
        EDIT_BREAKER("edit_db", "Breaker Edit Mode", "/ap3 edit db"),
        LIST("list", "List Chain", "/ap3 list"),
        /** The command takes a number; a key can't, so the keybind deletes the LAST node (the one you just added). */
        DELETE_LAST("delete", "Delete Last Chain Node", "/ap3 delete <n>"),
        CLEAR("clear", "Clear Chain", "/ap3 clear"),
        RELOAD("reload", "Reload Chains File", "/ap3 reload"),
        START("start", "Start Chain", "/ap3 start"),
        STOP("stop", "Stop Chain", "/ap3 stop");

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

    // ---- registration ----

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see INTEGRATION.md). */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ap3")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.literal("line").executes(context -> exec(Action.ADD_LINE)))
                                .then(ClientCommands.literal("axisline").executes(context -> exec(Action.ADD_AXIS_LINE)))
                                // Spelled-out alias, same courtesy as "/ar add etherwarp" - nobody should have to
                                // remember which spelling a command wants.
                                .then(ClientCommands.literal("axis").executes(context -> exec(Action.ADD_AXIS_LINE)))
                                .then(ClientCommands.literal("walk").executes(context -> exec(Action.ADD_WALK)))
                                .then(ClientCommands.literal("run").executes(context -> exec(Action.ADD_RUN)))
                                .then(ClientCommands.literal("leap").executes(context -> exec(Action.ADD_LEAP)))
                                .then(ClientCommands.literal("leapdetector").executes(context -> exec(Action.ADD_LEAP_DETECTOR)))
                                .then(ClientCommands.literal("detector").executes(context -> exec(Action.ADD_LEAP_DETECTOR)))
                                .then(ClientCommands.literal("terminal").executes(context -> exec(Action.ADD_TERMINAL)))
                                .then(ClientCommands.literal("term").executes(context -> exec(Action.ADD_TERMINAL)))
                                .then(ClientCommands.literal("stop").executes(context -> exec(Action.ADD_STOP)))
                                .then(ClientCommands.literal("look").executes(context -> exec(Action.ADD_LOOK)))
                                .then(ClientCommands.literal("breaker").executes(context -> exec(Action.ADD_BREAKER)))
                                // "/ap3 add wait <ms>" - the number is required; a bare "/ap3 add wait" is a usage
                                // error so a typo can't silently add a node with the previous value.
                                .then(ClientCommands.literal("wait")
                                        .then(ClientCommands.argument("ms", IntegerArgumentType.integer(MIN_WAIT_MS, MAX_WAIT_MS))
                                                .executes(context -> {
                                                    setPendingWaitMillis(IntegerArgumentType.getInteger(context, "ms"));
                                                    return exec(Action.ADD_WAIT);
                                                }))))
                        .then(ClientCommands.literal("edit")
                                .then(ClientCommands.literal("db").executes(context -> exec(Action.EDIT_BREAKER)))
                                .then(ClientCommands.literal("dungeonbreaker").executes(context -> exec(Action.EDIT_BREAKER)))
                                .then(ClientCommands.literal("breaker").executes(context -> exec(Action.EDIT_BREAKER))))
                        .then(ClientCommands.literal("list").executes(context -> exec(Action.LIST)))
                        .then(ClientCommands.literal("clear").executes(context -> exec(Action.CLEAR)))
                        .then(ClientCommands.literal("reload").executes(context -> exec(Action.RELOAD)))
                        .then(ClientCommands.literal("start").executes(context -> exec(Action.START)))
                        .then(ClientCommands.literal("stop").executes(context -> exec(Action.STOP)))
                        // "/ap3 delete <n>" takes the 1-based number "/ap3 list" prints - the list is what the
                        // user is looking at when they type this. Converted to 0-based exactly here.
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
            case ADD_LINE -> add(Ap3Node.Type.LINE);
            case ADD_AXIS_LINE -> add(Ap3Node.Type.AXIS_LINE);
            case ADD_WALK -> add(Ap3Node.Type.WALK);
            case ADD_RUN -> add(Ap3Node.Type.RUN);
            case ADD_LEAP -> add(Ap3Node.Type.LEAP);
            case ADD_LEAP_DETECTOR -> add(Ap3Node.Type.LEAP_DETECTOR);
            case ADD_TERMINAL -> add(Ap3Node.Type.TERMINAL);
            case ADD_WAIT -> addWait();
            case ADD_STOP -> add(Ap3Node.Type.STOP);
            case ADD_LOOK -> add(Ap3Node.Type.LOOK);
            case ADD_BREAKER -> add(Ap3Node.Type.BREAKER);
            case EDIT_BREAKER -> toggleEditMode();
            case LIST -> list();
            case DELETE_LAST -> deleteLast();
            case CLEAR -> clear();
            case RELOAD -> reload();
            case START -> startChain();
            case STOP -> stopChain();
        }
    }

    /**
     * Shared gate. Cheat jar only (the legit jar keeps the command so a shared config can't crash it, it just says
     * so), Skyblock/p3sim only like every other feature command, and the master toggle must be on - a chain command
     * silently doing nothing while the feature is off is the first thing a tester reports as "broken". Being in P3
     * is NOT checked here: the core decides what needs P3 (placing a node needs a section; listing / reloading /
     * clearing a chain you placed earlier shouldn't need you to be standing in the boss room).
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
        if (!Ap3Config.getInstance().isEnabledRaw()) {
            ModChat.send(FEATURE, ModChat.bad("AP3 is OFF - turn it on in the New tab first."));
            return false;
        }
        return Minecraft.getInstance().player != null;
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands (F7/M7 Phase 3 only):"));
        for (Action a : Action.values()) {
            ModChat.send(FEATURE, ModChat.value(a.command), ModChat.dim(" - " + a.label));
        }
    }

    // ---- actions ----

    private static void add(Ap3Node.Type type) {
        // The core owns where the node lands (half-block snap, current yaw for walk/run, held class for leap...)
        // and explains its own refusals (not in P3, no section). Success feedback is ours, once.
        Ap3Node added = Ap3Feature.addNode(type);
        if (added == null) {
            return;
        }
        announceAdded(added);
    }

    private static void addWait() {
        Ap3Node added = Ap3Feature.addWaitNode(getPendingWaitMillis());
        if (added == null) {
            return;
        }
        announceAdded(added);
    }

    private static void announceAdded(Ap3Node added) {
        List<Ap3Node> nodes = Ap3Feature.currentChainNodes();
        // The new node is normally the last one; search by identity anyway so a core that inserts elsewhere
        // still gets the right number printed.
        int number = nodes.size();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == added) {
                number = i + 1;
                break;
            }
        }
        String tail = switch (added.type()) {
            case BREAKER -> " - /ap3 edit db, then right-click its blocks.";
            case LEAP -> " - no modifier: leaps to whoever Fast Leap says for " + sectionName() + ".";
            case TERMINAL -> " - advances when YOU complete a terminal (a manual left-click also counts).";
            case LEAP_DETECTOR -> " - advances once teammates have leapt to you (or you left-click).";
            case WAIT -> " - a manual left-click skips the wait.";
            default -> "";
        };
        ModChat.send(FEATURE, ModChat.text("Added "), ModChat.dim("#" + number + " "), ModChat.value(describe(added)),
                ModChat.text(" to "), ModChat.value(sectionName()), ModChat.dim(tail.isEmpty() ? "." : tail));
    }

    /**
     * Same edit mode as {@code /ar edit db}: right-click adds a block to the breaker node, shift-right-click
     * removes it, held item suppressed. Toggles; the core's {@code UseBlockCallback} hook does the clicking half.
     */
    private static void toggleEditMode() {
        boolean on = !Ap3Feature.isEditMode();
        Ap3Feature.setEditMode(on);
        if (Ap3Feature.isEditMode() != on) {
            // The core refused (no breaker node in this chain, feature gated...) and said why - nothing to add.
            return;
        }
        if (on) {
            ModChat.send(FEATURE, ModChat.text("Breaker edit mode "), ModChat.good("ON"),
                    ModChat.text(" - right-click a block to add it, shift-right-click to remove. "),
                    ModChat.dim("/ap3 edit db again to finish."));
        } else {
            ModChat.send(FEATURE, ModChat.text("Breaker edit mode "), ModChat.bad("OFF"), ModChat.text("."));
        }
    }

    private static void list() {
        List<Ap3Node> nodes = Ap3Feature.currentChainNodes();
        if (nodes.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No chain for "), ModChat.value(sectionName()),
                    ModChat.dim(" - /ap3 add <type> while standing where the node goes."));
            return;
        }
        ModChat.send(FEATURE, ModChat.value(sectionName()), ModChat.text(": " + nodes.size() + " node" + (nodes.size() == 1 ? "" : "s")),
                Ap3Executor.isRunning() ? ModChat.good(" (running)") : ModChat.text(""),
                Ap3Feature.isEditMode() ? ModChat.good(" (editing breaker)") : ModChat.text(""));
        for (int i = 0; i < nodes.size(); i++) {
            ModChat.send(FEATURE, ModChat.dim("#" + (i + 1) + " "), ModChat.value(describe(nodes.get(i))));
        }
        ModChat.send(FEATURE, ModChat.dim("/ap3 delete <n> removes one by its number."));
    }

    private static void deleteLast() {
        int size = Ap3Feature.currentChainNodes().size();
        if (size == 0) {
            ModChat.send(FEATURE, ModChat.text("No nodes to delete in "), ModChat.value(sectionName()), ModChat.text("."));
            return;
        }
        delete(size - 1);
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
            // Yanking a node out from under a running chain would leave the executor seeking a node that no
            // longer exists - stop it first, with a reason it can echo.
            Ap3Executor.stop("node deleted");
        }
        String what = describe(nodes.get(index));
        if (!Ap3Feature.deleteNode(index)) {
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Deleted "), ModChat.dim("#" + (index + 1) + " "), ModChat.value(what), ModChat.text("."));
    }

    private static void clear() {
        int count = Ap3Feature.currentChainNodes().size();
        if (count == 0) {
            ModChat.send(FEATURE, ModChat.text("No chain for "), ModChat.value(sectionName()), ModChat.text("."));
            return;
        }
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain cleared");
        }
        if (Ap3Feature.isEditMode()) {
            Ap3Feature.setEditMode(false);
        }
        Ap3Feature.clearCurrentChain();
        ModChat.send(FEATURE, ModChat.text("Cleared "), ModChat.value(count + " node" + (count == 1 ? "" : "s")),
                ModChat.text(" from "), ModChat.value(sectionName()), ModChat.text("."));
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

    /**
     * Preconditions are checked here so the chat line is ours: the core's {@code start()} is only reached when
     * there is something to run. BOSS ONLY - "It should only work in the boss stages, not in regular clear."
     */
    private static void startChain() {
        if (Ap3Executor.isRunning()) {
            ModChat.send(FEATURE, ModChat.text("Already running. "), ModChat.dim("/ap3 stop first."));
            return;
        }
        if (!inP3()) {
            ModChat.send(FEATURE, ModChat.bad("Not in F7/M7 Phase 3"), ModChat.text(" - AP3 only runs in the boss's terminal phase."));
            return;
        }
        int count = Ap3Feature.currentChainNodes().size();
        if (count == 0) {
            ModChat.send(FEATURE, ModChat.text("No chain for "), ModChat.value(sectionName()), ModChat.dim(" - nothing to start."));
            return;
        }
        if (Ap3Feature.isEditMode()) {
            Ap3Feature.setEditMode(false);
        }
        Ap3Executor.start();
        if (!Ap3Executor.isRunning()) {
            // The core refused for a reason we didn't pre-check and said why.
            return;
        }
        ModChat.send(FEATURE, ModChat.text("Started "), ModChat.value(sectionName()), ModChat.text(" chain - " + count + " node"
                + (count == 1 ? "" : "s") + ". "), ModChat.dim("Move your mouse or a movement key to stop."));
    }

    private static void stopChain() {
        if (!Ap3Executor.isRunning()) {
            ModChat.send(FEATURE, ModChat.text("Nothing is running."));
            return;
        }
        Ap3Executor.stop("/ap3 stop");
        ModChat.send(FEATURE, ModChat.text("Stopped."));
    }

    // ---- shared helpers (also used by the tab) ----

    /** The one chains file - {@code Ap3Store.getInstance().directory()} is the folder the "Open Folder" button opens. */
    public static Path chainsFile() {
        return Ap3Store.getInstance().directory().resolve(CHAINS_FILE_NAME);
    }

    public static int getPendingWaitMillis() {
        return pendingWaitMillis;
    }

    public static void setPendingWaitMillis(int millis) {
        pendingWaitMillis = Math.max(MIN_WAIT_MS, Math.min(MAX_WAIT_MS, millis));
    }

    /** True in the F7/M7 boss AND in Phase 3 - the only place AP3 ever drives. Never throws. */
    public static boolean inP3() {
        try {
            return Floor7Tracker.inF7Boss() && Floor7Tracker.inPhase(Floor7Tracker.Phase.P3);
        } catch (Exception e) {
            return false;
        }
    }

    /** "S1".."S5" from the tracker's chat-driven stage, or "this section" when there isn't one yet. */
    public static String sectionName() {
        try {
            Floor7Tracker.Stage stage = Floor7Tracker.getStage();
            if (stage != null && stage.number >= 1 && stage.number <= 5) {
                return stage.name();
            }
        } catch (Exception ignored) {
            // tracker not ready - fall through
        }
        return "this section";
    }

    /** "Axis Line", "Leap Detector", ... from the enum constant. */
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
     * (length/width, wait ms, leap target) and the snapped position. This is the ONLY place that reads a node's
     * fields, so if the core's accessor names differ (see INTEGRATION.md) there is exactly one method to fix.
     */
    public static String describe(Ap3Node node) {
        StringBuilder sb = new StringBuilder(typeName(node.type()));
        switch (node.type()) {
            case LINE, AXIS_LINE -> sb.append(" len ").append(fmt(node.length())).append(" w ").append(fmt(node.width()));
            case WAIT -> sb.append(' ').append(node.waitMillis()).append(" ms");
            case LEAP -> {
                String mod = node.leapModifier();
                sb.append(mod == null || mod.isBlank() ? " (Fast Leap target)" : " -> " + mod.trim());
            }
            default -> {
            }
        }
        return sb.append(" @ ").append(fmt(node.x())).append(", ").append(fmt(node.y())).append(", ").append(fmt(node.z())).toString();
    }

    /** "#3 Line len 4.0 w 0.5 @ ..." - the tab's row text, so the tab and {@code /ap3 list} can't disagree. */
    public static String describeNumbered(int index, Ap3Node node) {
        return "#" + (index + 1) + " " + describe(node);
    }

    /** Half-block snapped coordinates only ever need one decimal ("12.5", "-30.0"). */
    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
