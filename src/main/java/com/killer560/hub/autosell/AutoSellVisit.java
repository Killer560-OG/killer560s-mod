package com.killer560.hub.autosell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Auto Sell visit to a sell screen: which stack to click next, whether the server has answered every click, and
 * why each stack left in the inventory was not sold. No Minecraft types, so it can be checked without a game.
 * <p>
 * <b>Clicks are tracked per SLOT, not per identity</b> (2026-10-20). The first version marked an identity handled
 * after one click, on the theory that one shift-click sells every item of that kind. The log disproves it: with
 * three Dreadlord Swords in the inventory, one visit sold "Epic Dreadlord Sword x1", the next "Sharp Dreadlord Sword
 * x1" and the third "Odd Dreadlord Sword x1"; Training Weights went "x2" then "x1". Hypixel sells the clicked stack
 * only, so every stack gets its own click. A slot counts as done while it still holds the identity clicked there.
 * <p>
 * The guard the identity set stood for (a false-positive screen where a shift-click MOVES a stack instead of selling
 * it, so it would be found and clicked again in its new slot) is kept by a per-identity cap: an identity is never
 * clicked more times than the most stacks of it ever seen in one scan.
 */
final class AutoSellVisit {

    /** Ticks after a click with no answer from the server before it is reported as timed out. */
    static final int ANSWER_TIMEOUT_TICKS = 20;
    /** Ticks without a menu update before the stacks left are judged. */
    static final int QUIET_TICKS = 2;

    record Stack(int slot, String identity, int count) {
    }

    interface Lists {
        boolean onSell(String identity);

        boolean onNever(String identity);
    }

    private static final class Click {
        final int slot;
        final String identity;
        final int count;
        final int stateId;
        final int tick;
        boolean answered;

        Click(int slot, String identity, int count, int stateId, int tick) {
            this.slot = slot;
            this.identity = identity;
            this.count = count;
            this.stateId = stateId;
            this.tick = tick;
        }
    }

    private final Set<String> listAtStart;
    private final Map<Integer, Click> clicks = new LinkedHashMap<>();
    private final Map<String, Integer> clicksPerIdentity = new HashMap<>();
    private final Map<String, Integer> mostSeen = new HashMap<>();
    private Integer lastStateId;
    private int lastStateChangeTick;
    private int lastClickTick;

    /** @param listAtStart the sell list when the visit began (upper case), to tell a mid-visit addition apart. */
    AutoSellVisit(Set<String> listAtStart) {
        this.listAtStart = Set.copyOf(listAtStart);
    }

    static boolean sellable(Lists lists, String identity) {
        return identity != null && !lists.onNever(identity) && lists.onSell(identity);
    }

    /** Whether this identity was not on the sell list when the visit began. */
    boolean addedMidVisit(String identity) {
        return identity != null && !listAtStart.contains(identity);
    }

    /** @return the next stack to click, reading the lists afresh, or null when nothing is left to click. */
    Stack next(List<Stack> stacks, Lists lists) {
        Map<String, Integer> seen = new HashMap<>();
        for (Stack s : stacks) {
            if (s.identity() != null) {
                seen.merge(s.identity(), 1, Integer::sum);
            }
        }
        seen.forEach((id, n) -> mostSeen.merge(id, n, Math::max));
        for (Stack s : stacks) {
            if (!sellable(lists, s.identity()) || doneAt(s)) {
                continue;
            }
            if (clicksPerIdentity.getOrDefault(s.identity(), 0) >= mostSeen.getOrDefault(s.identity(), 0)) {
                continue; // already clicked as many times as there were stacks: this is one that moved, not sold
            }
            return s;
        }
        return null;
    }

    void clicked(Stack s, int stateId, int tick) {
        clicks.put(s.slot(), new Click(s.slot(), s.identity(), s.count(), stateId, tick));
        clicksPerIdentity.merge(s.identity(), 1, Integer::sum);
        lastClickTick = tick;
    }

    int clickCount() {
        return clicks.size();
    }

    private boolean doneAt(Stack s) {
        Click c = clicks.get(s.slot());
        return c != null && c.identity.equals(s.identity());
    }

    /** Call every tick with the menu's state id: a change after a click means the server has answered it. */
    void observe(int stateId, int tick) {
        if (lastStateId == null || stateId != lastStateId) {
            lastStateId = stateId;
            lastStateChangeTick = tick;
        }
        for (Click c : clicks.values()) {
            if (stateId != c.stateId) {
                c.answered = true;
            }
        }
    }

    /**
     * True once every click has been answered (or has waited {@link #ANSWER_TIMEOUT_TICKS}) and the menu has been
     * quiet for {@link #QUIET_TICKS}: one server update can arrive for an earlier click after a later one went out,
     * so the stacks are judged only once updates have stopped coming. No click is sent while this is awaited.
     */
    boolean settled(int tick) {
        boolean timedOut = false;
        for (Click c : clicks.values()) {
            if (!c.answered) {
                if (tick - c.tick < ANSWER_TIMEOUT_TICKS) {
                    return false;
                }
                timedOut = true;
            }
        }
        return clicks.isEmpty() || timedOut || tick - lastStateChangeTick >= QUIET_TICKS
                || tick - lastClickTick >= ANSWER_TIMEOUT_TICKS; // a menu that never goes quiet
    }

    /**
     * One line per stack left in the inventory that the visit did not sell, with the reason; also counts what sold.
     * Stacks of an identity on neither list are folded into one "not on the sell list" line, so the log names them
     * without a line each for every unrelated item.
     */
    List<String> report(List<Stack> stacks, Lists lists, int tick) {
        List<String> lines = new ArrayList<>();
        Map<Integer, Stack> bySlot = new HashMap<>();
        for (Stack s : stacks) {
            bySlot.put(s.slot(), s);
        }
        int sold = 0;
        for (Click c : clicks.values()) {
            Stack now = bySlot.get(c.slot);
            boolean stillThere = now != null && c.identity.equals(now.identity()) && now.count() >= c.count;
            if (!stillThere) {
                sold++;
            } else if (c.answered) {
                lines.add("slot " + c.slot + " " + c.identity + " x" + c.count + ": refused - clicked, the server answered and the stack is still there");
            } else {
                lines.add("slot " + c.slot + " " + c.identity + " x" + c.count + ": timed out - clicked, no answer from the server in "
                        + Math.max(0, tick - c.tick) + " ticks");
            }
        }
        List<String> notListed = new ArrayList<>();
        for (Stack s : stacks) {
            if (clicks.containsKey(s.slot()) && clicks.get(s.slot()).identity.equals(s.identity())) {
                continue; // reported above
            }
            if (s.identity() == null) {
                lines.add("slot " + s.slot() + ": not clicked - no identity could be read from the item");
            } else if (lists.onNever(s.identity())) {
                lines.add("slot " + s.slot() + " " + s.identity() + " x" + s.count() + ": not clicked - on the never-sell list"
                        + (lists.onSell(s.identity()) ? " (also on the sell list; never-sell wins)" : ""));
            } else if (!lists.onSell(s.identity())) {
                notListed.add(s.identity() + "@" + s.slot());
            } else {
                lines.add("slot " + s.slot() + " " + s.identity() + " x" + s.count()
                        + ": not clicked - on the sell list, but " + s.identity() + " was already clicked "
                        + clicksPerIdentity.getOrDefault(s.identity(), 0) + " time(s), as many as stacks were seen (a moved stack, not a sold one)");
            }
        }
        if (!notListed.isEmpty()) {
            lines.add(notListed.size() + " stack(s) not on the sell list: " + String.join(", ", notListed));
        }
        lines.add(0, "visit result: " + clicks.size() + " clicked, " + sold + " sold");
        return lines;
    }
}
