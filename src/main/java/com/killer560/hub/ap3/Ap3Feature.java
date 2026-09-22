package com.killer560.hub.ap3;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.fastleap.Floor7Tracker.Phase;
import com.killer560.hub.fastleap.Floor7Tracker.Stage;
import com.killer560.hub.fastleap.Teammates;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AP3 - hand-placed node chains that move you through the F7/M7 boss fight, one chain per {@link Ap3Area} (P1, P2,
 * the P3 sections S1-S5, P4, P5). CHEAT BUILD ONLY, default off; every entry point is behind
 * {@link Ap3Config#isEnabled()}.
 * <p>
 * <b>BOSS ONLY.</b> killer560: "It should only work in the boss stages, not in regular clear." Everything here is
 * gated on {@link #isBossLive()} - {@code Floor7Tracker.inF7Boss()} plus a known phase. Auto Routes is the opposite (clear
 * only, never boss), so the two features can never be live at the same time; that mutual exclusion is what makes
 * two separate {@code KeyboardInput#tick} mixins safe (see {@code mixin/Ap3InputMixin}).
 * <p>
 * This class owns registration, the tick wiring (every boss tick hands {@link Ap3Executor} the node set of the area
 * you stand in - every node in it is armed, walking into one fires it), the gate, world / phase change handling,
 * node placement with its modifiers, undo / nearest-node delete, the stopwatch HUD, the chat hook that feeds
 * TERMINAL / BOOM nodes and the leap rule, and the public API the commands / keybinds / tab code against. A tick or render exception never escapes: it is logged, the
 * feature switches itself off (saved) and says so in chat.
 */
public final class Ap3Feature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");
    private static final String CHAT = "AP3";
    /** Hypixel's own line, the same regex {@code Floor7Tracker.Stage} parses (kept private there), with the optional
     *  trailing suffix so a line annotated by another mod (Odin's terminal splits) still matches. */
    private static final Pattern TERM_COMPLETED =
            Pattern.compile("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    /** Hypixel's line when a P3 gate goes down - a boom node's authoritative success signal. */
    private static final String GATE_DESTROYED = "The gate has been destroyed!";
    /** {@code /ap3 delete} with no number: the nearest node has to be this close ... */
    private static final double NEAREST_MAX = 3.0;
    /** ... and every other node at least this much further away, or it is not "clearly" the one he means. */
    private static final double NEAREST_MARGIN = 1.0;

    /** Hypixel's line when YOU leap - the same regex {@code leapcounter/LeapTracker.SELF_LEAP} keys on (kept private
     *  there; see the notes for the accessor that would let this be read from it instead of matched twice). */
    private static final Pattern SELF_LEAP = Pattern.compile("^You have teleported to \\w{1,16}!$");

    private static Object lastLevel;
    /**
     * Force Dungeon - killer560 (2026-09-21): "add a force dungeon tab to the ap3 so I can config outside of
     * dungeons to test if I want to." SESSION ONLY, never saved: it is cleared on every world change and starts off
     * on every launch, so it can never be left on going into a real run. While on, AP3's own gates read as "in the
     * F7/M7 boss" wherever he is; the real arena still resolves phase / section from position and chat when he IS
     * there, otherwise {@link #forcedArea} is the area. Nothing mod-wide changes ({@code SkyblockGate} is untouched
     * for every other feature); the executor, rotation, input and ActionGate rules are exactly as in a real boss.
     */
    private static boolean forceDungeon;
    /** The area Force Dungeon stands in when position / chat give none (the hub, singleplayer...). */
    private static Ap3Area forcedArea = Ap3Area.p3(1);
    /** The cycle order of the Forced Area button: P1, P2, S1-S5, P4, P5. */
    private static final List<Ap3Area> FORCED_AREAS = List.of(
            Ap3Area.ofPhase(Phase.P1), Ap3Area.ofPhase(Phase.P2), Ap3Area.p3(1), Ap3Area.p3(2), Ap3Area.p3(3),
            Ap3Area.p3(4), Ap3Area.p3(5), Ap3Area.ofPhase(Phase.P4), Ap3Area.ofPhase(Phase.P5));
    private static boolean wasLive;
    private static boolean renderFailed;
    private static Ap3Area lastArea;
    private static int lastSimRestart;
    /** The most recently ADDED node and the chain it went into - what {@code /ap3 undo} removes. */
    private static Ap3Node lastAdded;
    private static Ap3Chain lastAddedChain;
    private static boolean migrationReported;

    private Ap3Feature() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see API.md). */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Ap3Feature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(Ap3Feature::onRenderFrame);
        ChatObserver.subscribe(Ap3Feature::onChat);
        // Same shape as DungeonAlertsFeature: our own Fabric HUD layer draws the element at the HUD editor's
        // position/scale; the lead registers STOPWATCH_HUD into HudElementRegistry so it can be dragged there.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "ap3_stopwatch"),
                (graphics, deltaTracker) -> drawStopwatchHud(graphics));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "ap3_force_dungeon"),
                (graphics, deltaTracker) -> drawForceDungeonReminder(graphics));
        // Test Align's planner, compiled by the JIT in the background so the first one in a session is not the slow one.
        Ap3FastAlign.warmUpAsync();
        LOGGER.info("[AP3] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    // ------------------------------------------------------------------------------------------- gate

    /**
     * The BOSS-ONLY gate, for every phase - killer560 (2026-09-20): "For ap3 it should work in p1 and p2 and p3 and
     * p4 and p5. Any part of boss phase it should work in." True while {@code Floor7Tracker.inF7Boss()} - the same
     * arena test as before, never in clear, which is what keeps Auto Routes' {@code KeyboardInput#tick} mixin and
     * ours from ever driving at once - and the boss phase is known ({@link #currentPhase()}).
     */
    public static boolean isBossLive() {
        return currentPhase() != Phase.UNKNOWN;
    }

    // ---- Force Dungeon (session only) ----

    public static boolean isForceDungeon() {
        return forceDungeon;
    }

    /** Turning it OFF outside a real boss is "leaving the boss": the next tick stands everything down. */
    public static void setForceDungeon(boolean on) {
        if (forceDungeon != on) {
            forceDungeon = on;
            LOGGER.info("[AP3] Force Dungeon {}", on ? "ON (session only)" : "off");
        }
    }

    public static Ap3Area forcedArea() {
        return forcedArea;
    }

    /** Next area in P1, P2, S1-S5, P4, P5 order. */
    public static void cycleForcedArea() {
        int i = FORCED_AREAS.indexOf(forcedArea);
        forcedArea = FORCED_AREAS.get((i + 1) % FORCED_AREAS.size());
    }

    /** True while Force Dungeon is what makes AP3 live - he is NOT in a real F7/M7 boss. */
    public static boolean isForcedOnly() {
        return forceDungeon && !Floor7Tracker.inF7Boss();
    }

    /**
     * The boss phase you are in: chat-driven first ({@code Floor7Tracker.getPhase()}); when no boss dialogue has been
     * heard yet this world (a mid-run rejoin; p3sim before its Maxor line, which {@code Floor7Tracker} turns into
     * P3 / UNKNOWN itself, server-checked) the position-based phase is used instead. UNKNOWN outside the F7/M7 boss -
     * unless Force Dungeon is on, when the forced area's phase stands in for whatever position / chat cannot give.
     */
    public static Phase currentPhase() {
        Phase real = Phase.UNKNOWN;
        if (Floor7Tracker.inF7Boss()) {
            Phase chat = Floor7Tracker.getPhase();
            real = chat != Phase.UNKNOWN ? chat : Floor7Tracker.getPhaseAt();
        }
        if (real == Phase.UNKNOWN && forceDungeon) {
            return forcedArea.phase();
        }
        return real;
    }

    /**
     * The area chains are added to and run in: the phase, plus the section you stand in when that phase is P3.
     * null when not live, or in P3 but outside every section box ({@link #noAreaReason()} says which).
     */
    public static Ap3Area currentArea() {
        Phase phase = currentPhase();
        if (phase == Phase.UNKNOWN) {
            return null;
        }
        if (phase != Phase.P3) {
            return Ap3Area.ofPhase(phase);
        }
        int n = currentSectionNumber();
        return n == 0 ? null : Ap3Area.p3(n);
    }

    /** Why {@link #currentArea()} is null right now, for chat. */
    static String noAreaReason() {
        return currentPhase() == Phase.P3
                ? "In P3 but not inside a section (S1-S5) - stand in one first."
                : "Not in the F7/M7 boss - AP3 is boss-only (or turn on Force Dungeon in the tab to test anywhere).";
    }

    /** The P3 section you are standing in (1-5), position first, else the chat-tracked stage, else - with Force
     *  Dungeon on and the forced area a P3 section - that section; 0 when unknown or when the current phase is not
     *  P3. */
    public static int currentSectionNumber() {
        if (currentPhase() != Phase.P3) {
            return 0;
        }
        Stage at = Floor7Tracker.getStageAt();
        if (at == Stage.UNKNOWN) {
            at = Floor7Tracker.getStage();
        }
        if (at == Stage.UNKNOWN && forceDungeon && forcedArea.isP3()) {
            return forcedArea.section();
        }
        return at.number;
    }

    /** The class you are playing for chain selection: the mod-wide override for your own IGN, else the tab list. */
    public static DungeonClass selfClass() {
        return ClassOverrides.classOf(Teammates.selfName(), PartyTracker.selfClass());
    }

    // ------------------------------------------------------------------------------------------- public API

    /** The chain nodes are added to / listed from: the area you stand in + the tab's edit class filter. The
     *  live list, or an empty list when there is no such chain yet. */
    public static List<Ap3Node> currentChainNodes() {
        Ap3Chain chain = currentChain();
        return chain == null ? Collections.emptyList() : chain.nodes();
    }

    /** The chain being edited (area you stand in + edit class filter), or null when none exists yet. */
    public static Ap3Chain currentChain() {
        Ap3Area area = currentArea();
        return area == null ? null : Ap3Store.getInstance().exact(area, Ap3Config.getInstance().getEditClassFilter());
    }

    /** Label for the tab / chat: {@code "S3"}, {@code "S3 (Mage)"}, {@code "P1"}, or {@code "no area"}. */
    public static String currentChainLabel() {
        Ap3Area area = currentArea();
        if (area == null) {
            return "no area";
        }
        return Ap3Chain.label(area, Ap3Config.getInstance().getEditClassFilter());
    }

    /**
     * Everything {@code /ap3 add <type> ...} can say after the type, applied to the new node. Every field is a
     * modifier in killer560's sense ("design these as general modifiers that apply to any node"): the box size
     * ({@code w1 l1}), {@code wait:<ms>}, {@code close}, {@code precise}, and the leap / leap-counter targets.
     */
    public static final class NodeSpec {
        public Double width;
        public Double length;
        public Integer waitMs;
        public boolean close;
        public Ap3Node.JumpMod jumpMod;
        public String name;
        public boolean precise;
        public Ap3Node.LeapMode leapMode;
        public DungeonClass leapClass;
        public String leapIgn;
        public Integer leapCount;
        /** PATH: the speed window and heading the route must cross this node at, and whether it waits for a term. */
        public Double minSpeed;
        public Double maxSpeed;
        public Double dirDeg;
        public Double dirTolDeg;
        public boolean termWait;
        /** PATH: the step number typed after the type, or null to take the lowest one still free. */
        public Integer pathIndex;

        void applyTo(Ap3Node node) {
            if (width != null) {
                node.setWidth(width);
            }
            if (length != null) {
                node.setLength(length);
            }
            if (waitMs != null) {
                node.setWaitAfterMs(waitMs);
            }
            node.closeGate = close;
            if (jumpMod != null) {
                node.jumpMod = jumpMod;
            }
            if (name != null && node.type == Ap3Node.Type.STOPWATCH) {
                node.name = name;
            }
            node.precise = precise;
            if (node.type == Ap3Node.Type.LEAP) {
                node.leapMode = leapMode == null ? Ap3Node.LeapMode.DEFAULT : leapMode;
                node.leapClass = node.leapMode == Ap3Node.LeapMode.CLASS ? leapClass : null;
                node.leapIgn = node.leapMode == Ap3Node.LeapMode.IGN && leapIgn != null && !leapIgn.isBlank() ? leapIgn.trim() : null;
            }
            if (node.type == Ap3Node.Type.LEAP_COUNTER && leapCount != null) {
                node.setLeapCount(leapCount);
            }
            if (node.type == Ap3Node.Type.PATH) {
                if (pathIndex != null) {
                    node.pathIndex = pathIndex;
                }
                node.minSpeed = minSpeed == null ? -1 : minSpeed;
                node.maxSpeed = maxSpeed == null ? -1 : maxSpeed;
                node.hasDir = dirDeg != null;
                node.dirDeg = dirDeg == null ? 0 : dirDeg;
                node.dirTolDeg = dirTolDeg == null ? 15.0 : dirTolDeg;
                node.termWait = termWait;
            }
        }
    }

    /** {@code /ap3 add <type>}: a node of {@code type} at your position and current look, no modifiers. */
    public static boolean addNode(Ap3Node.Type type) {
        return addNode(type, new NodeSpec());
    }

    /** {@code /ap3 add <type> [modifiers]}. */
    public static boolean addNode(Ap3Node.Type type, NodeSpec spec) {
        if (spec == null) {
            spec = new NodeSpec();
        }
        Ap3Node node = captureNode(type, spec.precise);
        if (node == null) {
            return false;
        }
        spec.applyTo(node);
        if (node.type == Ap3Node.Type.PATH && spec.pathIndex == null) {
            node.pathIndex = lowestFreePathIndex();
        }
        if (node.type == Ap3Node.Type.LEAP && node.leapMode == Ap3Node.LeapMode.CLASS && node.leapClass == null) {
            chatBad("Leap class missing (mage / archer / bers / tank / healer).");
            return false;
        }
        if (node.type == Ap3Node.Type.LEAP && node.leapMode == Ap3Node.LeapMode.IGN && node.leapIgn == null) {
            chatBad("Leap IGN missing.");
            return false;
        }
        return commitNode(node);
    }

    /**
     * The step number a new Path node takes when none was typed - killer560 (2026-09-22): "if I had created path one
     * and three and made a new one without a number it should become two". So: the lowest number not already used.
     */
    private static int lowestFreePathIndex() {
        Ap3Chain chain = currentChain();
        if (chain == null) {
            return 1;
        }
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Ap3Node n : chain.nodes()) {
            if (n.type == Ap3Node.Type.PATH) {
                used.add(n.pathIndex);
            }
        }
        int i = 1;
        while (used.contains(i)) {
            i++;
        }
        return i;
    }

    /** Deletes node {@code index} (0-BASED here; show it to the player as {@code index + 1}). */
    public static boolean deleteNode(int index) {
        Ap3Chain chain = currentChain();
        if (chain == null || index < 0 || index >= chain.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in " + currentChainLabel() + ".");
            return false;
        }
        Ap3Node removed = chain.nodes().remove(index);
        if (removed == lastAdded) {
            lastAdded = null;
            lastAddedChain = null;
        }
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain edited");
        }
        Ap3Store store = Ap3Store.getInstance();
        if (chain.isEmpty()) {
            store.remove(chain);
        }
        store.markEdited();
        store.save();
        suppressAutoArm();
        chat(ModChat.text("Deleted "), ModChat.value("#" + (index + 1) + " " + removed.type.label()),
                ModChat.dim(" from " + chain.label()));
        return true;
    }

    /**
     * {@code /ap3 delete} / {@code /ap3 remove} with no number - killer560: "If he is stood clearly nearest to one
     * node (no ambiguity with another), delete/remove should take that node without him naming a number." Nearest
     * within {@value #NEAREST_MAX} blocks and every other node at least {@value #NEAREST_MARGIN} further, else it
     * says so and deletes nothing.
     */
    public static boolean deleteNearestNode() {
        Ap3Chain chain = currentChain();
        Minecraft client = Minecraft.getInstance();
        if (chain == null || chain.isEmpty() || client.player == null) {
            chatBad("No nodes in " + currentChainLabel() + ".");
            return false;
        }
        Vec3 pos = client.player.position();
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        List<Ap3Node> nodes = chain.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            double d = nodes.get(i).pos().distanceTo(pos);
            if (d < bestDist) {
                second = bestDist;
                bestDist = d;
                best = i;
            } else if (d < second) {
                second = d;
            }
        }
        if (best < 0 || bestDist > NEAREST_MAX) {
            chatBad(String.format(Locale.US, "No node within %.0f blocks - stand next to one or give its number.", NEAREST_MAX));
            return false;
        }
        if (second < bestDist + NEAREST_MARGIN) {
            chatBad(String.format(Locale.US, "Two nodes are about as close (#%d and one %.1f blocks off) - give the number.",
                    best + 1, second));
            return false;
        }
        return deleteNode(best);
    }

    /** {@code /ap3 undo}: removes the node most recently CREATED (whatever area it went into), else the last node
     *  of the chain you stand in. */
    public static boolean undoLastAdded() {
        if (lastAdded != null && lastAddedChain != null) {
            int i = lastAddedChain.indexOf(lastAdded);
            if (i >= 0) {
                Ap3Chain chain = lastAddedChain;
                Ap3Node removed = chain.nodes().remove(i);
                lastAdded = null;
                lastAddedChain = null;
                if (Ap3Executor.isRunning()) {
                    Ap3Executor.stop("chain edited");
                }
                Ap3Store store = Ap3Store.getInstance();
                if (chain.isEmpty()) {
                    store.remove(chain);
                }
                store.markEdited();
                store.save();
                suppressAutoArm();
                chat(ModChat.text("Undone "), ModChat.value("#" + (i + 1) + " " + removed.type.label()),
                        ModChat.dim(" from " + chain.label()));
                return true;
            }
        }
        List<Ap3Node> nodes = currentChainNodes();
        if (nodes.isEmpty()) {
            chatBad("Nothing to undo in " + currentChainLabel() + ".");
            return false;
        }
        return deleteNode(nodes.size() - 1);
    }

    /** Deletes the last node of the current chain. */
    public static boolean deleteLastNode() {
        List<Ap3Node> nodes = currentChainNodes();
        if (nodes.isEmpty()) {
            chatBad("No nodes in " + currentChainLabel() + ".");
            return false;
        }
        return deleteNode(nodes.size() - 1);
    }

    // ---- in-place editing (killer560: "make it easier to edit them") ----
    // All 0-BASED indices here, like deleteNode; the command layer and the tab convert from the 1-based number
    // the player sees. Every one of these stops a running chain (the executor holds a node index) and saves.

    /**
     * Moves node {@code index} to {@code newIndex} in the chain being edited, shifting the nodes in between -
     * "move up" is {@code index - 1}, "move down" is {@code index + 1}. The node object keeps its identity, so
     * the tab's edit page can stay open on it across the move.
     */
    public static boolean moveNode(int index, int newIndex) {
        Ap3Chain chain = currentChain();
        if (chain == null || index < 0 || index >= chain.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in " + currentChainLabel() + ".");
            return false;
        }
        int size = chain.nodes().size();
        int target = Math.max(0, Math.min(size - 1, newIndex));
        if (target == index) {
            chat(ModChat.text("#" + (index + 1) + " is already "),
                    ModChat.value(target == 0 ? "first" : target == size - 1 ? "last" : "#" + (target + 1)),
                    ModChat.text("."));
            return false;
        }
        Ap3Node node = chain.nodes().remove(index);
        chain.nodes().add(target, node);
        saveChains();
        chat(ModChat.text("Moved "), ModChat.value("#" + (index + 1) + " " + node.type.label()),
                ModChat.text(" to "), ModChat.value("#" + (target + 1)), ModChat.dim(" in " + chain.label()));
        return true;
    }

    /**
     * Re-places node {@code index} where you stand and/or look, without deleting and re-adding it (which would
     * also lose its number in the chain and every modifier on it). {@code position} = feet position (snapped to
     * the block centre unless the node is precise), {@code look} = yaw/pitch. An AXIS_ALIGN re-reads the wall you
     * are touching when the position moves and refuses when you are not touching one, same rule as placing one.
     */
    public static boolean replaceNode(int index, boolean position, boolean look) {
        Ap3Chain chain = currentChain();
        if (chain == null || index < 0 || index >= chain.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in " + currentChainLabel() + ".");
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return false;
        }
        if (!isBossLive()) {
            chatBad("Not in the F7/M7 boss - nodes are placed in the boss fight only.");
            return false;
        }
        if (!position && !look) {
            return false;
        }
        Ap3Node node = chain.nodes().get(index);
        // Work on a copy so a refused wall check leaves the real node exactly as it was.
        Ap3Node probe = node.copy();
        if (position) {
            Vec3 pos = player.position();
            probe.x = node.precise ? pos.x : Ap3Node.snapCentre(pos.x);
            probe.y = Ap3Node.snapY(pos.y);
            probe.z = node.precise ? pos.z : Ap3Node.snapCentre(pos.z);
            if (node.type == Ap3Node.Type.AXIS_ALIGN) {
                Direction wall = Ap3Executor.touchingWall(client.level, player);
                if (wall == null) {
                    chatBad("You are not touching a wall - #" + (index + 1) + " was not moved.");
                    return false;
                }
                probe.wallDir = wall;
            }
        }
        if (look) {
            // Stored yaw is DATA (Rotation 360 rule) - wrapped for the file, never written back to the player.
            probe.yaw = Mth.wrapDegrees(Ap3FreezeState.placementYaw(player));
            probe.pitch = Mth.clamp(Ap3FreezeState.placementPitch(player), -90f, 90f);
        }
        node.x = probe.x;
        node.y = probe.y;
        node.z = probe.z;
        node.yaw = probe.yaw;
        node.pitch = probe.pitch;
        node.wallDir = probe.wallDir;
        saveChains();
        chat(ModChat.text(position ? "Re-placed " : "Re-aimed "), ModChat.value("#" + (index + 1) + " " + node.describe()),
                ModChat.dim(" in " + chain.label()));
        return true;
    }

    /**
     * Applies {@code edit} to node {@code index}, saves, and prints one "Set #n ..." line - the command form of a
     * field edit ({@code /ap3 set <n> length 4}). The tab's sliders call {@link #saveChains()} directly instead:
     * a slider fires on every pixel of a drag and must not print a chat line per pixel.
     */
    public static boolean editNode(int index, String what, java.util.function.Consumer<Ap3Node> edit) {
        Ap3Chain chain = currentChain();
        if (chain == null || index < 0 || index >= chain.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in " + currentChainLabel() + ".");
            return false;
        }
        Ap3Node node = chain.nodes().get(index);
        edit.accept(node);
        saveChains();
        chat(ModChat.text("Set "), ModChat.value("#" + (index + 1) + " " + what),
                ModChat.dim(" - " + node.describe()));
        return true;
    }

    /** Removes the whole chain being edited. @return true when one existed. */
    public static boolean clearCurrentChain() {
        if (currentArea() == null) {
            chatBad(noAreaReason());
            return false;
        }
        Ap3Chain chain = currentChain();
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain cleared");
        }
        if (chain != null && chain == lastAddedChain) {
            lastAdded = null;
            lastAddedChain = null;
        }
        Ap3Store store = Ap3Store.getInstance();
        boolean removed = store.remove(chain);
        store.markEdited();
        store.save();
        suppressAutoArm();
        if (removed) {
            chat(ModChat.text("Cleared "), ModChat.value(chain.label()));
        } else {
            chatBad(currentChainLabel() + " has no chain.");
        }
        return removed;
    }

    /** Persist after the tab edits a node's fields in place (box, wait, close, leap modifier, colour...). */
    public static void saveChains() {
        Ap3Store store = Ap3Store.getInstance();
        store.markEdited();
        store.save();
        suppressAutoArm();
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain edited");
        }
    }

    /** {@link Ap3Store#reload()} swapped the chains: drop anything pointing at the old objects. */
    static void onChainsReloaded() {
        lastAdded = null;
        lastAddedChain = null;
        migrationReported = false;
        suppressAutoArm();
    }

    // ------------------------------------------------------------------------------------------- node capture

    private static Ap3Node captureNode(Ap3Node.Type type, boolean precise) {
        Ap3Config cfg = Ap3Config.getInstance();
        if (!cfg.isEnabled()) {
            chatBad("AP3 is off (cheat build + Skyblock only).");
            return null;
        }
        if (type == null) {
            chatBad("Unknown node type.");
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return null;
        }
        if (currentArea() == null) {
            chatBad(noAreaReason());
            return null;
        }
        Vec3 pos = player.position();
        // Stored yaw is DATA (wrapped for readability in the file); it is never written back to the player.
        Ap3Node node = new Ap3Node(type,
                precise ? pos.x : Ap3Node.snapCentre(pos.x), Ap3Node.snapY(pos.y), precise ? pos.z : Ap3Node.snapCentre(pos.z),
                Mth.wrapDegrees(Ap3FreezeState.placementYaw(player)), Mth.clamp(Ap3FreezeState.placementPitch(player), -90f, 90f));
        node.precise = precise;
        node.setWidth(cfg.getDefaultNodeSize());
        node.setLength(cfg.getDefaultNodeSize());
        if (type == Ap3Node.Type.AXIS_ALIGN) {
            // killer560: "Cannot be placed unless you are actually touching a wall. Must be very precise." - the
            // wall is what makes that axis exact, so there has to be one to lean on.
            node.wallDir = Ap3Executor.touchingWall(client.level, player);
            if (node.wallDir == null) {
                chatBad("You are not touching a wall - walk into one, then /ap3 add axisalign (or use align).");
                return null;
            }
        }
        return node;
    }

    private static boolean commitNode(Ap3Node node) {
        Ap3Area area = currentArea();
        if (area == null) {
            return false;
        }
        Ap3Store store = Ap3Store.getInstance();
        Ap3Chain chain = store.forAreaOrCreate(area, Ap3Config.getInstance().getEditClassFilter());
        if (chain.nodes().size() >= Ap3Store.MAX_NODES) {
            chatBad(chain.label() + " already has " + Ap3Store.MAX_NODES + " nodes.");
            return false;
        }
        chain.nodes().add(node);
        lastAdded = node;
        lastAddedChain = chain;
        store.save();
        suppressAutoArm(); // you are standing on the node you just placed - it must not fire until you re-enter
        switch (Ap3Config.getInstance().getMessageDetail()) {
            case DETAILED -> chat(ModChat.text("Added "), ModChat.value("#" + chain.nodes().size() + " " + node.describe()),
                    ModChat.dim(" to " + chain.label()));
            case SIMPLE -> chat(ModChat.text("Added "), ModChat.value("#" + chain.nodes().size() + " " + node.type.label()));
            default -> {
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        // Frozen: AP3's time stands still with the character and moves on only with it - one executor tick per
        // predicted forward step, the node, queue and held walk all carrying on as if nothing stopped (killer560,
        // 2026-09-21: "when I freeze it should continue reading the AP3 as though it hasn't stopped").
        if (Ap3FreezeState.isFrozen() && !Ap3FreezeState.takeExecutorTick()) {
            return;
        }
        try {
            tickInner(client);
        } catch (Exception e) {
            LOGGER.error("[AP3] Tick error - disabling AP3", e);
            disableAfterError("tick error (see log)");
        } finally {
            // Always last, and always - the server-side yaw's glide back onto the camera yaw has to finish even
            // after AP3 was turned off, the boss was left, or the tick above threw.
            try {
                Ap3Executor.tickStrafe(client);
            } catch (Exception e) {
                LOGGER.error("[AP3] Strafe tick error", e);
            }
            try {
                Ap3Executor.tickView(client);
            } catch (Exception e) {
                LOGGER.error("[AP3] View tick error", e);
            }
        }
    }

    private static void tickInner(Minecraft client) {
        Ap3Config cfg = Ap3Config.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetForWorld("world change");
            Ap3Executor.resetStopwatch();
            // Force Dungeon never survives a world change (nor a restart - it is not saved): a test switch that was
            // left on cannot follow him into a real run.
            setForceDungeon(false);
        }
        if (!cfg.isEnabled()) {
            if (Ap3Executor.isRunning() || Ap3Executor.isArmed()) {
                resetForWorld("AP3 turned off");
            }
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }
        reportMigrationOnce();
        boolean live = isBossLive();
        if (!live) {
            if (wasLive) {
                // Leaving the boss (died to the lobby, disconnect, warped out...) ends everything.
                resetForWorld("left the boss");
            }
            wasLive = false;
            lastArea = null;
            return;
        }
        wasLive = true;
        int restarts = Floor7Tracker.simRestartCount();
        if (restarts != lastSimRestart) {
            // killer560: on a p3sim restart a running chain must STOP - "a restart means the fight reset, so
            // continuing is wrong". The phase stays live (the tracker re-arms P3), so this is the only signal.
            lastSimRestart = restarts;
            if (Ap3Executor.isRunning()) {
                String why = "p3sim restarted - the fight reset";
                Ap3Executor.stop(why);
                if (!cfg.isChatFeedback()) {
                    chat(ModChat.bad("Stopped"), ModChat.dim(" - " + why)); // always said, even with feedback off
                }
            }
        }
        // killer560 (2026-09-20): "/ap3 start should not exist. If i ever walk into a node it should always fire" and
        // "node one, then node two, then node 4, then node 18, they should all fire ... The order never matters."
        // So every tick in the boss the executor gets the node set of the area you stand in and arms all of it;
        // walking into any node's box fires that node. Which nodes: the class chain for the class you play, else
        // the class-less one (Ap3Store.forArea) - the same choice the old start made.
        Ap3Area area = currentArea();
        boolean arrival = area == null ? lastArea != null : !area.equals(lastArea);
        lastArea = area;
        Ap3Chain nodes = area == null ? null : Ap3Store.getInstance().forArea(area, selfClass());
        Ap3Executor.tick(client, nodes, arrival);
    }

    /** After a placement / edit / reload: whatever box you stand in counts as already entered, so adding a node at
     *  your feet does not instantly fire it - leave the box and walk back in. Also drops anything queued. */
    static void suppressAutoArm() {
        Ap3Executor.resync();
    }

    /** The version-1 file migration, said once in chat so a renamed / merged / dropped node is never a surprise. */
    private static void reportMigrationOnce() {
        if (migrationReported) {
            return;
        }
        migrationReported = true;
        List<String> notes = Ap3Store.getInstance().consumeMigrationNotes();
        if (notes.isEmpty()) {
            return;
        }
        chat(ModChat.text("Chains file updated to the new node set:"));
        for (String note : notes) {
            chat(ModChat.dim("- " + note));
        }
        chat(ModChat.dim("It is saved in the new form the next time a node changes."));
    }

    private static void onRenderFrame(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext ctx) {
        if (renderFailed) {
            return;
        }
        try {
            Ap3Config cfg = Ap3Config.getInstance();
            if (!cfg.isEnabled()) {
                return;
            }
            if (!Ap3FreezeState.isFrozen()) {
                Ap3Executor.tickFrame(); // a LOOK's per-frame turn waits while time is frozen
            }
            if (!isBossLive()) {
                return;
            }
            Ap3Chain chain = Ap3Executor.armedChain();
            if (chain == null) {
                chain = currentChain();
                if (chain == null) {
                    chain = Ap3Store.getInstance().forArea(currentArea(), selfClass());
                }
            }
            if (chain == null) {
                return;
            }
            Ap3Renderer.render(ctx, chain, Ap3Executor.activeNode());
        } catch (Exception e) {
            renderFailed = true;
            LOGGER.error("[AP3] Render error - disabling AP3", e);
            disableAfterError("render error (see log)");
        }
    }

    /** TERMINAL nodes advance only on Hypixel's own completion line naming YOU - never on a GUI close. Also the
     *  boom confirmation and the "you leapt" line. */
    private static void onChat(Component message) {
        try {
            if (!Ap3Executor.isRunning()) {
                return;
            }
            String plain = ChatObserver.strip(message);
            // killer560: leaping "drops all movement ... and then no movement on the other side unless it hits a
            // node" - "also ... if I am using auto leap and auto leap goes off during it even if it isnt a node".
            // Whoever caused the leap (a node, Fast Leap / Auto Leap, a manual click), Hypixel prints this line;
            // the executor's own-position jump check catches it too, so p3sim is covered without the line.
            if (SELF_LEAP.matcher(plain).matches()) {
                Ap3Executor.onLeapHappened("you leapt");
                return;
            }
            // Boom nodes confirm off Hypixel's own gate line (a Superboom's block change can arrive late / outside
            // the sampled cube) - killer560 (2026-09-20): "it says the superboom didnt break anything even though
            // 'The gate has been destroyed!' came through."
            if (GATE_DESTROYED.equals(plain)) {
                Ap3Executor.onGateDestroyed();
                return;
            }
            Matcher m = TERM_COMPLETED.matcher(plain);
            if (!m.matches()) {
                return;
            }
            if (m.group(1).equalsIgnoreCase(Teammates.selfName())) {
                Ap3Executor.onSelfCompletedTerminal();
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------- stopwatch HUD

    /** The STOPWATCH node's optional HUD - the running time while one is going, the last time otherwise. */
    public static final HudElement STOPWATCH_HUD = new HudElement() {
        @Override
        public String id() {
            return "ap3_stopwatch";
        }

        @Override
        public String displayName() {
            return "AP3 Stopwatch";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 140;
        }

        @Override
        public int width() {
            return 90;
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public boolean isRelevantNow() {
            Ap3Config cfg = Ap3Config.getInstance();
            return cfg.isEnabled() && cfg.isStopwatchHud();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            Ap3Config cfg = Ap3Config.getInstance();
            boolean example = HudVisibility.menuOpen();
            if (!example && (!cfg.isEnabled() || !cfg.isStopwatchHud())) {
                return;
            }
            long running = Ap3Executor.stopwatchRunningMs();
            long last = Ap3Executor.lastStopwatchMs();
            String value;
            if (running >= 0) {
                value = Ap3Executor.formatStopwatch(running);
            } else if (last >= 0) {
                value = Ap3Executor.formatStopwatch(last);
            } else if (example) {
                value = "12.345s";
            } else {
                return;
            }
            var font = Minecraft.getInstance().font;
            String label = "Stopwatch: ";
            graphics.text(font, label, x + 1, y + 1, 0xFF000000 | ModChat.ORANGE, true);
            graphics.text(font, value, x + 1 + font.width(label), y + 1, running >= 0 ? 0xFFFFFFFF : (0xFF000000 | ModChat.GOOD), true);
        }
    };

    private static void drawStopwatchHud(GuiGraphicsExtractor graphics) {
        try {
            Minecraft client = Minecraft.getInstance();
            Ap3Config cfg = Ap3Config.getInstance();
            if (client.player == null || client.options.hideGui || HudVisibility.menuOpen() || !cfg.isEnabled() || !cfg.isStopwatchHud()) {
                return;
            }
            int[] pos = HudElementRegistry.resolvePosition(STOPWATCH_HUD);
            float scale = HudElementRegistry.resolveScale(STOPWATCH_HUD);
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(pos[0], pos[1]);
                graphics.pose().scale(scale, scale);
                STOPWATCH_HUD.render(graphics, 0, 0);
            } finally {
                graphics.pose().popMatrix();
            }
        } catch (RuntimeException e) {
            // never take the HUD frame down over a stopwatch
        }
    }

    /** The on-screen reminder while Force Dungeon is on - fixed top-centre, not a HUD-editor element, drawn even
     *  with the mod menu open, so it cannot be moved out of sight or forgotten. */
    private static void drawForceDungeonReminder(GuiGraphicsExtractor graphics) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (!forceDungeon || client.player == null || client.options.hideGui) {
                return;
            }
            var font = client.font;
            String text = "AP3 FORCE DUNGEON - " + (isForcedOnly() ? forcedArea.longLabel() + " (forced)" : "real boss");
            int x = client.getWindow().getGuiScaledWidth() / 2;
            graphics.centeredText(font, text, x, 4, 0xFF000000 | ModChat.BAD);
        } catch (RuntimeException e) {
            // never take the HUD frame down over a reminder
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static void resetForWorld(String reason) {
        Ap3Executor.disarm(reason);
        renderFailed = false;
        lastArea = null;
    }

    /** Never let a tick/render exception take the frame down: switch the feature off (persisted) and say so. */
    static void disableAfterError(String reason) {
        Ap3Config cfg = Ap3Config.getInstance();
        resetForWorld(reason);
        cfg.setEnabled(false);
        cfg.save();
        chat(ModChat.bad("Disabled"), ModChat.dim(" - " + reason));
    }

    static void chat(Component... parts) {
        ModChat.send(CHAT, parts);
    }

    static void chatBad(String text) {
        ModChat.send(CHAT, ModChat.bad(text));
    }

    /** {@code /ap3 list} body: one line per node, 1-based. */
    public static List<String> describeCurrentChain() {
        List<Ap3Node> nodes = currentChainNodes();
        List<String> out = new java.util.ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            out.add(String.format(Locale.US, "#%d %s", i + 1, nodes.get(i).describe()));
        }
        return out;
    }
}
