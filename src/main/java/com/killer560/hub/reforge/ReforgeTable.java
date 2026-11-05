package com.killer560.hub.reforge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Blacksmith's basic reforges, from {@code assets/killer560smod/reforge/reforges.json}: built by
 * {@code tools/reforge-table/build_reforges.py} from the NotEnoughUpdates-REPO's {@code constants/reforges.json} (the
 * source SkyHanni's Reforge Helper reads too), never typed by hand. Read once, on the first Reforge Item menu.
 */
public final class ReforgeTable {

    static final String RESOURCE = "/assets/killer560smod/reforge/reforges.json";

    /** One reforge: its name, NBT {@code modifier}, NEU item type ("SWORD/ROD", "ARMOR", ...) and stats per rarity. */
    public record Reforge(String name, String modifier, String type, Map<String, Map<String, Double>> stats) {
    }

    private static List<Reforge> all;

    private ReforgeTable() {
    }

    public static synchronized List<Reforge> all() {
        if (all == null) {
            List<Reforge> out = new ArrayList<>();
            try (InputStream in = ReforgeTable.class.getResourceAsStream(RESOURCE)) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement e : root.getAsJsonArray("reforges")) {
                    JsonObject o = e.getAsJsonObject();
                    Map<String, Map<String, Double>> stats = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> r : o.getAsJsonObject("s").entrySet()) {
                        Map<String, Double> s = new LinkedHashMap<>();
                        r.getValue().getAsJsonObject().entrySet().forEach(x -> s.put(x.getKey(), x.getValue().getAsDouble()));
                        stats.put(r.getKey(), s);
                    }
                    out.add(new Reforge(o.get("n").getAsString(), o.get("m").getAsString(), o.get("t").getAsString(), stats));
                }
            } catch (Exception e) {
                ModLog.get("killer560smod-reforge").error("[Reforge Helper] could not read " + RESOURCE, e);
            }
            all = List.copyOf(out);
        }
        return all;
    }

    /** The reforge whose NBT modifier or name key is {@code key} ("fabled", "odd_sword"), or null. */
    public static Reforge byKey(String key) {
        for (Reforge r : all()) {
            if (r.modifier.equals(key) || ReforgeHelperConfig.key(r.name).equals(key)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The NEU item types an item of Hypixel lore type {@code category} ("SWORD", "DUNGEON_SWORD" read as "SWORD",
     * "FISHING_ROD") rolls on: SkyHanni's {@code ReforgeApi.ReforgeType} groups (a gauntlet takes sword and pickaxe
     * reforges; Hypixel's old HOE lore is a farming tool).
     */
    static List<String> typesFor(String category) {
        return switch (category) {
            case "SWORD", "LONGSWORD", "FISHING_ROD", "FISHING_WEAPON" -> List.of("SWORD/ROD");
            case "GAUNTLET" -> List.of("SWORD/ROD", "PICKAXE");
            case "BOW", "SHORT_BOW" -> List.of("BOW");
            case "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "CARNIVAL_MASK" -> List.of("ARMOR");
            case "NECKLACE", "CLOAK", "BELT", "GLOVES", "BRACELET" -> List.of("EQUIPMENT");
            case "AXE" -> List.of("AXE");
            case "HOE", "FARMING_TOOL" -> List.of("FARMING_TOOL");
            case "PICKAXE", "DRILL" -> List.of("PICKAXE");
            default -> List.of();
        };
    }

    /** Every reforge an item of {@code category} can roll at the Blacksmith, in the table's (alphabetical) order. */
    public static List<Reforge> forCategory(String category) {
        List<String> types = typesFor(category);
        List<Reforge> out = new ArrayList<>();
        for (Reforge r : all()) {
            if (types.contains(r.type)) {
                out.add(r);
            }
        }
        return out;
    }
}
