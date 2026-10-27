package com.killer560.hub.terminals;

import com.killer560.hub.util.ActionGate;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Hold To Click (killer560, 2026-10-26: "there should be drop terms ... where if you hold left click it keeps spam
 * clicking like you are holding your drop key", then "make drop terms in the legit version not cheat"). Both builds.
 * <p>
 * While he holds left click inside an open terminal, the slot under HIS cursor is clicked again on a held-key rhythm:
 * nothing for the First Delay after the press (his own press is the first click), then at most one click every Repeat.
 * It never picks a slot: it only repeats on the one he is pointing at, and only when the Terminal Solver says that
 * slot is a correct click right now (the same single-slot question Hover Terminals asks, through
 * {@link TerminalSolverFeature#pickAutoClickTarget}), so Numbers stays in order, Rubix gets the direction its label
 * says, and a wrong slot is never sent - Hypixel resets a terminal on a wrong click. Melody follows the Hover Melody
 * rule: the row button under the cursor is clicked once, on the frame its moving pane is in the target column.
 * <p>
 * Pacing is Auto Terminals' and Hover Terminals': every click asks {@link ActionGate} for the tick's one interaction,
 * and a slot with a click still waiting on the server's answer ({@link TerminalSolverFeature#isHoverClickBlocked}) is
 * not clicked again, so a Panes pane can never be toggled back and a Rubix pane steps one confirmed click at a time.
 * His own press is entered in that same pending set, so the first repeat cannot double his click before the server
 * has answered it. Stands down when Auto Terminals is clicking this terminal.
 */
public final class HoldToClickFeature {

    private static boolean held;
    private static long nextRepeatAtMs;
    /** Left button down inside the open container screen, from its own press/release events. Not
     *  {@code MouseHandler.isLeftPressed()}: vanilla only updates that while no screen is open (javap 26.1.2,
     *  MouseHandler.onButton), so inside a terminal it never changes. */
    private static volatile boolean leftDown;
    private static Object leftDownScreen;

    private HoldToClickFeature() {
    }

    static void reset() {
        held = false;
        nextRepeatAtMs = 0;
    }

    /** Tracks the left button on every container screen (both builds). Fabric fires these from the mouse handler before
     *  the screen sees the click, so another feature canceling the click at the screen does not hide the press. */
    public static void register() {
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
                return;
            }
            leftDown = false;
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (event.button() == 0) {
                    leftDown = true;
                    leftDownScreen = s;
                }
                return true;
            });
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
                if (event.button() == 0) {
                    leftDown = false;
                }
                return true;
            });
        });
    }

    /** One frame, from {@link TerminalSolverFeature#refreshState} after its content-stability gates. */
    static void tick(ContainerScreen screen, TerminalType type, List<ItemStack> items,
                     Map<Integer, TerminalSolverFeature.SlotHighlight> highlights, boolean autoClickingThisType) {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        if (!cfg.isHoldToClickEnabled() || autoClickingThisType) {
            reset();
            return;
        }
        if (!leftDown || leftDownScreen != screen) {
            held = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (!held) {
            // The press itself: his own click has already gone out through the normal path (MouseHandler sets the
            // pressed flag and calls mouseClicked in one call, before this frame). Enter it as pending so the first
            // repeat waits for the server's answer to it.
            held = true;
            nextRepeatAtMs = now + cfg.getHoldToClickFirstDelayMs();
            int pressed = HoverTerminalFeature.resolveHoveredSlot(screen);
            if (pressed >= 0 && type != TerminalType.MELODY && highlights.containsKey(pressed)
                    && !TerminalSolverFeature.isHoverClickBlocked(pressed, now)) {
                TerminalSolverFeature.registerHoverClick(pressed, pressed < items.size() ? items.get(pressed).copy() : ItemStack.EMPTY, now);
            }
            if (pressed >= 0 && type == TerminalType.MELODY) {
                HoverTerminalFeature.noteMelodyClick(pressed, now);
            }
            return;
        }
        if (now < nextRepeatAtMs || TerminalQolFeature.withinProtectionWindow()) {
            return;
        }
        int slot = HoverTerminalFeature.resolveHoveredSlot(screen);
        if (slot < 0) {
            return;
        }
        if (type == TerminalType.MELODY) {
            if (TerminalSolverFeature.melodyMatchedButtonSlot(items) != slot || HoverTerminalFeature.melodyGuardBlocks(slot, now)) {
                return;
            }
            if (!ActionGate.tryAct(ActionGate.Actor.TERMINAL_SOLVER, screen)) {
                return;
            }
            HoverTerminalFeature.noteMelodyClick(slot, now);
            TerminalSolverFeature.sendTerminalClick(screen, slot, 0, ContainerInput.CLONE);
            nextRepeatAtMs = now + cfg.getHoldToClickRepeatMs();
            return;
        }
        TerminalSolverFeature.SlotHighlight highlight = highlights.get(slot);
        if (highlight == null) {
            return;
        }
        TerminalSolverFeature.AutoClickTarget target = TerminalSolverFeature.pickAutoClickTarget(type, Map.of(slot, highlight));
        if (target == null || TerminalSolverFeature.isHoverClickBlocked(slot, now)) {
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.TERMINAL_SOLVER, screen)) {
            return;
        }
        ItemStack snapshot = slot < items.size() ? items.get(slot).copy() : ItemStack.EMPTY;
        TerminalSolverFeature.registerHoverClick(slot, snapshot, now);
        if (TerminalSolverFeature.sendTerminalClick(screen, target.slot(), target.button(), target.clickType())) {
            clicks++;
        }
        // A missed tick (gate denied, slot pending) keeps the due time, so the click goes out the first frame it may.
        nextRepeatAtMs = now + cfg.getHoldToClickRepeatMs();
    }

    /** Repeat clicks sent since the game started. Read by the testkit (273/274); nothing in the mod reads it. */
    static int clicks;
}
