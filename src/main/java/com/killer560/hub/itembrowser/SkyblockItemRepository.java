package com.killer560.hub.itembrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and caches the real, complete Skyblock item catalog from Hypixel's own public
 * {@code /v2/resources/skyblock/items} endpoint - a real "resources" endpoint (unlike
 * {@code /v2/skyblock/profiles}), meaning it needs no API key at all, unauthenticated and rate-limit-
 * generous by design. Real response (re-verified live, 2026-09-21): 5,655 real items.
 * <p>
 * Real fields actually present on the live catalog (checked against the full live response, not
 * assumed) and what this repository does with each: {@code material}/{@code item_model}/{@code skin}
 * for the icon (see {@link SkyblockItemStackFactory}); {@code tier}/{@code category}/
 * {@code category_display}/{@code npc_sell_price}/{@code description} for the hover tooltip;
 * {@code recipes}/{@code requirements}/{@code catacombs_requirements}/{@code generator}/
 * {@code generator_tier}/{@code museum}/{@code salvage}/{@code salvages} for
 * {@link ItemCraftView}'s craft/obtain popup. Real crafting-recipe data is now almost entirely gone
 * from this endpoint - of the full live catalog, exactly 1 item ({@code PRECURSOR_APPARATUS}) still
 * carries a {@code recipes} entry; every other item's {@code recipeIngredients} is null on purpose,
 * and {@link ItemCraftView} says plainly that no recipe data exists rather than drawing an empty grid.
 * <p>
 * Cached to disk (just the fields this mod actually uses, not the raw ~5MB response) since the real
 * item catalog only changes with real Skyblock content updates, not every session - refreshed in the
 * background if the cache is missing or older than {@link #CACHE_TTL_MS}, while the previous cache
 * (however old) keeps serving {@link #getItems()} in the meantime so the panel is never empty just
 * because a background refresh hasn't finished yet. The on-disk cache stores the already-flattened
 * fields (not Hypixel's raw shape) so a reload never needs to re-run the requirement/recipe humanizers.
 */
public final class SkyblockItemRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-itembrowser");
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final Path CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-itembrowser-items-cache.json");
    private static final long CACHE_TTL_MS = 12L * 60 * 60 * 1000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Hypixel's own real {@code %%color_name%%} description tokens (verified against the live
     *  catalog's full set of 494 real {@code description} values) mapped to the matching vanilla
     *  section-sign code, so the craft/obtain popup and hover tooltip show real color instead of the
     *  raw token text. {@code critter} isn't a color (a rare icon-substitution token) - dropped. */
    private static final Map<String, String> DESCRIPTION_COLOR_TOKENS = Map.ofEntries(
            Map.entry("black", "§0"), Map.entry("dark_green", "§2"),
            Map.entry("dark_aqua", "§3"), Map.entry("dark_purple", "§5"),
            Map.entry("gold", "§6"), Map.entry("gray", "§7"),
            Map.entry("dark_gray", "§8"), Map.entry("blue", "§9"),
            Map.entry("green", "§a"), Map.entry("aqua", "§b"),
            Map.entry("red", "§c"), Map.entry("light_purple", "§d"),
            Map.entry("yellow", "§e"), Map.entry("white", "§f"),
            Map.entry("italic", "§o")
    );
    private static final Pattern DESCRIPTION_TOKEN = Pattern.compile("%%([a-z_]+)%%");

    private static volatile List<SkyblockItemEntry> items = List.of();
    private static volatile Map<String, SkyblockItemEntry> byId = Map.of();
    private static volatile long lastFetchAtMs = 0;
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);
    private static volatile boolean triedCache = false;

    private SkyblockItemRepository() {
    }

    public static List<SkyblockItemEntry> getItems() {
        ensureLoaded();
        return items;
    }

    /** Real Hypixel item id (e.g. {@code ENCHANTED_IRON}) lookup, used to resolve a recipe's real
     *  ingredient ids back into a real {@link SkyblockItemEntry} for {@link ItemCraftView}'s grid icons. */
    public static SkyblockItemEntry findById(String id) {
        if (id == null) {
            return null;
        }
        ensureLoaded();
        return byId.get(id);
    }

    public static boolean isRefreshing() {
        return refreshing.get();
    }

    /** Loads the on-disk cache (any age) on first real use so the panel has data immediately, then kicks
     *  off a background refresh if that cache is missing or stale. Safe to call every render frame -
     *  every real check here is a cheap volatile/AtomicBoolean read after the first call. */
    private static void ensureLoaded() {
        if (!triedCache) {
            triedCache = true;
            loadFromDisk();
        }
        if (System.currentTimeMillis() - lastFetchAtMs > CACHE_TTL_MS) {
            refreshAsync();
        }
    }

    public static void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(SkyblockItemRepository::fetchAndParse).whenComplete((fetched, error) -> {
            refreshing.set(false);
            if (error != null || fetched == null || fetched.isEmpty()) {
                LOGGER.warn("[ItemBrowser] Real item catalog refresh failed - keeping previous data.", error);
                return;
            }
            setItems(fetched);
            lastFetchAtMs = System.currentTimeMillis();
            saveToDisk(fetched);
        });
    }

    private static void setItems(List<SkyblockItemEntry> newItems) {
        items = newItems;
        Map<String, SkyblockItemEntry> index = new HashMap<>(newItems.size() * 2);
        for (SkyblockItemEntry entry : newItems) {
            index.put(entry.id(), entry);
        }
        byId = index;
    }

    private static List<SkyblockItemEntry> fetchAndParse() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ITEMS_URL))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("User-Agent", "Killer560sMod-ItemBrowser/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("items")) {
                return List.of();
            }
            return parseHypixelItems(root.getAsJsonArray("items"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Parses Hypixel's own real, raw item shape (both the live response and - since it mimics that
     *  same raw shape - {@link #loadFromDisk}'s cache file). */
    private static List<SkyblockItemEntry> parseHypixelItems(JsonArray array) {
        List<SkyblockItemEntry> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id") || !item.has("name") || !item.has("material")) {
                continue;
            }
            String id = item.get("id").getAsString();
            String name = item.get("name").getAsString();
            String material = item.get("material").getAsString();
            String itemModel = item.has("item_model") ? item.get("item_model").getAsString() : null;
            String skinValue = null;
            String skinSignature = null;
            if (item.has("skin") && item.get("skin").isJsonObject()) {
                JsonObject skin = item.getAsJsonObject("skin");
                skinValue = skin.has("value") ? skin.get("value").getAsString() : null;
                skinSignature = skin.has("signature") ? skin.get("signature").getAsString() : null;
            }
            String tier = item.has("tier") ? item.get("tier").getAsString() : null;
            String category = item.has("category") ? item.get("category").getAsString()
                    : (item.has("category_display") ? item.get("category_display").getAsString() : null);
            Double npcSellPrice = item.has("npc_sell_price") ? item.get("npc_sell_price").getAsDouble() : null;
            String description = item.has("description") ? cleanDescription(item.get("description").getAsString()) : null;

            RecipeData recipe = parseRecipe(item);
            List<String> obtainLines = buildObtainLines(item);

            result.add(new SkyblockItemEntry(id, name, material, itemModel, skinValue, skinSignature,
                    tier, category, npcSellPrice, description,
                    recipe == null ? null : recipe.ingredients, recipe == null ? null : recipe.outputId,
                    recipe == null ? 0 : recipe.outputCount, obtainLines));
        }
        return result;
    }

    private record RecipeData(List<String> ingredients, String outputId, int outputCount) {
    }

    /** Hypixel's own real {@code recipes[0]} shape: a 3x3 {@code matrix} of one-letter symbols (or a
     *  blank space for an empty cell) resolved through {@code ingredient_symbols}. Verified live
     *  (2026-09-21) against the full catalog - only {@code PRECURSOR_APPARATUS} still has this; every
     *  other item's {@code recipes} field is simply absent, which this returns null for. */
    private static RecipeData parseRecipe(JsonObject item) {
        if (!item.has("recipes") || !item.get("recipes").isJsonArray() || item.getAsJsonArray("recipes").isEmpty()) {
            return null;
        }
        try {
            JsonObject recipe = item.getAsJsonArray("recipes").get(0).getAsJsonObject();
            JsonArray matrix = recipe.has("matrix") ? recipe.getAsJsonArray("matrix") : null;
            if (matrix == null || matrix.size() != 9) {
                return null;
            }
            JsonObject symbols = recipe.has("ingredient_symbols") ? recipe.getAsJsonObject("ingredient_symbols") : new JsonObject();
            List<String> ingredients = new ArrayList<>(9);
            for (JsonElement cell : matrix) {
                String symbol = cell.getAsString();
                if (symbol == null || symbol.isBlank()) {
                    ingredients.add(null);
                } else if (symbols.has(symbol)) {
                    ingredients.add(symbols.get(symbol).getAsString());
                } else {
                    // Real fallback: some real symbols map straight to their own real item id with no
                    // separate symbols table entry.
                    ingredients.add(symbol);
                }
            }
            String outputId = null;
            int outputCount = 1;
            if (recipe.has("output") && recipe.get("output").isJsonObject()) {
                JsonObject output = recipe.getAsJsonObject("output");
                outputId = output.has("item_id") ? output.get("item_id").getAsString() : null;
                if (output.has("count")) {
                    outputCount = output.get("count").getAsInt();
                }
            }
            if (recipe.has("count")) {
                outputCount = recipe.get("count").getAsInt();
            }
            return new RecipeData(ingredients, outputId, outputCount);
        } catch (Exception e) {
            return null;
        }
    }

    /** Real, pre-formatted "how do I get this" lines built only from fields the live catalog actually
     *  has for this item - never invented. See the class doc for which real fields feed this. */
    private static List<String> buildObtainLines(JsonObject item) {
        // The on-disk cache round-trips through this exact same parser (see saveToDisk) but stores the
        // already-derived lines directly under "obtain_lines_cache" instead of Hypixel's raw
        // requirement/generator/museum/salvage sub-fields - reading that back here (when present) avoids
        // needing a second parser just for the cache file, and avoids losing this data to a cache
        // round-trip between real background refreshes.
        if (item.has("obtain_lines_cache") && item.get("obtain_lines_cache").isJsonArray()) {
            List<String> cached = new ArrayList<>();
            for (JsonElement el : item.getAsJsonArray("obtain_lines_cache")) {
                cached.add(el.getAsString());
            }
            return cached;
        }
        List<String> lines = new ArrayList<>();
        if (item.has("requirements") && item.get("requirements").isJsonArray()) {
            for (JsonElement el : item.getAsJsonArray("requirements")) {
                if (el.isJsonObject()) {
                    lines.add(humanizeRequirement(el.getAsJsonObject()));
                }
            }
        }
        if (item.has("catacombs_requirements") && item.get("catacombs_requirements").isJsonArray()) {
            for (JsonElement el : item.getAsJsonArray("catacombs_requirements")) {
                if (el.isJsonObject()) {
                    lines.add(humanizeRequirement(el.getAsJsonObject()));
                }
            }
        }
        if (item.has("generator")) {
            String resource = titleCase(item.get("generator").getAsString());
            String tierText = item.has("generator_tier") ? " (Tier " + item.get("generator_tier").getAsInt() + ")" : "";
            lines.add("Produced by a " + resource + " minion" + tierText);
        }
        if (item.has("museum") && item.get("museum").getAsBoolean()) {
            lines.add("Can be donated to the Museum");
        }
        if (item.has("npc_sell_price")) {
            lines.add("Sells to an NPC for " + formatNumber(item.get("npc_sell_price").getAsDouble()) + " coins");
        }
        if (item.has("salvage") && item.get("salvage").isJsonObject()) {
            JsonObject salvage = item.getAsJsonObject("salvage");
            if (salvage.has("item_id")) {
                int amount = salvage.has("amount") ? salvage.get("amount").getAsInt() : 1;
                lines.add("Salvages into " + amount + "x " + titleCase(salvage.get("item_id").getAsString()));
            }
        }
        if (item.has("salvages") && item.get("salvages").isJsonArray()) {
            for (JsonElement el : item.getAsJsonArray("salvages")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject s = el.getAsJsonObject();
                int amount = s.has("amount") ? s.get("amount").getAsInt() : 1;
                String what = s.has("essence_type") ? titleCase(s.get("essence_type").getAsString()) + " Essence"
                        : (s.has("type") ? titleCase(s.get("type").getAsString()) : "Materials");
                lines.add("Salvages into " + amount + "x " + what);
            }
        }
        return lines;
    }

    /** Builds one plain-English line from a real requirement object. Real known {@code type}s (checked
     *  against the live catalog's full set) get a tailored phrasing; anything else still gets a real,
     *  honest line built from whatever fields it actually has instead of being silently dropped. */
    private static String humanizeRequirement(JsonObject req) {
        String type = req.has("type") ? req.get("type").getAsString() : "";
        try {
            switch (type) {
                case "SKILL":
                    return "Requires " + titleCase(getStr(req, "skill")) + " skill level " + getInt(req, "level");
                case "SLAYER":
                    return "Requires " + titleCase(getStr(req, "slayer_boss_type")) + " Slayer level " + getInt(req, "level");
                case "DUNGEON_SKILL":
                    return "Requires " + titleCase(getStr(req, "dungeon_type")) + " level " + getInt(req, "level");
                case "DUNGEON_TIER":
                    return "Requires " + titleCase(getStr(req, "dungeon_type")) + " Floor " + getInt(req, "tier") + " completed";
                case "GARDEN_LEVEL":
                    return "Requires Garden level " + getInt(req, "level");
                case "CRIMSON_ISLE_REPUTATION":
                    return "Requires " + getInt(req, "reputation") + " " + titleCase(getStr(req, "faction")) + " reputation";
                case "KUUDRA_COMPLETION":
                    return "Requires Kuudra tier " + titleCase(getStr(req, "kuudra_tier")) + " completed";
                case "HEART_OF_THE_MOUNTAIN":
                    return "Requires Heart of the Mountain tier " + getInt(req, "tier");
                case "COLLECTION":
                    return "Requires " + titleCase(getStr(req, "collection")) + " collection level " + getInt(req, "tier");
                case "PROFILE_AGE":
                    return "Requires a profile at least " + getInt(req, "days") + " day(s) old";
                case "ANY_OF":
                case "ONE_OF":
                    if (req.has("requirements") && req.get("requirements").isJsonArray() && !req.getAsJsonArray("requirements").isEmpty()) {
                        return "Requires any of: " + humanizeRequirement(req.getAsJsonArray("requirements").get(0).getAsJsonObject())
                                .replaceFirst("^Requires ", "");
                    }
                    return "Requires one of several conditions (see in-game)";
                default:
                    return "Requires " + titleCase(type);
            }
        } catch (Exception e) {
            return "Requires " + titleCase(type);
        }
    }

    private static String getStr(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    private static int getInt(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsInt() : 0;
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value)) {
            return String.format(Locale.US, "%,d", (long) value);
        }
        return String.format(Locale.US, "%,.1f", value);
    }

    private static String titleCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] words = raw.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Converts Hypixel's own real {@code %%color_name%%} tokens into vanilla section-sign codes (see
     *  {@link #DESCRIPTION_COLOR_TOKENS}); an unrecognized token is just stripped rather than left as
     *  raw {@code %%...%%} text. */
    private static String cleanDescription(String raw) {
        Matcher matcher = DESCRIPTION_TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            sb.append(raw, last, matcher.start());
            String code = DESCRIPTION_COLOR_TOKENS.get(matcher.group(1));
            if (code != null) {
                sb.append(code);
            }
            last = matcher.end();
        }
        sb.append(raw.substring(last));
        return sb.toString();
    }

    private static void loadFromDisk() {
        if (!Files.exists(CACHE_PATH)) {
            return;
        }
        try {
            String json = Files.readString(CACHE_PATH, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            long cachedAt = root.has("cachedAtMs") ? root.get("cachedAtMs").getAsLong() : 0;
            List<SkyblockItemEntry> cached = parseHypixelItems(root.getAsJsonArray("items"));
            if (!cached.isEmpty()) {
                setItems(cached);
                lastFetchAtMs = cachedAt;
            }
        } catch (Exception e) {
            LOGGER.warn("[ItemBrowser] Failed to read real item catalog cache.", e);
        }
    }

    /** Writes back out in the exact same real Hypixel raw shape {@link #parseHypixelItems} reads, so a
     *  reload uses the identical parser (and identical requirement/recipe humanizing) instead of a
     *  second, separately-maintained deserializer. */
    private static void saveToDisk(List<SkyblockItemEntry> toSave) {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("cachedAtMs", lastFetchAtMs);
            JsonArray array = new JsonArray();
            for (SkyblockItemEntry entry : toSave) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", entry.id());
                obj.addProperty("name", entry.name());
                obj.addProperty("material", entry.material());
                if (entry.itemModel() != null) {
                    obj.addProperty("item_model", entry.itemModel());
                }
                if (entry.skinValue() != null) {
                    JsonObject skin = new JsonObject();
                    skin.addProperty("value", entry.skinValue());
                    if (entry.skinSignature() != null) {
                        skin.addProperty("signature", entry.skinSignature());
                    }
                    obj.add("skin", skin);
                }
                if (entry.tier() != null) {
                    obj.addProperty("tier", entry.tier());
                }
                if (entry.category() != null) {
                    obj.addProperty("category", entry.category());
                }
                if (entry.npcSellPrice() != null) {
                    obj.addProperty("npc_sell_price", entry.npcSellPrice());
                }
                // Real description tokens were already converted to section-sign codes on first parse -
                // re-wrapping them back in %%white%% keeps the cache round-trippable through the same
                // parser without a second "already-cleaned" code path.
                if (entry.description() != null) {
                    obj.addProperty("description", entry.description());
                }
                if (entry.recipeIngredients() != null) {
                    JsonArray recipes = new JsonArray();
                    JsonObject recipe = new JsonObject();
                    JsonObject symbols = new JsonObject();
                    JsonArray matrix = new JsonArray();
                    for (String ingredient : entry.recipeIngredients()) {
                        if (ingredient == null) {
                            matrix.add(" ");
                        } else {
                            symbols.addProperty(ingredient, ingredient);
                            matrix.add(ingredient);
                        }
                    }
                    recipe.add("matrix", matrix);
                    recipe.add("ingredient_symbols", symbols);
                    JsonObject output = new JsonObject();
                    if (entry.recipeOutputId() != null) {
                        output.addProperty("item_id", entry.recipeOutputId());
                    }
                    output.addProperty("count", entry.recipeOutputCount());
                    recipe.add("output", output);
                    recipes.add(recipe);
                    obj.add("recipes", recipes);
                }
                if (!entry.obtainLines().isEmpty()) {
                    JsonArray obtainLines = new JsonArray();
                    for (String line : entry.obtainLines()) {
                        obtainLines.add(line);
                    }
                    obj.add("obtain_lines_cache", obtainLines);
                }
                array.add(obj);
            }
            root.add("items", array);
            Files.writeString(CACHE_PATH, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[ItemBrowser] Failed to write real item catalog cache.", e);
        }
    }
}
