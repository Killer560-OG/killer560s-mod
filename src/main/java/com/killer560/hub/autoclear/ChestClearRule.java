package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560's rule (2026-10-20): once Auto Clear or Auto Secret has grabbed a mob room's secret chest, that room counts as
 * cleared in their eyes. Not at once - the room's mobs still need a moment to show or die - but {@link #LEAVE_TICKS} after
 * the chest, so either may leave it then. If the map still does not show it cleared {@link #RETURN_TICKS} after the chest,
 * it stops counting as cleared and they may come back to it, once per room per run; after that only the map decides.
 * <p>
 * The chest is the one Auto Routes' run memory records ({@code RouteRunMemory.gotSecret}, a chest click of his while Auto
 * Clear or Auto Secret runs: a route node's, Secret Aura's under them, Auto Clear's). A run is the live map's reset
 * generation in one world, as for the run memory. Every decision is logged once. Client thread.
 */
public final class ChestClearRule {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoclear");

    /** "A few seconds" after the chest before the room counts as cleared (killer560: not immediately). */
    public static final int LEAVE_TICKS = 3 * 20;
    /** Still not cleared on the map this long after the chest: it may be come back to, once (killer560: 30 seconds). */
    public static final int RETURN_TICKS = 30 * 20;

    private enum State { WAITING, CLEARED, RETURNED }

    private static final class Entry {
        final String chest;
        final long tick;
        final long lag;
        State state = State.WAITING;

        Entry(String chest, long tick, long lag) {
            this.chest = chest;
            this.tick = tick;
            this.lag = lag;
        }
    }

    /** Room name -> its first chest grabbed under Auto Clear / Auto Secret this run. */
    private static final Map<String, Entry> rooms = new LinkedHashMap<>();
    /** Rooms whose come-back fell due and that Auto Secret has not been told about yet ({@link #drainReturns}). */
    private static final List<String> returnsDue = new ArrayList<>();
    private static Object level;
    private static int generation = Integer.MIN_VALUE;

    private ChestClearRule() {
    }

    private static void check() {
        Minecraft client = Minecraft.getInstance();
        int gen = LiveMapFeature.resetGeneration();
        if (client.level != level || gen != generation) {
            if (!rooms.isEmpty()) {
                LOGGER.info("[AutoClear] chest rule: new run - forgetting {} room(s)", rooms.size());
            }
            rooms.clear();
            returnsDue.clear();
            level = client.level;
            generation = gen;
        }
    }

    /** For the testkit: forget the run. */
    public static void reset() {
        rooms.clear();
        returnsDue.clear();
        level = Minecraft.getInstance().level;
        generation = LiveMapFeature.resetGeneration();
    }

    /**
     * A chest of his was just remembered this run ({@code RouteRunMemory.gotSecret}, once per chest). Starts the room's
     * clock when Auto Clear or Auto Secret is running and it is a mob room the map does not show cleared yet.
     */
    public static void noteChest(Vec3 pos, String what) {
        check();
        if (pos == null) {
            return;
        }
        boolean clear = AutoClearFeature.isBusy();
        boolean secret = com.killer560.hub.autosecret.AutoSecretFeature.isRunning();
        if (!clear && !secret) {
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int room = layout.roomAtWorld(pos.x, pos.z);
        String name = room < 0 ? null : layout.name(room);
        if (name == null || !AutoClearTargets.isMobRoom(layout, room)) {
            LOGGER.info("[AutoClear] chest rule: {} is not in a mob room ({}) - nothing to decide", what,
                    name == null ? "no room" : name);
            return;
        }
        if (AutoClearTargets.isClearedOnMap(layout, room)) {
            LOGGER.info("[AutoClear] chest rule: {} in {} - the map already shows the room cleared", what, name);
            return;
        }
        if (rooms.containsKey(name)) {
            LOGGER.info("[AutoClear] chest rule: {} in {} - its clock already runs from an earlier chest", what, name);
            return;
        }
        rooms.put(name, new Entry(what, ClearExecutor.clientTick(), com.killer560.hub.util.ServerLag.mark()));
        LOGGER.info("[AutoClear] chest rule: {} grabbed in {} under {} - the room counts as cleared in {} s (unless the map "
                        + "says so first); not cleared {} s after it, it may be come back to once", what, name,
                clear ? "Auto Clear" : "Auto Secret", LEAVE_TICKS / 20, RETURN_TICKS / 20);
    }

    /** {@link #clearedInItsEyes(DungeonLayout, int)} by name. */
    public static boolean clearedInItsEyes(String name) {
        DungeonLayout layout = DungeonLayout.capture();
        int room = AutoClearTargets.roomByName(layout, name);
        return room >= 0 && clearedInItsEyes(layout, room);
    }

    /**
     * Whether Auto Clear and Auto Secret treat {@code room} as cleared because of its chest (the map's own clear is not
     * asked here beyond the come-back): from {@link #LEAVE_TICKS} after the chest until {@link #RETURN_TICKS} after it.
     */
    public static boolean clearedInItsEyes(DungeonLayout layout, int room) {
        check();
        if (rooms.isEmpty() || room < 0) {
            return false;
        }
        String name = layout.name(room);
        Entry e = name == null ? null : rooms.get(name);
        if (e == null) {
            return false;
        }
        evaluate(layout, room, name, e);
        return e.state == State.CLEARED;
    }

    /**
     * Rooms whose come-back fell due since the last call (each at most once a run): Auto Secret drops them from the rooms
     * it has already sent Auto Clear to, so it may send it again. Evaluates every room first, so a come-back is not left
     * waiting for something to ask about that room.
     */
    public static List<String> drainReturns() {
        check();
        if (!rooms.isEmpty()) {
            DungeonLayout layout = DungeonLayout.capture();
            for (Map.Entry<String, Entry> me : rooms.entrySet()) {
                int room = AutoClearTargets.roomByName(layout, me.getKey());
                if (room >= 0) {
                    evaluate(layout, room, me.getKey(), me.getValue());
                }
            }
        }
        if (returnsDue.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(returnsDue);
        returnsDue.clear();
        return out;
    }

    private static void evaluate(DungeonLayout layout, int room, String name, Entry e) {
        if (e.state == State.RETURNED) {
            return;
        }
        long lag = com.killer560.hub.util.ServerLag.ticksSince(e.lag);
        long since = ClearExecutor.clientTick() - e.tick;
        if (since >= RETURN_TICKS + lag) {
            if (AutoClearTargets.isClearedOnMap(layout, room)) {
                return;   // the map says it now: the rule has nothing left to decide
            }
            e.state = State.RETURNED;
            returnsDue.add(name);
            LOGGER.info("[AutoClear] chest rule: {} still not cleared {} s after its chest ({}) - it no longer counts as "
                    + "cleared; Auto Clear / Auto Secret may come back to it (once this run)", name,
                    String.format(Locale.ROOT, "%.1f", since / 20.0), e.chest);
            return;
        }
        if (e.state == State.WAITING && since >= LEAVE_TICKS + lag) {
            e.state = State.CLEARED;
            LOGGER.info("[AutoClear] chest rule: {} counts as cleared now, {} s after its chest ({}) - map {}", name,
                    String.format(Locale.ROOT, "%.1f", since / 20.0), e.chest,
                    AutoClearTargets.isClearedOnMap(layout, room) ? "cleared" : "not cleared yet");
        }
    }

    /** One line for the floor dump: every room the rule holds and its state. */
    public static String describe() {
        check();
        StringBuilder sb = new StringBuilder();
        long now = ClearExecutor.clientTick();
        for (Map.Entry<String, Entry> me : rooms.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(me.getKey()).append(' ').append(me.getValue().state).append(' ')
                    .append(String.format(Locale.ROOT, "%.1fs", (now - me.getValue().tick) / 20.0));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
