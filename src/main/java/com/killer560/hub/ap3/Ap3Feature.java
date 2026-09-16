package com.killer560.hub.ap3;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.fastleap.Floor7Tracker.Phase;
import com.killer560.hub.fastleap.Floor7Tracker.Stage;
import com.killer560.hub.fastleap.Teammates;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * AP3 - hand-placed node chains that move you through F7/M7 Phase 3 terminal sections. CHEAT BUILD ONLY, default
 * off; every entry point is behind {@link Ap3Config#isEnabled()}.
 * <p>
 * <b>BOSS ONLY.</b> killer560: "It should only work in the boss stages, not in regular clear." Everything here is
 * gated on {@link #isP3Live()} - {@code Floor7Tracker.inF7Boss()} plus Phase 3. Auto Routes is the opposite (clear
 * only, never boss), so the two features can never be live at the same time; that mutual exclusion is what makes
 * two separate {@code KeyboardInput#tick} mixins safe (see {@code mixin/Ap3InputMixin}).
 * <p>
 * This class owns registration, the tick wiring, the gate, world / phase change handling, edit mode, the chat hook
 * that feeds TERMINAL nodes, and the public API the commands / keybinds / tab code against. A tick or render
 * exception never escapes: it is logged, the feature switches itself off (saved) and says so in chat.
 */
public final class Ap3Feature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");
    private static final String CHAT = "AP3";
    /** Hypixel's own line, the same regex {@code Floor7Tracker.Stage} parses (kept private there), with the optional
     *  trailing suffix so a line annotated by another mod (Odin's terminal splits) still matches. */
    private static final Pattern TERM_COMPLETED =
            Pattern.compile("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    /** A block further than this (squared) from the breaker node is refused (QUOI DB editor). */
    private static final double EDIT_MAX_DIST_SQ = 30.0;

    private static boolean editMode;
    /** The BREAKER node {@code /ap3 edit db} right-clicks add blocks to (chosen when edit mode turns on). */
    private static Ap3Node editBreakerNode;
    private static Object lastLevel;
    private static boolean wasLive;
    private static boolean renderFailed;
    private static int lastSection;

    private Ap3Feature() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see API.md). */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Ap3Feature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(Ap3Feature::onRenderFrame);
        ChatObserver.subscribe(Ap3Feature::onChat);
        LOGGER.info("[AP3] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    // ------------------------------------------------------------------------------------------- gate

    /**
     * The BOSS-ONLY + P3 gate. Chat-driven phase first ({@code Floor7Tracker.inPhase(P3)}); when no boss dialogue
     * has been seen at all this world ({@code getPhase() == UNKNOWN} - p3sim.net can skip Goldor's line) the
     * position-based phase is accepted instead. Never while chat says any OTHER phase.
     */
    public static boolean isP3Live() {
        if (!Floor7Tracker.inF7Boss()) {
            return false;
        }
        if (Floor7Tracker.inPhase(Phase.P3)) {
            return true;
        }
        return Floor7Tracker.getPhase() == Phase.UNKNOWN && Floor7Tracker.getPhaseAt() == Phase.P3;
    }

    /** The P3 section you are standing in (1-5), position first, else the chat-tracked stage; 0 when unknown. */
    public static int currentSectionNumber() {
        if (!isP3Live()) {
            return 0;
        }
        Stage at = Floor7Tracker.getStageAt();
        if (at == Stage.UNKNOWN) {
            at = Floor7Tracker.getStage();
        }
        return at.number;
    }

    /** The class you are playing for chain selection: the mod-wide override for your own IGN, else the tab list. */
    public static DungeonClass selfClass() {
        return ClassOverrides.classOf(Teammates.selfName(), PartyTracker.selfClass());
    }

    // ------------------------------------------------------------------------------------------- public API

    public static void setEditMode(boolean on) {
        if (on && !Ap3Config.getInstance().isEnabled()) {
            chatBad("AP3 is off (cheat build + Skyblock only).");
            return;
        }
        if (on == editMode) {
            if (on) {
                pickEditBreakerNode();
            }
            return;
        }
        editMode = on;
        if (on) {
            if (Ap3Executor.isRunning()) {
                Ap3Executor.stop("edit mode");
            }
            pickEditBreakerNode();
            chat(editBreakerNode == null
                            ? ModChat.bad("No breaker node in this section")
                            : ModChat.text("Editing breaker "),
                    editBreakerNode == null
                            ? ModChat.dim(" - add one with /ap3 add breaker")
                            : ModChat.value("#" + breakerIndex()));
        } else {
            editBreakerNode = null;
            Ap3EditInput.reset();
        }
    }

    public static boolean isEditMode() {
        return editMode;
    }

    /**
     * Edit-mode right click on a block ({@link Ap3EditInput}'s {@code UseBlockCallback}): adds it to the breaker node
     * being edited, shift removes it. @return true when consumed - the caller then suppresses the real interaction.
     */
    public static boolean onEditRightClick(BlockPos pos, boolean shift) {
        if (!editMode || pos == null || !Ap3Config.getInstance().isEnabled()) {
            return false;
        }
        if (editBreakerNode == null) {
            pickEditBreakerNode();
            if (editBreakerNode == null) {
                chatBad("No breaker node in this section - /ap3 add breaker first.");
                return true;
            }
        }
        Ap3Node node = editBreakerNode;
        if (pos.distToCenterSqr(node.x, node.y + 1.6, node.z) > EDIT_MAX_DIST_SQ) {
            chatBad("Block is too far from breaker #" + breakerIndex() + ".");
            return true;
        }
        if (shift) {
            if (node.breakerBlocks.remove(pos)) {
                Ap3Store.getInstance().save();
                chat(ModChat.text("Removed "), ModChat.value(pos.toShortString()),
                        ModChat.dim(" from breaker #" + breakerIndex()));
            }
            return true;
        }
        if (node.breakerBlocks.contains(pos)) {
            return true;
        }
        if (node.breakerBlocks.size() >= Ap3Store.MAX_BREAKER_BLOCKS) {
            chatBad("Breaker #" + breakerIndex() + " already has " + Ap3Store.MAX_BREAKER_BLOCKS + " blocks.");
            return true;
        }
        node.breakerBlocks.add(pos);
        Ap3Store.getInstance().save();
        chat(ModChat.text("Added "), ModChat.value(pos.toShortString()),
                ModChat.dim(" to breaker #" + breakerIndex() + " (" + node.breakerBlocks.size() + ")"));
        return true;
    }

    /** The chain nodes are added to / listed from: the section you stand in + the tab's edit class filter. The
     *  live list, or an empty list when there is no such chain yet. */
    public static List<Ap3Node> currentChainNodes() {
        Ap3Chain chain = currentChain();
        return chain == null ? Collections.emptyList() : chain.nodes();
    }

    /** The chain being edited (section you stand in + edit class filter), or null when none exists yet. */
    public static Ap3Chain currentChain() {
        int section = currentSectionNumber();
        return section == 0 ? null : Ap3Store.getInstance().exact(section, Ap3Config.getInstance().getEditClassFilter());
    }

    /** Label for the tab / chat: {@code "S3"}, {@code "S3 (Mage)"}, or {@code "no section"}. */
    public static String currentChainLabel() {
        int section = currentSectionNumber();
        if (section == 0) {
            return "no section";
        }
        DungeonClass filter = Ap3Config.getInstance().getEditClassFilter();
        return "S" + section + (filter == null ? "" : " (" + filter.displayName() + ")");
    }

    /** {@code /ap3 add <type>}: a node of {@code type} at your snapped position and current look. */
    public static boolean addNode(Ap3Node.Type type) {
        return addNode(type, Ap3Node.DEFAULT_LENGTH, Ap3Node.DEFAULT_WIDTH);
    }

    /** As {@link #addNode(Ap3Node.Type)} with explicit length / width (LINE, AXIS_LINE, WALK, RUN). */
    public static boolean addNode(Ap3Node.Type type, double length, double width) {
        Ap3Node node = captureNode(type);
        if (node == null) {
            return false;
        }
        node.setLength(length);
        node.setWidth(width);
        if (type == Ap3Node.Type.WAIT) {
            node.setWaitMs(Ap3Config.getInstance().getDefaultWaitMs());
        }
        return commitNode(node);
    }

    /** {@code /ap3 add wait <ms>} - killer560: "add a wait modifier in milliseconds for this one." */
    public static boolean addWaitNode(int millis) {
        Ap3Node node = captureNode(Ap3Node.Type.WAIT);
        if (node == null) {
            return false;
        }
        node.setWaitMs(millis);
        return commitNode(node);
    }

    /** {@code /ap3 add leap [class <c> | ign <name>]}. Mode null/DEFAULT = Fast Leap's target for this section. */
    public static boolean addLeapNode(Ap3Node.LeapMode mode, DungeonClass clazz, String ign) {
        Ap3Node node = captureNode(Ap3Node.Type.LEAP);
        if (node == null) {
            return false;
        }
        node.leapMode = mode == null ? Ap3Node.LeapMode.DEFAULT : mode;
        node.leapClass = clazz;
        node.leapIgn = ign == null || ign.isBlank() ? null : ign.trim();
        if (node.leapMode == Ap3Node.LeapMode.CLASS && clazz == null) {
            chatBad("Leap class missing (mage / archer / berserk / tank / healer).");
            return false;
        }
        if (node.leapMode == Ap3Node.LeapMode.IGN && node.leapIgn == null) {
            chatBad("Leap IGN missing.");
            return false;
        }
        return commitNode(node);
    }

    /** {@code /ap3 add leapdetector <count>}. */
    public static boolean addLeapDetectorNode(int count) {
        Ap3Node node = captureNode(Ap3Node.Type.LEAP_DETECTOR);
        if (node == null) {
            return false;
        }
        node.setLeapCount(count);
        return commitNode(node);
    }

    /** Deletes node {@code index} (0-BASED here; show it to the player as {@code index + 1}). */
    public static boolean deleteNode(int index) {
        Ap3Chain chain = currentChain();
        if (chain == null || index < 0 || index >= chain.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in " + currentChainLabel() + ".");
            return false;
        }
        Ap3Node removed = chain.nodes().remove(index);
        if (removed == editBreakerNode) {
            editBreakerNode = null;
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
        chat(ModChat.text("Deleted "), ModChat.value("#" + (index + 1) + " " + removed.type.label()),
                ModChat.dim(" from " + chain.label()));
        return true;
    }

    /** Deletes the last node of the current chain (the {@code deleteLast} keybind). */
    public static boolean deleteLastNode() {
        List<Ap3Node> nodes = currentChainNodes();
        if (nodes.isEmpty()) {
            chatBad("No nodes in " + currentChainLabel() + ".");
            return false;
        }
        return deleteNode(nodes.size() - 1);
    }

    /** Removes the whole chain being edited. @return true when one existed. */
    public static boolean clearCurrentChain() {
        if (currentSectionNumber() == 0) {
            chatBad("Stand in a P3 section (S1-S5) first.");
            return false;
        }
        Ap3Chain chain = currentChain();
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain cleared");
        }
        editBreakerNode = null;
        Ap3Store store = Ap3Store.getInstance();
        boolean removed = store.remove(chain);
        store.markEdited();
        store.save();
        if (removed) {
            chat(ModChat.text("Cleared "), ModChat.value(chain.label()));
        } else {
            chatBad(currentChainLabel() + " has no chain.");
        }
        return removed;
    }

    /** Persist after the tab edits a node's fields in place (length, width, wait, leap modifier, colour...). */
    public static void saveChains() {
        Ap3Store store = Ap3Store.getInstance();
        store.markEdited();
        store.save();
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chain edited");
        }
    }

    /** {@link Ap3Store#reload()} swapped the chains: drop anything pointing at the old objects. */
    static void onChainsReloaded() {
        editBreakerNode = null;
        if (editMode) {
            pickEditBreakerNode();
        }
    }

    // ------------------------------------------------------------------------------------------- node capture

    private static Ap3Node captureNode(Ap3Node.Type type) {
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
        if (!isP3Live()) {
            chatBad("Not in F7/M7 Phase 3 - nodes are placed in the boss arena only.");
            return null;
        }
        int section = currentSectionNumber();
        if (section == 0) {
            chatBad("Stand in a P3 section (S1-S5) first.");
            return null;
        }
        Vec3 pos = player.position();
        // Stored yaw is DATA (wrapped for readability in the file); it is never written back to the player.
        Ap3Node node = new Ap3Node(type, Ap3Node.snapXZ(pos.x), Ap3Node.snapY(pos.y), Ap3Node.snapXZ(pos.z),
                Mth.wrapDegrees(player.getYRot()), Mth.clamp(player.getXRot(), -90f, 90f));
        if (type == Ap3Node.Type.AXIS_LINE) {
            Ap3Executor.measureWall(client.level, player, node);
            if (node.wallAxis == Ap3Node.WallAxis.NONE) {
                chatBad("No wall within 8 blocks in front / left / right - use a Line node here.");
                return null;
            }
        }
        return node;
    }

    private static boolean commitNode(Ap3Node node) {
        int section = currentSectionNumber();
        if (section == 0) {
            return false;
        }
        Ap3Store store = Ap3Store.getInstance();
        Ap3Chain chain = store.forSectionOrCreate(section, Ap3Config.getInstance().getEditClassFilter());
        if (chain.nodes().size() >= Ap3Store.MAX_NODES) {
            chatBad(chain.label() + " already has " + Ap3Store.MAX_NODES + " nodes.");
            return false;
        }
        chain.nodes().add(node);
        store.save();
        if (editMode && node.type == Ap3Node.Type.BREAKER) {
            editBreakerNode = node;
        }
        chat(ModChat.text("Added "), ModChat.value("#" + chain.nodes().size() + " " + node.describe()),
                ModChat.dim(" to " + chain.label()));
        return true;
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (Exception e) {
            LOGGER.error("[AP3] Tick error - disabling AP3", e);
            disableAfterError("tick error (see log)");
        }
    }

    private static void tickInner(Minecraft client) {
        Ap3Config cfg = Ap3Config.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetForWorld("world change");
        }
        if (!cfg.isEnabled()) {
            if (Ap3Executor.isRunning() || editMode) {
                resetForWorld("AP3 turned off");
            }
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }
        boolean live = isP3Live();
        if (!live) {
            if (wasLive) {
                // Leaving P3 (P4 started, died to the lobby, disconnect...) ends everything, edit mode included -
                // edit mode swallows every block right-click, and must not follow the player out of the arena.
                resetForWorld("left P3");
            }
            wasLive = false;
            lastSection = 0;
            return;
        }
        wasLive = true;
        int section = currentSectionNumber();
        if (section != lastSection) {
            lastSection = section;
            if (editMode) {
                pickEditBreakerNode();
            }
        }
        if (Ap3Executor.isRunning()) {
            Ap3Executor.tick(client);
            return;
        }
        int completed = Ap3Executor.consumeCompletedSection();
        if (completed != 0 && cfg.isContinueIntoNextSection() && !editMode && section != 0 && section != completed) {
            // Only after a chain ran to its END - never after the player stopped one (that must stay stopped) - and
            // only into a DIFFERENT section, so a chain that ends where it started can't restart itself.
            Ap3Chain next = Ap3Store.getInstance().forSection(section, selfClass());
            if (next != null && !next.isEmpty()) {
                Ap3Executor.start(next);
            }
        }
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
            Ap3Executor.tickFrame();
            if (!isP3Live()) {
                return;
            }
            Ap3Chain chain = Ap3Executor.runningChain();
            if (chain == null) {
                chain = currentChain();
                if (chain == null) {
                    int section = currentSectionNumber();
                    chain = section == 0 ? null : Ap3Store.getInstance().forSection(section, selfClass());
                }
            }
            if (chain == null) {
                return;
            }
            Ap3Renderer.render(ctx, chain, editMode, Ap3Executor.activeNode());
        } catch (Exception e) {
            renderFailed = true;
            LOGGER.error("[AP3] Render error - disabling AP3", e);
            disableAfterError("render error (see log)");
        }
    }

    /** TERMINAL nodes advance only on Hypixel's own completion line naming YOU - never on a GUI close. */
    private static void onChat(Component message) {
        try {
            if (!Ap3Executor.isRunning()) {
                return;
            }
            String plain = ChatObserver.strip(message);
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

    // ------------------------------------------------------------------------------------------- helpers

    private static void resetForWorld(String reason) {
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop(reason);
        }
        editMode = false;
        Ap3EditInput.reset();
        editBreakerNode = null;
        renderFailed = false;
        lastSection = 0;
    }

    /** Never let a tick/render exception take the frame down: switch the feature off (persisted) and say so. */
    static void disableAfterError(String reason) {
        Ap3Config cfg = Ap3Config.getInstance();
        resetForWorld(reason);
        cfg.setEnabled(false);
        cfg.save();
        chat(ModChat.bad("Disabled"), ModChat.dim(" - " + reason));
    }

    /** The breaker node edit mode targets: the nearest BREAKER node of the chain being edited. */
    private static void pickEditBreakerNode() {
        editBreakerNode = null;
        Minecraft client = Minecraft.getInstance();
        Ap3Chain chain = currentChain();
        if (chain == null || client.player == null) {
            return;
        }
        Vec3 pos = client.player.position();
        double best = Double.MAX_VALUE;
        for (Ap3Node node : chain.nodes()) {
            if (node.type != Ap3Node.Type.BREAKER) {
                continue;
            }
            double d = node.pos().distanceTo(pos);
            if (d < best) {
                best = d;
                editBreakerNode = node;
            }
        }
    }

    /** 1-BASED, to match /ap3 list, /ap3 delete, the world labels and the tab. */
    private static int breakerIndex() {
        Ap3Chain chain = currentChain();
        return chain == null || editBreakerNode == null ? -1 : chain.indexOf(editBreakerNode) + 1;
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
