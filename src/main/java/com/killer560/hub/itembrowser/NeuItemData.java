package com.killer560.hub.itembrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.util.ModNet;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full item data from the NEU item repo (NotEnoughUpdates-REPO {@code items/<ID>.json}), fetched lazily per item the
 * first time the Item Browser shows its tooltip or recipe, then kept for the session. killer560, 2026-10-11:
 * "make it so hovering something there shows its tooltip" and "Some things are missing recipe data" - Hypixel's
 * public items resource has no lore and a recipe for only 1 of ~5,600 items, while NEU's repo has both for nearly
 * everything. Same keyless source {@code profileviewer.item.PetIcons} already reads pet heads from.
 * <p>
 * Parsed fields: {@code displayname} and {@code lore} (section-sign formatted, shown as-is), the 3x3 crafting
 * {@code recipe} ({@code A1..C3} = {@code "ID:count"}, plus an optional {@code count}), and every entry of
 * {@code recipes} - a crafting entry fills the grid if {@code recipe} did not, every other type (forge, NPC shop,
 * trade, kat upgrade, drops...) becomes one readable line.
 */
public final class NeuItemData {

    private static final String NEU_ITEMS = ModNet.url("github-raw",
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/");
    private static final String[] GRID = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};
    private static final int MAX_CACHED = 2048;

    /** One parsed item. {@code grid} has 9 entries (null = empty cell), or is null when there is no crafting recipe;
     *  {@code gridCounts} matches it. {@code otherRecipes} are ready-to-draw lines. */
    public record Info(String displayName, List<String> lore, List<String> grid, List<Integer> gridCounts,
                       int outputCount, List<String> otherRecipes) {

        public boolean hasCraftingRecipe() {
            return grid != null;
        }
    }

    private static final Map<String, CompletableFuture<Info>> CACHE = new ConcurrentHashMap<>();

    private NeuItemData() {
    }

    /** @return the item's data if it has arrived, else null - and starts fetching it. */
    public static Info get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (CACHE.size() > MAX_CACHED) {
            CACHE.clear();
        }
        CompletableFuture<Info> f = CACHE.computeIfAbsent(id, NeuItemData::fetch);
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    /** @return true once a fetch for {@code id} has finished, found or not. */
    public static boolean isSettled(String id) {
        CompletableFuture<Info> f = id == null ? null : CACHE.get(id);
        return f != null && f.isDone();
    }

    /** Test hook: seeds the cache with a repo JSON as if it had been fetched. */
    public static void put(String id, JsonObject json) {
        CACHE.put(id, CompletableFuture.completedFuture(parse(json)));
    }

    private static CompletableFuture<Info> fetch(String id) {
        String url = NEU_ITEMS + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20") + ".json";
        return ProfileViewerApi.getKeylessJson(url).thenApply(NeuItemData::parse).exceptionally(t -> null);
    }

    /** Parses one NEU repo item JSON. @return null for a null/non-item object. Public for the testkit. */
    public static Info parse(JsonObject json) {
        if (json == null) {
            return null;
        }
        String displayName = str(json, "displayname");
        List<String> lore = new ArrayList<>();
        if (json.has("lore") && json.get("lore").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("lore")) {
                if (e.isJsonPrimitive()) {
                    lore.add(e.getAsString());
                }
            }
        }
        List<String> grid = null;
        List<Integer> counts = null;
        int outputCount = 1;
        if (json.has("recipe") && json.get("recipe").isJsonObject()) {
            JsonObject recipe = json.getAsJsonObject("recipe");
            grid = new ArrayList<>(9);
            counts = new ArrayList<>(9);
            fillGrid(recipe, grid, counts);
            outputCount = intOr(recipe, "count", intOr(json, "count", 1));
            if (grid.stream().allMatch(x -> x == null)) {
                grid = null;
                counts = null;
            }
        }
        List<String> other = new ArrayList<>();
        if (json.has("recipes") && json.get("recipes").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("recipes")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject r = e.getAsJsonObject();
                String type = str(r, "type");
                if (type == null || type.equals("crafting")) {
                    if (grid == null) {
                        List<String> g = new ArrayList<>(9);
                        List<Integer> c = new ArrayList<>(9);
                        fillGrid(r, g, c);
                        if (!g.stream().allMatch(x -> x == null)) {
                            grid = g;
                            counts = c;
                            outputCount = intOr(r, "count", 1);
                        }
                    }
                    continue;
                }
                String line = describe(type, r);
                if (line != null) {
                    other.add(line);
                }
            }
        }
        return new Info(displayName, Collections.unmodifiableList(lore), grid, counts, Math.max(1, outputCount),
                Collections.unmodifiableList(other));
    }

    private static void fillGrid(JsonObject recipe, List<String> grid, List<Integer> counts) {
        for (String cell : GRID) {
            String raw = str(recipe, cell);
            if (raw == null || raw.isBlank()) {
                grid.add(null);
                counts.add(0);
                continue;
            }
            String[] parts = splitIdCount(raw);
            grid.add(parts[0]);
            counts.add(parseCount(parts[1]));
        }
    }

    /** "ENCHANTED_IRON:32" -> {"ENCHANTED_IRON", "32"}; "X" -> {"X", "1"}. */
    private static String[] splitIdCount(String raw) {
        int colon = raw.lastIndexOf(':');
        if (colon <= 0) {
            return new String[]{raw.trim(), "1"};
        }
        return new String[]{raw.substring(0, colon).trim(), raw.substring(colon + 1).trim()};
    }

    private static int parseCount(String s) {
        try {
            return Math.max(1, (int) Math.round(Double.parseDouble(s)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** One line for a non-crafting recipe, e.g. "Forge (1h): 4x Refined Mithril, 2x Enchanted Gold". */
    private static String describe(String type, JsonObject r) {
        String inputs = joinIds(r.has("inputs") ? r.get("inputs") : r.has("cost") ? r.get("cost") : null);
        return switch (type) {
            case "forge" -> "Forge" + (r.has("duration") ? " (" + duration(intOr(r, "duration", 0)) + ")" : "")
                    + (inputs.isEmpty() ? "" : ": " + inputs);
            case "npc_shop" -> "NPC Shop" + (inputs.isEmpty() ? "" : ": " + inputs);
            case "trade" -> "Trade" + (inputs.isEmpty() ? "" : ": " + inputs);
            case "katgrade" -> "Kat upgrade" + (inputs.isEmpty() ? "" : ": " + inputs);
            case "drops" -> "Dropped by " + (str(r, "name") != null ? stripCodes(str(r, "name")) : "mobs");
            default -> Character.toUpperCase(type.charAt(0)) + type.substring(1).replace('_', ' ')
                    + (inputs.isEmpty() ? "" : ": " + inputs);
        };
    }

    private static String joinIds(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return "";
        }
        JsonArray arr = e.getAsJsonArray();
        StringBuilder sb = new StringBuilder();
        for (JsonElement x : arr) {
            if (!x.isJsonPrimitive()) {
                continue;
            }
            String[] parts = splitIdCount(x.getAsString());
            if (sb.length() > 0) {
                sb.append(", ");
            }
            int count = parseCount(parts[1]);
            sb.append(count > 1 ? count + "x " : "").append(nameFor(parts[0]));
        }
        return sb.toString();
    }

    /** Display name for an ingredient id from the catalog, or the id prettified when it is not in it. */
    public static String nameFor(String id) {
        if ("SKYBLOCK_COIN".equals(id)) {
            return "Coins";
        }
        SkyblockItemEntry entry = SkyblockItemRepository.findById(id);
        if (entry != null) {
            return entry.name();
        }
        String[] words = id.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String duration(int seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String stripCodes(String s) {
        return s.replaceAll("§.", "");
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
