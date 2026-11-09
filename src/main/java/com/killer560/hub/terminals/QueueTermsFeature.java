package com.killer560.hub.terminals;

import com.killer560.hub.util.ActionGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Queue Terms (Nebulune, cheat build, default OFF): in Panes, Rubix, Numbers, Starts With and Select, a click of yours on a
 * right slot that comes while an earlier click still waits for the server's answer is held, and goes out one per update: the
 * first frame no click of ours is unanswered, through Auto Terminals' own pacing (the tick's one {@link ActionGate}
 * interaction, {@link TerminalSolverFeature#sendTerminalClick}, the shared pending-click set that confirms it). So fast
 * clicks never stack on a lagging server, and a Numbers click made one ahead waits until its number is the next. A click
 * on a slot the solver does not mark, or the first click with nothing pending, goes through untouched. Stands down while
 * Auto Terminals is clicking the terminal. The queue is at most {@link #MAX_QUEUE} long and is emptied with the terminal.
 */
public final class QueueTermsFeature {

    private static final int MAX_QUEUE = 8;
    private static final int[] queue = new int[MAX_QUEUE];
    private static int size;
    /** Clicks held and clicks sent from the queue since the game started (testkit). */
    private static boolean swallowRelease;
    static int held;
    static int sent;

    private QueueTermsFeature() {
    }

    static void reset() {
        size = 0;
    }

    /** From the container screen's mouse press (before it is handled). @return true when the click was held in the queue. */
    public static boolean intercept(AbstractContainerScreen<?> screen) {
        if (!TerminalSolverConfig.getInstance().isQueueTerms()) return false;
        TerminalType type = TerminalSolverFeature.currentType();
        if (type == null || type == TerminalType.MELODY || TerminalSolverFeature.autoClicking(type)) return false;
        int slot = HoverTerminalFeature.resolveHoveredSlot(screen);
        if (slot < 0 || !TerminalSolverFeature.highlights().containsKey(slot)) return false;
        long now = System.currentTimeMillis();
        if (size == 0 && !TerminalSolverFeature.hasUnansweredClick(now)) {
            // Goes out as it is; entered as pending so the next click waits for the answer to this one.
            var items = screen.getMenu().slots;
            TerminalSolverFeature.registerHoverClick(slot, slot < items.size() ? items.get(slot).getItem().copy() : ItemStack.EMPTY, now);
            return false;
        }
        if (size < MAX_QUEUE && (type == TerminalType.RUBIX || !contains(slot))) {
            queue[size++] = slot;
            held++;
        }
        swallowRelease = true;
        return true;
    }

    /** True once after a press this class swallowed: vanilla sends the click on RELEASE, so the release is swallowed too. */
    public static boolean consumeRelease() {
        boolean r = swallowRelease;
        swallowRelease = false;
        return r;
    }

    private static boolean contains(int slot) {
        for (int i = 0; i < size; i++) {
            if (queue[i] == slot) return true;
        }
        return false;
    }

    /** One frame, from {@link TerminalSolverFeature#refreshState} after its content-stability gates. */
    static void tick(ContainerScreen screen, TerminalType type, List<ItemStack> items,
                     Map<Integer, TerminalSolverFeature.SlotHighlight> highlights, boolean autoClickingThisType) {
        if (size == 0) return;
        if (autoClickingThisType || !TerminalSolverConfig.getInstance().isQueueTerms()) {
            size = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (TerminalSolverFeature.hasUnansweredClick(now) || TerminalQolFeature.withinProtectionWindow()) return;
        for (int i = 0; i < size; i++) {
            int slot = queue[i];
            TerminalSolverFeature.SlotHighlight highlight = highlights.get(slot);
            if (highlight == null) {
                remove(i--);
                continue;
            }
            TerminalSolverFeature.AutoClickTarget target = TerminalSolverFeature.pickAutoClickTarget(type, Map.of(slot, highlight));
            if (target == null) continue;
            if (!ActionGate.tryAct(ActionGate.Actor.TERMINAL_SOLVER, screen)) return;
            TerminalSolverFeature.registerHoverClick(slot, slot < items.size() ? items.get(slot).copy() : ItemStack.EMPTY, now);
            if (TerminalSolverFeature.sendTerminalClick(screen, slot, target.button(), target.clickType())) sent++;
            remove(i);
            return;
        }
    }

    private static void remove(int i) {
        System.arraycopy(queue, i + 1, queue, i, --size - i);
    }

    /** The slots waiting, oldest first (testkit). */
    static int[] queuedForTest() {
        return java.util.Arrays.copyOf(queue, size);
    }
}
