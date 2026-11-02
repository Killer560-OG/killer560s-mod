package com.killer560.hub.terminals;

import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.ActionGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** Hover Terminals - per killer560's own request (2026-09-16): "make hover terms. It will go in the
 *  cheat version and if you hover a button that needs to be pressed it'll press it. Add a delay option
 *  as well in ms." While a Floor 7 terminal is open, simply resting the mouse over a slot the Terminal
 *  Solver already says is correct sends that slot's click for you (at most one click per Hover Delay), so a run
 *  becomes "sweep the mouse over the right slots" instead of clicking each one.
 *  <p>
 *  This is a REAL macro (it sends clicks you never made), so like Auto Terminals it is gated three ways:
 *  {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} (checked inside
 *  {@link TerminalSolverConfig#isHoverTerminalsEnabled()}), {@link com.killer560.hub.util.SkyblockGate}
 *  and its own toggle, which ships OFF.
 *  <p>
 *  It deliberately owns NO solving logic of its own: it is driven entirely by the highlights
 *  {@link TerminalSolverFeature} already computes every frame, through the very same
 *  {@link TerminalSolverFeature#pickAutoClickTarget} decision Auto Terminals uses (so Numbers' strict
 *  click order and Rubix's left-vs-right click direction are honored here without a second copy of
 *  either rule), and sends its clicks through the same {@code SlotClickInvoker} path. The Terminal
 *  Solver itself therefore has to be ON for this to do anything - there are no highlights to hover
 *  otherwise.
 *  <p>
 *  <b>Precedence.</b> If Auto Terminals is enabled AND enabled for the terminal that is open, Hover
 *  Terminals stands completely down for that terminal ({@code autoClickingThisType} below): Auto
 *  Terminals is already clicking every slot this would, and a second click on the same slot is not a
 *  no-op - on Rubix it cycles the pane one step PAST the target color, and on Numbers it lands out of
 *  order. Auto wins because it is the strictly more complete automation and the user turned it on
 *  explicitly; hover adds nothing on top of it (its Block Input option, on by default, also swallows
 *  real mouse movement-driven input while it runs).
 *  <p>
 *  <b>Terminal Protection.</b> {@link TerminalQolFeature}'s opening window is honored through the
 *  non-consuming {@link TerminalQolFeature#withinProtectionWindow()} query - a hover click inside that
 *  window is simply not sent (and the dwell timer restarts), rather than being sent and then swallowed,
 *  which would waste Protection's one-shot on a click the player never made. */
public final class HoverTerminalFeature {

    /** Melody only - the same 250ms same-row guard {@code TerminalSolverFeature}'s own Melody auto-click
     *  uses (ported from NoammAddons' {@code lastClickedSlot} + cooldown pair), since a real column match
     *  stays true for several consecutive frames before the indicator moves on. */
    private static final long MELODY_SAME_ROW_GUARD_MS = 250;

    private static final Random RANDOM = new Random();

    /** The terminal-grid slot the mouse is over, or -1. */
    private static int hoveredSlot = -1;
    /** When the last hover click went out (0 = none yet this terminal). The Hover Delay is the least time between two
     *  hover clicks, NOT a dwell (killer560, 2026-11-01: "It should be how long between clicks it should have to wait not
     *  how long i have to be on the item for it to click"): a correct slot under the mouse clicks as soon as that much
     *  time has passed since the previous hover click, the first one at once. */
    private static long lastHoverClickAtMs;
    /** Melody only: when the pointer arrived on the button it rests on. */
    private static long hoverStartedAtMs;
    /** Melody's least rest on a row button before it may click, whatever the delay (0 included). */
    private static final long MELODY_MIN_REST_MS = 100;
    /** Rolled once per click (see {@link #rollDelayMs}), so the jitter varies click to click. */
    private static int armedDelayMs;
    /** Melody's once-per-row guard, shared with Hold To Click: the button last clicked, when, and where the moving pane
     *  was then. The same button is not clicked again while the pane has not moved off that slot (a click's answer can
     *  take longer than any fixed guard on a slow connection) or within {@link #MELODY_SAME_ROW_GUARD_MS}. */
    private static int lastMelodyClickedSlot = -1;
    private static long lastMelodyClickAtMs;
    private static int lastMelodyClickLimeSlot = -1;

    private HoverTerminalFeature() {
    }

    /** Called from {@code TerminalSolverFeature#resetAutoClickState()} - i.e. on every new terminal, every
     *  quick reopen, and when tracking ends (screen closed). A dwell can never survive the terminal it
     *  was started in. */
    static void reset() {
        hoveredSlot = -1;
        hoverStartedAtMs = 0;
        lastHoverClickAtMs = 0;
        armedDelayMs = 0;
        lastMelodyClickedSlot = -1;
        lastMelodyClickAtMs = 0;
        lastMelodyClickLimeSlot = -1;
    }

    /** @return whether a Melody click on {@code slot} now would be a second click for the same pass of the pane. */
    static boolean melodyGuardBlocks(int slot, long now) {
        return slot == lastMelodyClickedSlot && (now - lastMelodyClickAtMs < MELODY_SAME_ROW_GUARD_MS
                || TerminalSolverFeature.melodyLastLimeSlot == lastMelodyClickLimeSlot);
    }

    /** Records a Melody click on {@code slot} (hover, hold, or his own press under Hold To Click). */
    static void noteMelodyClick(int slot, long now) {
        lastMelodyClickedSlot = slot;
        lastMelodyClickAtMs = now;
        lastMelodyClickLimeSlot = TerminalSolverFeature.melodyLastLimeSlot;
    }

    /** One frame of hover handling. Called from {@link TerminalSolverFeature#refreshState()} only after
     *  that class's own content-stability + initial-settle gates have passed, so this can never click
     *  into a terminal whose real items are still arriving (or into Hypixel's real double-open window).
     *
     *  @param highlights            the solver's live highlight map for this frame - the only source of
     *                               "this slot needs pressing" this class has.
     *  @param autoClickingThisType  true when Auto Terminals is running for this terminal; see the class
     *                               doc's precedence note. */
    static void tick(ContainerScreen screen, TerminalType type, List<ItemStack> items,
                     Map<Integer, TerminalSolverFeature.SlotHighlight> highlights, boolean autoClickingThisType) {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        if (!cfg.isHoverTerminalsEnabled() || autoClickingThisType) {
            reset();
            return;
        }
        if (TerminalQolFeature.withinProtectionWindow()) {
            // Still inside Terminal Protection's opening window - don't even start a dwell, so the first
            // click can't land the instant the window closes off a hover that began before it.
            reset();
            return;
        }

        long now = System.currentTimeMillis();
        int slot = resolveHoveredSlot(screen);
        if (slot != hoveredSlot) {
            hoveredSlot = slot;
            hoverStartedAtMs = now;
        }
        if (slot < 0) {
            return;
        }

        if (type == TerminalType.MELODY) {
            // Melody keeps the rest on the button as its arming (the delay, then the click fires on the row's match): a
            // click the instant the pointer reached the next row's button read a board the last click had not finished
            // changing and was wrong (testkit 271 on 26.2, doc-terms-1101: row 3 judged wrong 2 ticks after the move).
            if (now - hoverStartedAtMs < Math.max(MELODY_MIN_REST_MS, cfg.getHoverDelayMs())) {
                return;
            }
            tickMelody(screen, items, slot, now, cfg);
            return;
        }
        if (lastHoverClickAtMs != 0 && now - lastHoverClickAtMs < armedDelayMs) {
            return;
        }

        TerminalSolverFeature.SlotHighlight highlight = highlights.get(slot);
        if (highlight == null) {
            return;
        }
        // Single-entry map on purpose: this asks "is THIS slot clickable right now?" rather than "what
        // would you click next?". For Numbers that means a hovered slot which isn't the next one in
        // order returns null (its primary() check fails) and nothing is sent - exactly the ordered-type
        // rule asked for. For Rubix it returns the real signed direction the highlight's own label
        // encodes, so a hover sends the same left/right click a manual click on it would.
        TerminalSolverFeature.AutoClickTarget target =
                TerminalSolverFeature.pickAutoClickTarget(type, Map.of(slot, highlight));
        if (target == null) {
            return;
        }
        // Dedupe: one click per slot in flight, ever. A slot already sent and not yet confirmed by the
        // server is never clicked again (Panes would toggle straight back to wrong), and once it IS
        // confirmed the solver simply stops highlighting it, so a solved slot can't be re-clicked either.
        // Rubix is the one type where the same slot legitimately needs several clicks - it stays
        // highlighted with a lower remaining count after each confirm, and the dwell restart below paces
        // those at the configured delay instead of one per frame.
        if (TerminalSolverFeature.isHoverClickBlocked(slot, now)) {
            return;
        }

        // Mod-wide one-interaction-per-tick gate. This runs per RENDER FRAME, so at high FPS the dwell can
        // come due on several frames inside one client tick; the gate is checked before the click is
        // registered and before the dwell is re-armed, so a denied frame changes nothing and the same hover
        // simply clicks on a later tick.
        if (!ActionGate.tryAct(ActionGate.Actor.TERMINAL_SOLVER, screen)) {
            return;
        }
        ItemStack snapshot = slot < items.size() ? items.get(slot).copy() : ItemStack.EMPTY;
        TerminalSolverFeature.registerHoverClick(slot, snapshot, now);
        TerminalSolverFeature.sendTerminalClick(screen, target.slot(), target.button(), target.clickType());
        lastHoverClickAtMs = now;
        armedDelayMs = rollDelayMs(cfg);
        hoverClicks++;
    }

    /** Melody has no solved/correct set at all (see {@link TerminalSolverFeature#solve}), so "a button
     *  that needs to be pressed" there is purely a timing question: the row button is correct only while
     *  its own moving indicator is lined up with the target column. The dwell therefore ARMS the button
     *  (hover it for the configured delay) and the click then fires on the first frame that row actually
     *  lines up - never on a timer, which would miss by definition. */
    private static void tickMelody(ContainerScreen screen, List<ItemStack> items, int slot, long now,
                                   TerminalSolverConfig cfg) {
        if (!cfg.isHoverMelodyEnabled()) {
            return;
        }
        if (TerminalSolverFeature.melodyMatchedButtonSlot(items) != slot) {
            return;
        }
        if (melodyGuardBlocks(slot, now)) {
            return;
        }
        // Gate before the same-row guard fields move - a denied frame must not consume this row's one
        // allowed click, or the indicator would move past the match while the guard says "already clicked".
        if (!ActionGate.tryAct(ActionGate.Actor.TERMINAL_SOLVER, screen)) {
            return;
        }
        noteMelodyClick(slot, now);
        TerminalSolverFeature.sendTerminalClick(screen, slot, 0, ContainerInput.CLONE);
        lastHoverClickAtMs = now;
        armedDelayMs = rollDelayMs(cfg);
        hoverClicks++;
    }

    /** Hover clicks sent since launch (testkit 1101-menu-term-hover-gap). */
    static int hoverClicks;

    /** @return the terminal-grid slot index under the mouse, or -1.
     *  <p>
     *  Two real cases. With Custom GUI mode OFF the vanilla slots are the ones on screen, so this reads
     *  {@code AbstractContainerScreen.hoveredSlot} through the existing {@code slotbinds}
     *  {@link AbstractContainerScreenAccessor} (a read-only accessor mixin this mod already ships - no
     *  second copy added). With Custom GUI mode ON the real slots are hidden and redrawn as a scaled
     *  panel somewhere else entirely, so {@code hoveredSlot} points at where the invisible vanilla slot
     *  is, not at the cell the player can actually see - the panel's own hit-test
     *  ({@link TerminalSolverFeature#customGuiSlotAt}, the exact math its real click redirect uses) is
     *  the only correct answer there.
     *  <p>
     *  Player-inventory slots are excluded by the {@code currentTerminalSlotCount} bound:
     *  {@code Slot.index} is assigned by {@code AbstractContainerMenu.addSlot} as the slot's position in
     *  {@code menu.slots} (verified via javap), so the terminal grid is exactly indices
     *  {@code [0, terminalSlotCount)} and the player's own 36 slots are all above it. */
    static int resolveHoveredSlot(AbstractContainerScreen<?> screen) {
        int terminalSlots = TerminalSolverFeature.getCurrentTerminalSlotCount();
        if (terminalSlots <= 0) {
            return -1;
        }
        int index;
        if (TerminalSolverFeature.isCustomGuiActive()) {
            Minecraft client = Minecraft.getInstance();
            if (client.getWindow() == null) {
                return -1;
            }
            index = TerminalSolverFeature.customGuiSlotAt(screen,
                    client.mouseHandler.getScaledXPos(client.getWindow()),
                    client.mouseHandler.getScaledYPos(client.getWindow()));
        } else {
            Slot hovered = ((AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();
            index = hovered == null ? -1 : hovered.index;
        }
        return index >= 0 && index < terminalSlots ? index : -1;
    }

    /** @return the gap before the next hover click: the configured delay plus a fresh random 0..jitter ms. Jitter
     *  defaults to 0 (pure, predictable delay); it exists because a perfectly constant hover-to-click gap
     *  is a trivially detectable signature, and is cheap here since the dwell is already re-armed on
     *  every hover change. */
    private static int rollDelayMs(TerminalSolverConfig cfg) {
        int jitter = cfg.getHoverJitterMs();
        return cfg.getHoverDelayMs() + (jitter <= 0 ? 0 : RANDOM.nextInt(jitter + 1));
    }
}
