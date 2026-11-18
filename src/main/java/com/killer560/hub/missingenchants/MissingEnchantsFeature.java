package com.killer560.hub.missingenchants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.enchantcolors.EnchantColorsDefaults;
import com.killer560.hub.enchantcolors.EnchantColorsFeature;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.reforge.ReforgeMenu;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.Keys;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Missing Enchants: holding Shift over a SkyBlock item adds a section listing every normal enchant its type can take that it does
 * not have. Ultimate enchants are never listed (not as missing, not as below max). An enchant that conflicts with one the item has
 * (the groups in {@code assets/killer560smod/skyblock/missing-enchants.json}, each confirmed against current SkyBlock wiki pages)
 * and the "never" list (Knockback) are left out. Optionally a second list of enchants below their highest level.
 * The enchant types come from the item's lore rarity line, the enchants from its custom data {@code enchantments} map, and the
 * names and top levels from {@link EnchantColorsFeature} / {@link EnchantColorsDefaults}.
 * The computed text is cached per stack (bounded, cleared on disconnect and on a setting change), so a frame costs a map lookup.
 */
public final class MissingEnchantsFeature {

    private static final String PATH = "/assets/killer560smod/skyblock/missing-enchants.json";
    private static final int PER_LINE = 4;
    private static final int MAX_CACHE = 256;

    private record Data(Map<String, Set<String>> types, List<Set<String>> pools, Map<String, String> aliases, Set<String> never) {
    }

    /** Display text of one item's sections, computed once. */
    public record Result(List<String> missing, List<String> below) {
        boolean empty() {
            return missing.isEmpty() && below.isEmpty();
        }
    }

    private record Entry(CustomData data, Result result) {
    }

    private static final Result NONE = new Result(List.of(), List.of());
    private static final Map<ItemStack, Entry> CACHE = new WeakHashMap<>();
    private static volatile boolean clearRequested;
    private static volatile Data data;
    private static volatile boolean loading;
    /** Testkit: forces the Shift state (null = the real keys). */
    public static volatile Boolean shiftForTest;

    private MissingEnchantsFeature() {
    }

    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearRequested = true);
    }

    public static void clearCache() {
        clearRequested = true;
    }

    /** Loads the data file now (the tooltip path loads it on a worker thread the first time it is needed). */
    public static void loadNow() {
        if (data != null) {
            return;
        }
        try (var in = MissingEnchantsFeature.class.getResourceAsStream(PATH)) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Set<String>> types = new HashMap<>();
            for (var e : root.getAsJsonObject("types").entrySet()) {
                types.put(e.getKey(), strings(e.getValue()));
            }
            List<Set<String>> pools = new ArrayList<>();
            for (JsonElement p : root.getAsJsonArray("pools")) {
                pools.add(strings(p));
            }
            Map<String, String> aliases = new HashMap<>();
            for (var e : root.getAsJsonObject("aliases").entrySet()) {
                aliases.put(e.getKey(), e.getValue().getAsString());
            }
            data = new Data(types, pools, aliases, strings(root.get("never")));
        } catch (Exception e) {
            com.killer560.hub.util.ModLog.get("killer560smod-missingenchants").error("Could not read missing-enchants.json", e);
            data = new Data(Map.of(), List.of(), Map.of(), Set.of());
        }
    }

    private static Set<String> strings(JsonElement arr) {
        Set<String> s = new java.util.LinkedHashSet<>();
        arr.getAsJsonArray().forEach(e -> s.add(e.getAsString()));
        return s;
    }

    /** The data file's item type for the lore type word(s) ("HOE" is a farming tool, "SHORT_BOW" a bow). */
    static String dataType(String loreType) {
        return switch (loreType) {
            case "HOE" -> "FARMING_TOOL";
            case "SHORT_BOW" -> "BOW";
            default -> loreType;
        };
    }

    /** The sections for an item of lore type {@code loreType} holding {@code have} (enchant id -> level); {@link #NONE}-like when the type takes none. Needs {@link #loadNow}. */
    public static Result compute(String loreType, Map<String, Integer> have, boolean belowMax) {
        Data d = data;
        Set<String> takes = d == null ? null : d.types.get(dataType(loreType));
        if (takes == null) {
            return NONE;
        }
        Map<String, Integer> mine = new HashMap<>();
        have.forEach((id, lvl) -> {
            String k = id.toLowerCase(Locale.ROOT);
            mine.put(d.aliases.getOrDefault(k, k), lvl);
        });
        List<String> missing = new ArrayList<>();
        for (String id : takes) {
            if (!mine.containsKey(id) && !d.never.contains(id) && !conflicts(d, id, mine.keySet())) {
                missing.add(name(id));
            }
        }
        missing.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> below = new ArrayList<>();
        if (belowMax) {
            mine.forEach((id, lvl) -> {
                int[] tier = id.startsWith("ultimate_") ? null : EnchantColorsDefaults.TIERS.get(EnchantColorsFeature.loreName(id));
                // good level 0 with a top of 10 is a stacking enchant (Champion, Compact): its level is a counter, not a tier
                if (tier != null && lvl < tier[1] && !(tier[0] == 0 && tier[1] >= 10)) {
                    below.add(name(id) + " " + lvl + " -> " + tier[1]);
                }
            });
            below.sort(String.CASE_INSENSITIVE_ORDER);
        }
        return new Result(missing, below);
    }

    private static boolean conflicts(Data d, String id, Set<String> have) {
        for (Set<String> pool : d.pools) {
            if (pool.contains(id)) {
                for (String other : pool) {
                    if (!other.equals(id) && have.contains(other)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** "bane_of_arthropods" -> "Bane of Arthropods", through the name Hypixel prints for the id. */
    private static String name(String id) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char ch : EnchantColorsFeature.loreName(id).toCharArray()) {
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = ch == ' ' || ch == '-';
        }
        return sb.toString().replace(" Of ", " of ");
    }

    private static boolean shiftDown() {
        Boolean f = shiftForTest;
        if (f != null) {
            return f;
        }
        var window = Minecraft.getInstance().getWindow();
        return KeyUtil.isKeyDown(window, Keys.LEFT_SHIFT) || KeyUtil.isKeyDown(window, Keys.RIGHT_SHIFT);
    }

    private static Result build(ItemStack stack, CustomData cd, boolean belowMax) {
        String[] rt = ReforgeMenu.rarityAndType(stack);
        if (rt == null || rt[1].isEmpty()) {
            return NONE;
        }
        CompoundTag tag = cd.copyTag();
        if (tag.getStringOr("id", "").isEmpty()) {
            return NONE;
        }
        CompoundTag enchants = tag.getCompoundOrEmpty("enchantments");
        Map<String, Integer> have = new HashMap<>();
        for (String id : enchants.keySet()) {
            have.put(id, enchants.getIntOr(id, 1));
        }
        Result r = compute(rt[1], have, belowMax);
        return r.empty() ? NONE : r;
    }

    /**
     * Called from the tooltip mixin: {@code lines} unchanged, or a copy with the hint (no Shift) or the sections (Shift) added.
     */
    public static List<Component> append(ItemStack stack, List<Component> lines) {
        MissingEnchantsConfig cfg = MissingEnchantsConfig.getInstance();
        if (!cfg.isEnabled() || stack == null || stack.isEmpty() || lines == null) {
            return lines;
        }
        if (data == null) {
            if (!loading) {
                loading = true;
                Thread t = new Thread(MissingEnchantsFeature::loadNow, "killer560smod-missingenchants-load");
                t.setDaemon(true);
                t.start();
            }
            return lines;
        }
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return lines;
        }
        if (clearRequested) {
            clearRequested = false;
            CACHE.clear();
        }
        Entry e = CACHE.get(stack);
        if (e == null || e.data != cd) {
            if (CACHE.size() >= MAX_CACHE) {
                CACHE.clear();
            }
            e = new Entry(cd, build(stack, cd, cfg.isBelowMax()));
            CACHE.put(stack, e);
        }
        Result r = e.result;
        if (r.empty()) {
            return lines;
        }
        List<Component> out = new ArrayList<>(lines.size() + 8);
        out.addAll(lines);
        if (!shiftDown()) {
            if (cfg.isHint() && !r.missing.isEmpty()) {
                out.add(Component.literal("Shift: missing enchants").withStyle(s -> s.withColor(TextColor.fromRgb(0x555555))));
            }
            return out.size() == lines.size() ? lines : out;
        }
        int accent = ThemeAccent.light(0xFFFFAA00) & 0xFFFFFF;
        section(out, "Missing Enchants", r.missing, accent, PER_LINE);
        section(out, "Below Max Level", r.below, accent, 2);
        return out;
    }

    private static void section(List<Component> out, String title, List<String> items, int accent, int perLine) {
        if (items.isEmpty()) {
            return;
        }
        out.add(Component.empty());
        out.add(Component.literal(title).withStyle(s -> s.withColor(TextColor.fromRgb(accent))));
        for (int i = 0; i < items.size(); i += perLine) {
            out.add(Component.literal(String.join(", ", items.subList(i, Math.min(items.size(), i + perLine))))
                    .withStyle(s -> s.withColor(TextColor.fromRgb(0xAAAAAA))));
        }
    }
}
