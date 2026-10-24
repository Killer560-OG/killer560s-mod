package com.killer560.hub.croesus;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the mod knows about each Croesus run, kept with the run wherever it moves in the list and across restarts
 * (killer560, 2026-10-12: "if it moves it still needs to be tracked").
 * <p>
 * <b>Identity.</b> A run is its name (Master or not), floor and players, plus the instant its chests expire: the head's
 * "Chests expire in 1d 12h!" (or, without it, "Completed: 2 hours ago" + Croesus's 72 hours) turned into an absolute time,
 * with a tolerance as coarse as the text (an hour when it shows hours, a minute when minutes). That instant is fixed for
 * a run, so it survives the run sliding a slot when one before it expires.
 * <p>
 * <b>Matching a page.</b> Runs with the same party and floor whose windows overlap cannot be told apart by time alone (he
 * runs the same floor with the same party all day), so on each page read the in-window pairs vote on the shift of the
 * list (a run ahead expiring moves every later run back by one; the order of the rest never changes) and the most common
 * shift assigns them. What is left is assigned only when exactly one run fits within a few places; anything still
 * ambiguous reads "not looked at" (never hidden) and is logged.
 * <p>
 * <b>Persistence.</b> {@code killer560smod-croesus-runs.json} (dungeons/runs); runs past their expiry are dropped on load
 * and save. Run data, not a setting, so profiles do not carry it. The same file keeps each Croesus page as last read
 * (its runs' expiry and state), so the info box's totals survive a restart (2026-10-13).
 */
final class CroesusRunMemory {

    private static final Logger LOGGER = ModLog.get("killer560smod-croesus");
    private static final Path PATH = ModPaths.config("killer560smod-croesus-runs.json");
    /** Croesus keeps a run 72 hours (hypixelskyblock.minecraft.wiki/w/Croesus). */
    static final long RUN_LIFETIME_MS = 72L * 3600_000L;
    /** Slack on top of the two texts' own coarseness: the two reads are taken at different moments. */
    private static final long READ_SLACK_MS = 120_000L;
    /** A leftover run is matched alone only this many places from where it was. */
    private static final int LEFTOVER_MAX_SHIFT = 3;
    private static final Pattern EXPIRE = Pattern.compile("^Chests expire in ((?:\\d{1,4}[dhms] ?){1,4})!?$");
    private static final Pattern EXPIRE_PART = Pattern.compile("(\\d{1,4})([dhms])");
    private static final Pattern COMPLETED = Pattern.compile("^Completed: (\\d{1,4}) (second|minute|hour|day)s? ago$");

    /** One run, as remembered. */
    static final class Run {
        final String id;
        final String sig;
        long expiresAt;
        long tolMs;
        int order;
        /** When a list read last saw this run (ms), 0 if unknown (a run loaded from a file saved before this existed). */
        long seenAtMs;
        /** Each chest not opened when its run view was last seen: type -> profit after a key. Null until seen. */
        Map<String, Long> chests;
        String claimType;
        boolean claimKeyed;
        long claimAtMs;

        Run(String id, String sig, long expiresAt, long tolMs, int order) {
            this.id = id;
            this.sig = sig;
            this.expiresAt = expiresAt;
            this.tolMs = tolMs;
            this.order = order;
        }
    }

    /** One list entry: its place (overall index across pages), who it is, when it expires, and whether its head says
     *  "No chests opened yet!". */
    record Entry(int slot, int order, String sig, long expiresAt, long tolMs, boolean unopened) {
    }

    private static final List<Run> RUNS = new ArrayList<>();

    /** One run as a page showed it: when it expires and the state it was drawn in (ChestProfitFeature.RUN_*). */
    record PageRun(long expiresAt, long tolMs, byte state) {
    }

    /** A Croesus page as last read. {@code stale}: newer runs have pushed the list along since, so it no longer holds
     *  exactly the runs now on that page (its counts still add up to real runs, but the total misses some). */
    static final class Page {
        final List<PageRun> runs;
        final long readAtMs;
        boolean stale;

        Page(List<PageRun> runs, long readAtMs, boolean stale) {
            this.runs = runs;
            this.readAtMs = readAtMs;
            this.stale = stale;
        }
    }

    /** killer560, 2026-10-13: "make it save across reloading my instance". The pages behind the info box's totals, saved
     *  with the runs so "Unclaimed X / 60" and "Already claimed" are right straight after a restart. */
    private static final Map<Integer, Page> PAGES = new HashMap<>();
    private static int pageCount = 0;
    private static boolean loaded = false;
    private static volatile int version = 0;

    private CroesusRunMemory() {
    }

    static synchronized int version() {
        ensureLoaded();
        return version;
    }

    static void bump() {
        version++;
    }

    /** An entry from a list head's lore. {@code order} is the run's overall place: (page - 1) * 28 + its index. */
    static Entry entry(int slot, int order, String sig, List<String> lore, long now) {
        boolean unopened = lore.contains(DungeonChestValuer.RUN_UNOPENED_LORE);
        for (String line : lore) {
            Matcher m = EXPIRE.matcher(line.trim());
            if (m.matches()) {
                long total = 0;
                long unit = Long.MAX_VALUE;
                Matcher p = EXPIRE_PART.matcher(m.group(1));
                while (p.find()) {
                    long u = unitMs(p.group(2).charAt(0));
                    total += Long.parseLong(p.group(1)) * u;
                    unit = Math.min(unit, u);
                }
                if (unit != Long.MAX_VALUE) {
                    return new Entry(slot, order, sig, now + total, unit, unopened);
                }
            }
        }
        for (String line : lore) {
            Matcher m = COMPLETED.matcher(line.trim());
            if (m.matches()) {
                long unit = unitMs(m.group(2).charAt(0));
                return new Entry(slot, order, sig, now - Long.parseLong(m.group(1)) * unit + RUN_LIFETIME_MS, unit, unopened);
            }
        }
        return new Entry(slot, order, sig, 0L, RUN_LIFETIME_MS, unopened); // no time on it: identity by order alone
    }

    private static long unitMs(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'd' -> 86_400_000L;
            case 'h' -> 3_600_000L;
            case 'm' -> 60_000L;
            default -> 1_000L;
        };
    }

    /**
     * A run only moves along the list as fast as runs are added to it. One that a list read saw a minute ago at place 5
     * cannot be the run now at place 33: a run takes minutes, so a shift of 28 would need 28 runs in that minute. Without
     * this, two pages read back to back, with the same party's runs all inside each other's expiry window (an hour wide
     * when the head only gives hours), voted for a shift of one whole page and gave page 2's runs page 1's states - the
     * highlights that "completely bug out and won't update" after Auto Croesus had been through page 1 (killer560,
     * 2026-10-18). Allowed: 2 places, plus one for every {@link #MIN_RUN_GAP_MS} since the run was last seen.
     * <p>
     * Later in the list only as many places as runs could have been added since (no slack): only a new run pushes a run
     * later. With the 2 free places, page 1's last run, read a second before, was "reachable" from page 2's first slot,
     * voted for a shift of one there and was handed page 2's first head, so every run on page 2 took its neighbor's
     * memory and the run left over read "not looked at" (killer560, 2026-10-24: "randomly marks a run as not seen
     * before", most of all off page 1). Earlier keeps the slack: a run ahead expiring needs no time (testkit 888).
     */
    static boolean reachable(Run r, Entry e, long now) {
        if (r.seenAtMs <= 0) {
            return true;
        }
        long gaps = Math.max(0L, now - r.seenAtMs) / MIN_RUN_GAP_MS;
        int later = e.order() - r.order;
        return later > 0 ? later <= gaps : -later <= 2 + gaps;
    }

    /** The fastest a party can start another run into the list: no dungeon run is shorter than this. */
    private static final long MIN_RUN_GAP_MS = 180_000L;

    private static boolean onPage(int order, int pageFirst, int per) {
        return order >= pageFirst && order < pageFirst + per;
    }

    /**
     * The head says {@code e} cannot be {@code r}: a claim of {@code r} seen over {@link ChestProfitFeature#CLAIM_TRUST_MS}
     * ago (so not a head lagging it) under a head that still says "No chests opened yet!", or {@code e} expiring later than
     * any run already in the list when {@code r} was last seen could (it finished after that read). Used to break a tie
     * between shifts: one party's new run at the front ties shift 0 against shift -1, and shift 0 hands it the remembered
     * run before it.
     */
    private static boolean contradicts(Run r, Entry e, long now) {
        if (r.claimType != null && e.unopened() && now - r.claimAtMs >= ChestProfitFeature.CLAIM_TRUST_MS) {
            return true;
        }
        return r.seenAtMs > 0 && e.expiresAt() > 0
                && e.expiresAt() - e.tolMs() - READ_SLACK_MS > r.seenAtMs + RUN_LIFETIME_MS;
    }

    private static boolean inWindow(Run r, Entry e) {
        return r.sig.equals(e.sig()) && Math.abs(r.expiresAt - e.expiresAt()) <= r.tolMs + e.tolMs() + READ_SLACK_MS;
    }

    /** The remembered run of each entry (same order), or null for one not remembered or truly ambiguous. */
    static synchronized Run[] match(List<Entry> entries) {
        ensureLoaded();
        long now = System.currentTimeMillis();
        Run[] out = new Run[entries.size()];
        java.util.Set<Run> used = new java.util.HashSet<>();
        // 1. Every in-window pair votes for the list's shift; the most common shift assigns them. A tie goes to the shift
        // whose pairs the heads contradict least ({@link #contradicts}), then to the smallest.
        Map<Integer, Integer> votes = new HashMap<>();
        Map<Integer, Integer> against = new HashMap<>();
        for (Entry e : entries) {
            for (Run r : RUNS) {
                if (inWindow(r, e) && reachable(r, e, now)) {
                    votes.merge(r.order - e.order(), 1, Integer::sum);
                    if (contradicts(r, e, now)) {
                        against.merge(r.order - e.order(), 1, Integer::sum);
                    }
                }
            }
        }
        List<Integer> deltas = new ArrayList<>(votes.keySet());
        deltas.sort((a, b) -> {
            int byVotes = Integer.compare(votes.get(b), votes.get(a));
            int byAgainst = Integer.compare(against.getOrDefault(a, 0), against.getOrDefault(b, 0));
            return byVotes != 0 ? byVotes : byAgainst != 0 ? byAgainst : Integer.compare(Math.abs(a), Math.abs(b));
        });
        // A page read sees one page of the list, so every run whose head is on another page looks free here. Only the
        // winning shift may bring a run in from another page; the weaker shifts and the leftovers below take only a run
        // that the winning shift puts on this page. Before, an unseen head at a page's edge took the next page's
        // first run (or the run the shift had just pushed off this page), and that page then read its own run as new.
        int win = deltas.isEmpty() ? 0 : deltas.get(0);
        int per = DungeonChestValuer.RUN_HEAD_SLOTS.length;
        int pageFirst = entries.isEmpty() ? 0 : Math.floorDiv(entries.get(0).order(), per) * per;
        for (int d : deltas) {
            for (int i = 0; i < entries.size(); i++) {
                if (out[i] != null) {
                    continue;
                }
                Entry e = entries.get(i);
                for (Run r : RUNS) {
                    if (!used.contains(r) && r.order - e.order() == d && inWindow(r, e) && reachable(r, e, now)
                            && (d == win || onPage(r.order - win, pageFirst, per))) {
                        out[i] = r;
                        used.add(r);
                        break;
                    }
                }
            }
            if (votes.get(d) < 2) {
                break; // single votes are not a shift of the list: the leftover pass below decides those
            }
        }
        // 2. Leftovers: exactly one free run that fits, within a few places.
        for (int i = 0; i < entries.size(); i++) {
            if (out[i] != null) {
                continue;
            }
            Entry e = entries.get(i);
            List<Run> fit = new ArrayList<>();
            for (Run r : RUNS) {
                if (!used.contains(r) && inWindow(r, e) && Math.abs(r.order - e.order()) <= LEFTOVER_MAX_SHIFT
                        && reachable(r, e, now) && onPage(r.order - win, pageFirst, per)) {
                    fit.add(r);
                }
            }
            if (fit.size() == 1) {
                out[i] = fit.get(0);
                used.add(fit.get(0));
            } else if (fit.size() > 1) {
                LOGGER.info("[Croesus] Run at place {} ({}) matches {} remembered runs - treated as not looked at",
                        e.order(), e.sig(), fit.size());
            }
        }
        // 3. Follow the runs: their place now, and the tighter of the two expiry estimates.
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            Run r = out[i];
            if (r == null) {
                continue;
            }
            Entry e = entries.get(i);
            r.seenAtMs = now;
            if (r.order != e.order()) {
                r.order = e.order();
                changed = true;
            }
            if (e.tolMs() < r.tolMs) {
                r.expiresAt = e.expiresAt();
                r.tolMs = e.tolMs();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return out;
    }

    /** A new remembered run for an entry nothing matched (he or Auto Croesus is opening it). */
    static synchronized Run create(Entry e) {
        ensureLoaded();
        Run r = new Run(UUID.randomUUID().toString(), e.sig(), e.expiresAt(), e.tolMs(), e.order());
        r.seenAtMs = System.currentTimeMillis();
        RUNS.add(r);
        return r;
    }

    static synchronized void noteChests(Run r, Map<String, Long> chests) {
        if (r == null || chests.equals(r.chests)) {
            return;
        }
        r.chests = Map.copyOf(chests);
        version++;
        save();
    }

    static synchronized void noteClaim(Run r, String type, boolean keyed, long now) {
        if (r == null) {
            return;
        }
        r.claimType = type;
        r.claimKeyed = keyed;
        r.claimAtMs = now;
        version++;
        save();
    }

    static synchronized int activeClaims(long now, long trustMs) {
        ensureLoaded();
        int n = 0;
        for (Run r : RUNS) {
            if (r.claimType != null && now - r.claimAtMs < trustMs) {
                n++;
            }
        }
        return n;
    }

    // ---- pages (the info box's totals) ------------------------------------------------------------------------------

    /**
     * A page was read. When page 1 shows a run newer than the newest it showed before, or the number of pages changed,
     * runs were added and the list moved along: every other page is marked stale until it is read again (its runs are
     * real, but the runs now between pages are not counted). A run that expired drops off by itself (below), which does
     * not move the pages before it.
     */
    static synchronized void notePage(int page, int pages, List<PageRun> runs, long now) {
        ensureLoaded();
        Page old = PAGES.get(page);
        boolean moved = pages != pageCount && pageCount != 0;
        if (page == 1 && old != null && !old.runs.isEmpty() && !runs.isEmpty()) {
            PageRun was = old.runs.get(0);
            PageRun is = runs.get(0);
            if (was.expiresAt() > 0 && is.expiresAt() > was.expiresAt() + was.tolMs() + is.tolMs() + READ_SLACK_MS) {
                moved = true;
            }
        }
        if (moved) {
            for (Map.Entry<Integer, Page> e : PAGES.entrySet()) {
                if (e.getKey() != page) {
                    e.getValue().stale = true;
                }
            }
        }
        pageCount = pages;
        boolean same = old != null && !old.stale && old.runs.equals(runs);
        PAGES.put(page, new Page(List.copyOf(runs), now, false));
        if (!same || moved) {
            version++;
            save();
        }
    }

    /** {unclaimed, claimed, pages read and still current, pages, worth a key, not looked at}, runs past expiry left out. */
    static synchronized int[] pageTotals(long now, byte unclaimed, byte needsKey, byte unknown) {
        ensureLoaded();
        int u = 0;
        int c = 0;
        int k = 0;
        int q = 0;
        int seen = 0;
        for (Map.Entry<Integer, Page> e : PAGES.entrySet()) {
            if (e.getKey() > pageCount) {
                continue;
            }
            if (!e.getValue().stale) {
                seen++;
            }
            for (PageRun r : e.getValue().runs) {
                if (r.expiresAt() > 0 && r.expiresAt() + r.tolMs() < now) {
                    continue; // gone from Croesus since that page was read
                }
                if (r.state() == unclaimed) {
                    u++;
                } else {
                    c++;
                    if (r.state() == needsKey) {
                        k++;
                    } else if (r.state() == unknown) {
                        q++;
                    }
                }
            }
        }
        return new int[]{u, c, seen, pageCount, k, q};
    }

    // ---- persistence ----------------------------------------------------------------------------------------------

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    static synchronized void load() {
        flushWrites();
        loaded = true;
        RUNS.clear();
        PAGES.clear();
        pageCount = 0;
        version++;
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray runs = ConfigJson.getArray(obj, "runs");
            long now = System.currentTimeMillis();
            if (runs != null) {
                for (JsonElement el : runs) {
                    JsonObject o = el.getAsJsonObject();
                    Run r = new Run(ConfigJson.getString(o, "id", UUID.randomUUID().toString()), ConfigJson.getString(o, "sig", ""),
                            ConfigJson.getLong(o, "expiresAt", 0), ConfigJson.getLong(o, "tolMs", RUN_LIFETIME_MS),
                            ConfigJson.getInt(o, "order", 0));
                    if (r.sig.isEmpty() || (r.expiresAt > 0 && r.expiresAt + r.tolMs < now)) {
                        continue; // gone from Croesus
                    }
                    r.seenAtMs = ConfigJson.getLong(o, "seenAt", 0);
                    JsonObject chests = ConfigJson.getObject(o, "chests");
                    if (chests != null) {
                        Map<String, Long> m = new LinkedHashMap<>();
                        chests.entrySet().forEach(en -> m.put(en.getKey(), en.getValue().getAsLong()));
                        r.chests = Map.copyOf(m);
                    }
                    r.claimType = ConfigJson.getString(o, "claimType", null);
                    r.claimKeyed = ConfigJson.getBool(o, "claimKeyed", false);
                    r.claimAtMs = ConfigJson.getLong(o, "claimAt", 0);
                    RUNS.add(r);
                }
            }
            pageCount = ConfigJson.getInt(obj, "pageCount", 0);
            JsonArray pages = ConfigJson.getArray(obj, "pages");
            if (pages != null) {
                for (JsonElement el : pages) {
                    JsonObject o = el.getAsJsonObject();
                    List<PageRun> list = new ArrayList<>();
                    JsonArray rs = ConfigJson.getArray(o, "runs");
                    if (rs != null) {
                        for (JsonElement re : rs) {
                            JsonArray a = re.getAsJsonArray();
                            list.add(new PageRun(a.get(0).getAsLong(), a.get(1).getAsLong(), a.get(2).getAsByte()));
                        }
                    }
                    PAGES.put(ConfigJson.getInt(o, "page", 1), new Page(List.copyOf(list), ConfigJson.getLong(o, "readAt", 0),
                            ConfigJson.getBool(o, "stale", false)));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Croesus] Could not read {} - starting with no remembered runs", PATH.getFileName(), e);
        }
    }

    private static void save() {
        try {
            long now = System.currentTimeMillis();
            RUNS.removeIf(r -> r.expiresAt > 0 && r.expiresAt + r.tolMs < now);
            JsonArray runs = new JsonArray();
            for (Run r : RUNS) {
                if (r.chests == null && r.claimType == null) {
                    continue; // nothing learned about it yet
                }
                JsonObject o = new JsonObject();
                o.addProperty("id", r.id);
                o.addProperty("sig", r.sig);
                o.addProperty("expiresAt", r.expiresAt);
                o.addProperty("tolMs", r.tolMs);
                o.addProperty("order", r.order);
                o.addProperty("seenAt", r.seenAtMs);
                if (r.chests != null) {
                    JsonObject c = new JsonObject();
                    r.chests.forEach(c::addProperty);
                    o.add("chests", c);
                }
                if (r.claimType != null) {
                    o.addProperty("claimType", r.claimType);
                    o.addProperty("claimKeyed", r.claimKeyed);
                    o.addProperty("claimAt", r.claimAtMs);
                }
                runs.add(o);
            }
            JsonObject obj = new JsonObject();
            obj.add("runs", runs);
            obj.addProperty("pageCount", pageCount);
            JsonArray pages = new JsonArray();
            for (Map.Entry<Integer, Page> e : new java.util.TreeMap<>(PAGES).entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("page", e.getKey());
                o.addProperty("readAt", e.getValue().readAtMs);
                o.addProperty("stale", e.getValue().stale);
                JsonArray rs = new JsonArray();
                for (PageRun r : e.getValue().runs) {
                    if (r.expiresAt() > 0 && r.expiresAt() + r.tolMs() < now) {
                        continue;
                    }
                    JsonArray a = new JsonArray();
                    a.add(r.expiresAt());
                    a.add(r.tolMs());
                    a.add(r.state());
                    rs.add(a);
                }
                o.add("runs", rs);
                pages.add(o);
            }
            obj.add("pages", pages);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(obj);
            // The write itself goes to a writer thread: a save is triggered from the render path (a page read, a claim seen),
            // and a disk write there is a frame hitch. One thread keeps the writes in order; load() waits for it.
            WRITER.submit(() -> {
                try {
                    Files.createDirectories(PATH.getParent());
                    Path tmp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
                    Files.writeString(tmp, json, StandardCharsets.UTF_8);
                    replace(tmp, PATH, json);
                } catch (Exception e) {
                    LOGGER.warn("[Croesus] Could not save {}", PATH.getFileName(), e);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("[Croesus] Could not save {}", PATH.getFileName(), e);
        }
    }

    /**
     * Puts {@code tmp} in place of {@code target}. Windows refuses the replace while anything else has the target open
     * (FileAlreadyExistsException: a scanner, or a reader at that moment), and a refused replace used to drop the save
     * outright, so a restart read the save before it (890 lost pages 2 and 3 that way, 2026-10-22). Then the file is
     * written in place instead, which an open reader does not block.
     */
    static void replace(Path tmp, Path target, String json) throws java.io.IOException {
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException refused) {
            Files.writeString(target, json, StandardCharsets.UTF_8);
            Files.deleteIfExists(tmp);
        }
    }

    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-croesus-runs-writer");
        t.setDaemon(true);
        return t;
    });

    /** Returns once every write queued so far is on disk (a restart's read must see the last save). */
    private static void flushWrites() {
        try {
            WRITER.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** For the testkit: forget every run, on disk too. */
    static synchronized void clearForTest() {
        loaded = true;
        RUNS.clear();
        PAGES.clear();
        pageCount = 0;
        version++;
        save();
    }

    static String describe(Run r) {
        return r == null ? "-" : String.format(Locale.ROOT, "%s@%d", r.id.substring(0, 8), r.order);
    }
}
