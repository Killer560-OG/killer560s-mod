package com.killer560.hub.profileviewer.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Static tables for the extra pages.
 * <ul>
 *   <li>{@code extras.json} (bundled): meowdding-repo {@code repo/pv} crimson isle / rift / corpse / garden
 *   tables and skyblock-pv's HOTM/HOTF level thresholds.</li>
 *   <li>Collections: Hypixel's keyless {@code /v2/resources/skyblock/collections} (what skyblock-pv's
 *   {@code CollectionAPI} reads).</li>
 *   <li>Bestiary: meowdding-repo {@code repo/neu/bestiary.json} (skyblock-pv's {@code BestiaryCodecs}),
 *   NEU-REPO {@code constants/bestiary.json} as fallback.</li>
 *   <li>Museum categories: NEU-REPO {@code constants/museum.json}.</li>
 *   <li>Lowest BIN prices: Coflnet's keyless NEU-format price list.</li>
 * </ul>
 * Remote tables are fetched once per session on first use (retried after a minute on failure).
 */
public final class ExtraTables {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-profileviewer");

    private static final String COLLECTIONS_URL = "https://api.hypixel.net/v2/resources/skyblock/collections";
    private static final String BESTIARY_URL = "https://raw.githubusercontent.com/meowdding/meowdding-repo/HEAD/repo/neu/bestiary.json";
    private static final String BESTIARY_FALLBACK = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/bestiary.json";
    private static final String MUSEUM_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/museum.json";
    private static final String LBIN_URL = "https://sky.coflnet.com/api/prices/neu";
    private static final long RETRY_MS = 60_000L;
    private static final long PRICE_TTL_MS = 10 * 60_000L;

    private ExtraTables() {
    }

    // ------------------------------------------------------------------ bundled

    private static JsonObject bundled;

    public static synchronized JsonObject bundled() {
        if (bundled == null) {
            try (InputStream in = ExtraTables.class.getResourceAsStream("/assets/killer560smod/profileviewer/extras.json")) {
                bundled = in == null ? new JsonObject()
                        : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                LOGGER.warn("[ProfileViewer] bundled extras table unreadable", e);
                bundled = new JsonObject();
            }
        }
        return bundled;
    }

    public static JsonObject section(String name) {
        JsonObject o = SbProfile.asObj(bundled().get(name));
        return o == null ? new JsonObject() : o;
    }

    public static long[] longs(JsonElement el) {
        JsonArray arr = SbProfile.asArr(el);
        if (arr == null) {
            return new long[0];
        }
        long[] out = new long[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (long) SbProfile.num(arr.get(i));
        }
        return out;
    }

    public static List<String> strings(JsonElement el) {
        List<String> out = new ArrayList<>();
        JsonArray arr = SbProfile.asArr(el);
        if (arr != null) {
            for (JsonElement e : arr) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    /** Running totals of a per-level increment list. */
    public static long[] cumulative(long[] increments) {
        long[] out = new long[increments.length];
        long sum = 0;
        for (int i = 0; i < increments.length; i++) {
            sum += increments[i];
            out[i] = sum;
        }
        return out;
    }

    /** Highest threshold entry whose threshold is <= amount; the first entry otherwise. */
    public static String threshold(JsonElement list, String field, long amount) {
        JsonArray arr = SbProfile.asArr(list);
        String best = null;
        long bestT = Long.MIN_VALUE;
        if (arr == null) {
            return "";
        }
        for (JsonElement e : arr) {
            JsonObject o = SbProfile.asObj(e);
            if (o == null) {
                continue;
            }
            long t = (long) SbProfile.num(o.get("threshold"));
            if (t <= amount && t >= bestT) {
                bestT = t;
                best = SbProfile.str(o, field, "");
            }
        }
        return best == null ? "" : best;
    }

    // ------------------------------------------------------------------ remote loader

    private static final class Remote<T> {
        private final java.util.function.Supplier<CompletableFuture<T>> loader;
        private final long ttl;
        private CompletableFuture<T> future;
        private long startedAt;

        Remote(java.util.function.Supplier<CompletableFuture<T>> loader, long ttl) {
            this.loader = loader;
            this.ttl = ttl;
        }

        synchronized CompletableFuture<T> get() {
            long now = System.currentTimeMillis();
            boolean failed = future != null && future.isDone() && (future.isCompletedExceptionally() || future.getNow(null) == null);
            boolean expired = future != null && future.isDone() && ttl > 0 && now - startedAt > ttl;
            if (future == null || (failed && now - startedAt > RETRY_MS) || expired) {
                startedAt = now;
                CompletableFuture<T> f;
                try {
                    f = loader.get();
                } catch (Throwable t) {
                    f = CompletableFuture.completedFuture(null);
                }
                future = f.exceptionally(t -> null);
            }
            return future;
        }
    }

    /** Non-null once loaded, null while loading or after a failure. */
    public static <T> T now(CompletableFuture<T> f) {
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    // ------------------------------------------------------------------ collections

    public record CollectionItem(String id, String name, int maxTiers, long[] tiers) {
    }

    public record CollectionCategory(String id, String name, List<CollectionItem> items) {
    }

    private static final Remote<List<CollectionCategory>> COLLECTIONS = new Remote<>(() ->
            ProfileViewerApi.getKeylessJson(COLLECTIONS_URL).thenApplyAsync(json -> {
                JsonObject cols = json == null ? null : SbProfile.asObj(json.get("collections"));
                if (cols == null) {
                    return null;
                }
                List<CollectionCategory> out = new ArrayList<>();
                for (Map.Entry<String, JsonElement> c : cols.entrySet()) {
                    JsonObject cat = SbProfile.asObj(c.getValue());
                    JsonObject items = cat == null ? null : SbProfile.asObj(cat.get("items"));
                    if (items == null) {
                        continue;
                    }
                    List<CollectionItem> list = new ArrayList<>();
                    for (Map.Entry<String, JsonElement> i : items.entrySet()) {
                        JsonObject it = SbProfile.asObj(i.getValue());
                        if (it == null) {
                            continue;
                        }
                        JsonArray tiers = SbProfile.asArr(it.get("tiers"));
                        List<Long> amounts = new ArrayList<>();
                        if (tiers != null) {
                            for (JsonElement t : tiers) {
                                JsonObject to = SbProfile.asObj(t);
                                if (to != null) {
                                    amounts.add((long) SbProfile.num(to.get("amountRequired")));
                                }
                            }
                        }
                        Collections.sort(amounts);
                        long[] arr = amounts.stream().mapToLong(Long::longValue).toArray();
                        list.add(new CollectionItem(i.getKey(), SbProfile.str(it, "name", SbProfile.titleCase(i.getKey())),
                                (int) SbProfile.num(it.get("maxTiers")), arr));
                    }
                    list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                    out.add(new CollectionCategory(c.getKey(), SbProfile.str(cat, "name", SbProfile.titleCase(c.getKey())), list));
                }
                return out.isEmpty() ? null : Collections.unmodifiableList(out);
            }, ProfileViewerApi.EXECUTOR), 0);

    public static CompletableFuture<List<CollectionCategory>> collections() {
        return COLLECTIONS.get();
    }

    // ------------------------------------------------------------------ bestiary

    /** @param icon a vanilla item id ("dragon_egg") or null; @param texture a skull texture or null. */
    public record BestiaryFamily(String name, String icon, String texture, int cap, List<String> mobs, int[] tiers) {
    }

    public record BestiaryCategory(String id, String name, String icon, String texture, List<BestiaryFamily> families) {
    }

    private static final Remote<List<BestiaryCategory>> BESTIARY = new Remote<>(() ->
            ProfileViewerApi.getKeylessJson(BESTIARY_URL)
                    .thenCompose(j -> j != null ? CompletableFuture.completedFuture(j) : ProfileViewerApi.getKeylessJson(BESTIARY_FALLBACK))
                    .thenApplyAsync(ExtraTables::parseBestiary, ProfileViewerApi.EXECUTOR), 0);

    public static CompletableFuture<List<BestiaryCategory>> bestiary() {
        return BESTIARY.get();
    }

    private static List<BestiaryCategory> parseBestiary(JsonObject json) {
        if (json == null) {
            return null;
        }
        Map<String, int[]> brackets = brackets(SbProfile.asObj(json.get("brackets")));
        Map<String, Map<String, int[]>> sets = new LinkedHashMap<>();
        JsonObject bs = SbProfile.asObj(json.get("bracketSets"));
        if (bs != null) {
            for (Map.Entry<String, JsonElement> e : bs.entrySet()) {
                sets.put(e.getKey().toUpperCase(java.util.Locale.ROOT), brackets(SbProfile.asObj(e.getValue())));
            }
        }
        List<BestiaryCategory> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (e.getKey().equals("brackets") || e.getKey().equals("bracketSets")) {
                continue;
            }
            JsonObject cat = SbProfile.asObj(e.getValue());
            if (cat == null || !cat.has("name")) {
                continue;
            }
            List<BestiaryFamily> families = new ArrayList<>();
            if (SbProfile.asArr(cat.get("mobs")) != null) {
                addFamilies(families, SbProfile.asArr(cat.get("mobs")), brackets, sets);
            } else {
                for (Map.Entry<String, JsonElement> sub : cat.entrySet()) {
                    JsonObject so = SbProfile.asObj(sub.getValue());
                    if (so != null && SbProfile.asArr(so.get("mobs")) != null) {
                        addFamilies(families, SbProfile.asArr(so.get("mobs")), brackets, sets);
                    }
                }
            }
            JsonObject icon = SbProfile.asObj(cat.get("icon"));
            out.add(new BestiaryCategory(e.getKey(), cleanName(SbProfile.str(cat, "name", e.getKey())),
                    SbProfile.str(icon, "item", null), fixTexture(SbProfile.str(icon, "texture", null)), families));
        }
        return out.isEmpty() ? null : Collections.unmodifiableList(out);
    }

    private static void addFamilies(List<BestiaryFamily> out, JsonArray mobs, Map<String, int[]> brackets,
                                     Map<String, Map<String, int[]>> sets) {
        for (JsonElement el : mobs) {
            JsonObject m = SbProfile.asObj(el);
            if (m == null) {
                continue;
            }
            int cap = (int) SbProfile.num(m.get("cap"));
            String bracket = String.valueOf((int) SbProfile.num(m.get("bracket")));
            String type = SbProfile.str(m, "bracketType", null);
            int[] full;
            if (type != null) {
                String upper = type.toUpperCase(java.util.Locale.ROOT);
                Map<String, int[]> set = sets.getOrDefault(upper, sets.get(upper + "S"));
                full = set == null ? null : set.get(bracket);
            } else {
                full = brackets.get(bracket);
            }
            int[] tiers = new int[0];
            if (full != null && full.length > 0) {
                List<Integer> t = new ArrayList<>();
                for (int v : full) {
                    if (v < cap) {
                        t.add(v);
                    }
                }
                t.add(cap);
                tiers = t.stream().mapToInt(Integer::intValue).toArray();
            }
            out.add(new BestiaryFamily(cleanName(SbProfile.str(m, "name", "?")), SbProfile.str(m, "item", null),
                    fixTexture(SbProfile.str(m, "texture", null)), cap, strings(m.get("mobs")), tiers));
        }
    }

    private static Map<String, int[]> brackets(JsonObject obj) {
        Map<String, int[]> out = new LinkedHashMap<>();
        if (obj == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            long[] l = longs(e.getValue());
            int[] arr = new int[l.length];
            for (int i = 0; i < l.length; i++) {
                arr[i] = (int) l[i];
            }
            out.put(e.getKey(), arr);
        }
        return out;
    }

    /** The NEU file has mis-decoded "Â§" pairs; drop those and the color codes. */
    private static String cleanName(String raw) {
        return raw == null ? "" : raw.replace("Â", "").replaceAll("§.", "").trim();
    }

    private static String fixTexture(String tex) {
        if (tex == null || tex.isBlank()) {
            return null;
        }
        String t = tex.split("\", \"")[0].trim();
        while (t.length() % 4 != 0) {
            t += "=";
        }
        return t;
    }

    // ------------------------------------------------------------------ museum categories

    public record MuseumTables(Map<String, List<String>> categories, Map<String, Integer> maxValues) {
    }

    private static final Remote<MuseumTables> MUSEUM = new Remote<>(() ->
            ProfileViewerApi.getKeylessJson(MUSEUM_URL).thenApplyAsync(json -> {
                JsonObject items = json == null ? null : SbProfile.asObj(json.get("items"));
                if (items == null) {
                    return null;
                }
                Map<String, List<String>> cats = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : items.entrySet()) {
                    cats.put(e.getKey(), strings(e.getValue()));
                }
                Map<String, Integer> max = new LinkedHashMap<>();
                JsonObject mv = SbProfile.asObj(json.get("max_values"));
                if (mv != null) {
                    for (Map.Entry<String, JsonElement> e : mv.entrySet()) {
                        max.put(e.getKey(), (int) SbProfile.num(e.getValue()));
                    }
                }
                return new MuseumTables(cats, max);
            }, ProfileViewerApi.EXECUTOR), 0);

    public static CompletableFuture<MuseumTables> museum() {
        return MUSEUM.get();
    }

    // ------------------------------------------------------------------ lowest BIN

    private static final Remote<Map<String, Double>> LBIN = new Remote<>(() ->
            ProfileViewerApi.getKeylessJson(LBIN_URL).thenApplyAsync(json -> {
                if (json == null) {
                    return null;
                }
                Map<String, Double> out = new java.util.HashMap<>();
                for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                    double v = SbProfile.num(e.getValue());
                    if (v > 0) {
                        out.put(e.getKey(), v);
                    }
                }
                return out.isEmpty() ? null : out;
            }, ProfileViewerApi.EXECUTOR), PRICE_TTL_MS);

    public static CompletableFuture<Map<String, Double>> lowestBins() {
        return LBIN.get();
    }

    public static Set<String> set(List<String> list) {
        return new LinkedHashSet<>(list);
    }
}
