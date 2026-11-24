package com.killer560.hub.deathpatterns;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonRun;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.splittimers.SplitTimersFeature;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Death Patterns (information only, both builds): each of the player's OWN deaths in a dungeon run is kept (floor, where,
 * cause from the death message, class, seconds into the run) in a bounded shared record, and the card groups the last
 * {@link #WINDOW} runs of a floor by where and what killed you. "Where" is the Split Timers segment in the boss
 * (when that is on), else the sidebar room, else "Clear". A repeated death (two or more) is mentioned once in chat
 * the next time that same place is reached on that floor.
 */
public final class DeathPatterns {

    public static final IslandConfig CFG = IslandConfig.of("deathpatterns", "settings").flag("remind", true).num("floor", 0, 0, 30);

    static final int WINDOW = 20;
    private static final int KEEP = 300;
    private static final int REPEAT = 2;
    private static final int POLL_TICKS = 10;
    /** "☠ You were killed by Goldor and became a ghost." / "☠ You fell into the void." (own deaths only: the name is "You"). */
    private static final Pattern DEATH = Pattern.compile("^\\s*☠ You (?:were )?(.{1,80}?)(?: and became a ghost)?\\.$");

    public record Run(long startedAtMs, String floor) { }

    public record Death(long at, long run, String floor, String where, String cause, String cls, int sec) { }

    public record Cause(String where, String cause, int count) { }

    /** The last runs of one floor: how many, how many had a death, the repeated causes (most first) and the classes died as. */
    public record Summary(String floor, int runs, int deaths, int runsWithDeath, List<Cause> patterns, Map<String, Integer> classes) { }

    /** A fixed context for the testkit (the real one needs a live run). */
    public record Ctx(String floor, String where, String cls, long runStartMs) { }

    private static final List<Run> RUNS = new ArrayList<>();
    private static final List<Death> DEATHS = new ArrayList<>();
    private static boolean loaded;
    private static boolean loading;
    private static final DungeonRun.Tracker TRACKER = new DungeonRun.Tracker();
    private static long runStart;
    private static String runFloor;
    private static boolean armedFor;
    private static Map<String, Cause> armed = Map.of();
    private static final Set<String> reminded = new HashSet<>();
    private static int ticks;
    private static int deathsSeen;
    private static int reminders;
    private static Ctx testCtx;

    private DeathPatterns() {
    }

    public static void register() {
        int bit = FeatureBits.register("Death Patterns", CFG::isEnabledRaw);
        ChatObserver.subscribe(m -> {
            if (CFG.isEnabledRaw()) onLine(ChatObserver.strip(m), System.currentTimeMillis());
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DeathPatterns.tick", bit, c -> tick()));
        SharedRecords.onAccountChange(() -> {
            synchronized (DeathPatterns.class) {
                loaded = false;
                loading = false;
                armedFor = false;
            }
        });
    }

    /** Deaths recorded and reminders shown this session (the testkit reads them). */
    public static int deathsSeen() { return deathsSeen; }

    public static int reminders() { return reminders; }

    /** Testkit: pretend this place in a run of {@code floor} (null floor: back to the live state). */
    public static void testContext(String floor, String where, String cls, long runStartMs) {
        testCtx = floor == null ? null : new Ctx(floor, where, cls, runStartMs);
    }

    /** The cause part of a death line, or null if it is not one of your own deaths. */
    public static String causeOf(String plain) {
        Matcher m = DEATH.matcher(plain);
        if (!m.matches()) return null;
        String c = m.group(1).trim();
        if (c.startsWith("killed by ")) c = c.substring("killed by ".length());
        return c.isEmpty() ? null : c;
    }

    private static Ctx live() {
        if (testCtx != null) return testCtx;
        if (!DungeonState.isInDungeon() || DungeonState.isRoomSim() || DungeonState.isSimBoss()) return null;
        String floor = DungeonState.getFloor();
        if (floor == null) return null;
        long split = SplitTimersFeature.getRunStartedAtMs();
        var cls = PartyTracker.selfClass();
        return new Ctx(floor, where(), cls == null ? "?" : cls.toString(), split > 0 ? split : runStart);
    }

    private static String where() {
        String seg = SplitTimersFeature.getCurrentSegmentLabel();
        if (seg != null && seg.isBlank()) seg = null;
        if (DungeonState.isBossPhaseActive()) return seg != null ? seg : "Boss";
        String room = DungeonState.sidebarRoomName();
        return room != null ? room : seg != null ? seg : "Clear";
    }

    /** One chat line (the testkit calls it too). */
    public static void onLine(String plain, long now) {
        String cause = causeOf(plain);
        if (cause == null) return;
        Ctx c = live();
        if (c == null) return;
        synchronized (DeathPatterns.class) {
            long run = logRun(c.floor(), now);
            long start = c.runStartMs() > 0 ? c.runStartMs() : run;
            Death d = new Death(now, run, c.floor(), c.where(), cause.length() > 40 ? cause.substring(0, 40) : cause, c.cls(),
                    (int) Math.max(0, (now - start) / 1000));
            DEATHS.add(0, d);
            while (DEATHS.size() > KEEP) DEATHS.remove(DEATHS.size() - 1);
            deathsSeen++;
        }
        save();
    }

    /** The start key of this run's record, made on first use (a run is logged once, at its first tick or first death). */
    private static long logRun(String floor, long now) {
        if (runFloor == null || !runFloor.equals(floor)) {
            runFloor = floor;
            runStart = now;
            RUNS.add(0, new Run(now, floor));
            while (RUNS.size() > KEEP) RUNS.remove(RUNS.size() - 1);
        }
        return runStart;
    }

    private static void tick() {
        if (++ticks % POLL_TICKS != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !DungeonState.isInDungeon()) return;
        if (TRACKER.newRun(mc.level)) {
            synchronized (DeathPatterns.class) {
                runFloor = null;
                armedFor = false;
                armed = Map.of();
                reminded.clear();
            }
        }
        ensureLoaded();
        String floor = DungeonState.getFloor();
        if (floor == null || DungeonState.isRoomSim() || DungeonState.isSimBoss()) return;
        synchronized (DeathPatterns.class) {
            if (runFloor == null) {
                logRun(floor, System.currentTimeMillis());
                save();
            }
            if (!loaded) return;
            if (!armedFor) {
                armedFor = true;
                armed = new LinkedHashMap<>();
                for (Cause p : summarize(floor).patterns()) {
                    if (p.count() >= REPEAT) armed.putIfAbsent(p.where(), p);
                }
            }
        }
        if (armed.isEmpty() || !CFG.on("remind")) return;
        remind(mc, where(), floor);
    }

    /** Mentions the repeated death of this place once per run (the testkit calls it with a fixed place). */
    static void remind(Minecraft mc, String where, String floor) {
        Cause p;
        synchronized (DeathPatterns.class) {
            p = armed.get(where);
            if (p == null || !reminded.add(where)) return;
        }
        reminders++;
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§c[Death Patterns] §7" + floor + " " + where + ": you died here " + p.count()
                    + " times in your last " + WINDOW + " runs, to §f" + p.cause() + "§7."));
        }
    }

    /** Testkit: arms the reminder from the stored deaths for {@code floor} and reminds at {@code where}. */
    public static void remindForTest(String floor, String where) {
        synchronized (DeathPatterns.class) {
            armed = new LinkedHashMap<>();
            for (Cause p : summarize(floor).patterns()) {
                if (p.count() >= REPEAT) armed.putIfAbsent(p.where(), p);
            }
            reminded.clear();
        }
        remind(Minecraft.getInstance(), where, floor);
    }

    // ---- grouping (pure over the lists) ----

    /** Floors with a recorded run, the newest run's floor first. */
    public static synchronized List<String> floors() {
        List<String> out = new ArrayList<>();
        for (Run r : RUNS) if (!out.contains(r.floor())) out.add(r.floor());
        return out;
    }

    public static synchronized Summary summarize(String floor) {
        Set<Long> runs = new HashSet<>();
        for (Run r : RUNS) {
            if (r.floor().equals(floor) && runs.size() < WINDOW) runs.add(r.startedAtMs());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        Map<String, Integer> classes = new LinkedHashMap<>();
        Set<Long> withDeath = new HashSet<>();
        int deaths = 0;
        for (Death d : DEATHS) {
            if (!d.floor().equals(floor) || !runs.contains(d.run())) continue;
            deaths++;
            withDeath.add(d.run());
            String k = d.where() + "\n" + d.cause();
            counts.merge(k, 1, Integer::sum);
            keys.putIfAbsent(k, new String[]{d.where(), d.cause()});
            classes.merge(d.cls(), 1, Integer::sum);
        }
        List<Cause> pats = new ArrayList<>();
        counts.forEach((k, n) -> pats.add(new Cause(keys.get(k)[0], keys.get(k)[1], n)));
        pats.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return new Summary(floor, runs.size(), deaths, withDeath.size(), pats, classes);
    }

    /** The card's lines for one floor, or one "not enough data" line. */
    public static List<String> lines(String floor) {
        Summary s = summarize(floor);
        List<String> out = new ArrayList<>();
        if (s.runs() == 0) {
            out.add("No runs recorded yet");
            return out;
        }
        out.add(floor + " last " + s.runs() + " run" + (s.runs() == 1 ? "" : "s") + ": " + s.deaths() + " death" + (s.deaths() == 1 ? "" : "s")
                + (s.deaths() > 0 ? " in " + s.runsWithDeath() + " run" + (s.runsWithDeath() == 1 ? "" : "s") : ""));
        for (Cause p : s.patterns()) {
            if (out.size() > 8) break;
            out.add(p.count() + "x " + p.where() + ": " + p.cause());
        }
        if (!s.classes().isEmpty()) {
            StringBuilder b = new StringBuilder("As:");
            s.classes().forEach((k, n) -> b.append(' ').append(k).append(' ').append(n));
            out.add(b.toString());
        }
        return out;
    }

    // ---- store ----

    /** Reads the shared record off the calling thread; the card shows "Loading" until it has. */
    public static boolean ensureLoaded() {
        synchronized (DeathPatterns.class) {
            if (loaded) return true;
            if (loading) return false;
            loading = true;
        }
        SharedWriter.submit(() -> {
            JsonObject root = SharedRecords.load(SharedRecords.file(RecordCatalog.DEATH_PATTERNS));
            synchronized (DeathPatterns.class) {
                // Entries made while it loaded stay (they are merged onto the disk on the next save).
                mergeIn(root);
                loaded = true;
                loading = false;
            }
        });
        return false;
    }

    private static void mergeIn(JsonObject root) {
        JsonArray runs = ConfigJson.getArray(root, "runs");
        if (runs != null) {
            for (var e : runs) {
                JsonObject o = e.getAsJsonObject();
                Run r = new Run(ConfigJson.getLong(o, "startedAtMs", 0), ConfigJson.getString(o, "floor", ""));
                if (r.startedAtMs() > 0 && !RUNS.contains(r)) RUNS.add(r);
            }
            RUNS.sort((a, b) -> Long.compare(b.startedAtMs(), a.startedAtMs()));
        }
        JsonArray deaths = ConfigJson.getArray(root, "deaths");
        if (deaths != null) {
            for (var e : deaths) {
                JsonObject o = e.getAsJsonObject();
                Death d = new Death(ConfigJson.getLong(o, "at", 0), ConfigJson.getLong(o, "run", 0), ConfigJson.getString(o, "floor", ""),
                        ConfigJson.getString(o, "where", ""), ConfigJson.getString(o, "cause", ""), ConfigJson.getString(o, "cls", "?"),
                        ConfigJson.getInt(o, "sec", 0));
                if (d.at() > 0 && !DEATHS.contains(d)) DEATHS.add(d);
            }
            DEATHS.sort((a, b) -> Long.compare(b.at(), a.at()));
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonArray runs = new JsonArray();
        JsonArray deaths = new JsonArray();
        synchronized (DeathPatterns.class) {
            for (Run r : RUNS) {
                JsonObject o = new JsonObject();
                o.addProperty("startedAtMs", r.startedAtMs());
                o.addProperty("floor", r.floor());
                runs.add(o);
            }
            for (Death d : DEATHS) {
                JsonObject o = new JsonObject();
                o.addProperty("at", d.at());
                o.addProperty("run", d.run());
                o.addProperty("floor", d.floor());
                o.addProperty("where", d.where());
                o.addProperty("cause", d.cause());
                o.addProperty("cls", d.cls());
                o.addProperty("sec", d.sec());
                deaths.add(o);
            }
        }
        root.add("runs", runs);
        root.add("deaths", deaths);
        SharedWriter.submit(() -> SharedRecords.save(SharedRecords.file(RecordCatalog.DEATH_PATTERNS), root));
    }

    /** Testkit: starts a new run record of {@code floor} at {@code startMs}. */
    public static synchronized void newRunForTest(String floor, long startMs) {
        runFloor = null;
        logRun(floor, startMs);
    }

    /** Testkit: forget the in-memory lists (the file stays) so a case starts clean; marks the store loaded. */
    public static synchronized void resetForTest() {
        RUNS.clear();
        DEATHS.clear();
        loaded = true;
        loading = false;
        armed = Map.of();
        reminded.clear();
        deathsSeen = 0;
        reminders = 0;
        testCtx = null;
        runFloor = null;
    }
}
