package com.killer560.hub.autoroutes;

import java.util.List;

/**
 * The rooms an automation run is going to do, in order, as its planner decided them (killer560, 2026-10-15: "If using
 * auto clear have a list of the rooms it is going to do in order with a total percent at the top"). Auto Clear,
 * Auto Secret and Dungeon Autopilot each publish the queue THEY work from, at the moment they pick (so this is the
 * planner's own ordering, never a second guess at it), and withdraw it when the run ends. {@link AutoRoutesProgressHud}
 * draws the highest-priority one that is live: Autopilot, then Auto Secret, then Auto Clear (Auto Secret and Autopilot
 * hand rooms to Auto Clear one at a time, and their queue is the bigger picture).
 * <p>
 * Client thread only.
 */
public final class RunPlan {

    /** Who is running. Priority order: the first live one wins. */
    public enum Source {
        AUTOPILOT("Autopilot"), AUTO_SECRET("Auto Secret"), AUTO_CLEAR("Auto Clear");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param rooms rooms still to do, in the order they will be done; the first is the one under way (or next)
     * @param tags  what is done in each room ("Clear", "Secret"...) where a planner mixes kinds, else "" - same size as rooms
     * @param done  rooms (for Autopilot, actions) finished this run
     */
    public record Snapshot(Source source, List<String> rooms, List<String> tags, int done) {

        /** Rooms finished plus rooms left. */
        public int total() {
            return done + rooms.size();
        }

        /** Whole-run progress, 0 to 100: finished rooms over all rooms the plan holds. */
        public int percent() {
            int t = total();
            return t <= 0 ? 100 : Math.max(0, Math.min(100, Math.round(done * 100f / t)));
        }
    }

    private static final Snapshot[] LIVE = new Snapshot[Source.values().length];

    private RunPlan() {
    }

    /** A planner picked: this is what is left, in order, and how many it has done. */
    public static void publish(Source source, List<String> rooms, int done) {
        publish(source, rooms, null, done);
    }

    /** As above, with what is done in each room ({@code tags} may be null). */
    public static void publish(Source source, List<String> rooms, List<String> tags, int done) {
        List<String> t = new java.util.ArrayList<>(rooms.size());
        for (int i = 0; i < rooms.size(); i++) {
            t.add(tags != null && i < tags.size() && tags.get(i) != null ? tags.get(i) : "");
        }
        LIVE[source.ordinal()] = new Snapshot(source, List.copyOf(rooms), List.copyOf(t), Math.max(0, done));
    }

    /** The run ended (or never began): nothing of {@code source}'s is planned. */
    public static void clear(Source source) {
        LIVE[source.ordinal()] = null;
    }

    /** The live plan with the highest priority, or null while no planner is running. */
    public static Snapshot active() {
        for (Snapshot s : LIVE) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** One source's plan, or null. For the testkit. */
    public static Snapshot of(Source source) {
        return LIVE[source.ordinal()];
    }
}
