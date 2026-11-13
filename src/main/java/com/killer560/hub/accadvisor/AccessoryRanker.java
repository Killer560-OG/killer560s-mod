package com.killer560.hub.accadvisor;

import com.killer560.hub.accadvisor.AccessoryAdvisorConfig.Mode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Ranks the accessories worth buying next, from what the bag holds, the accessory list and prices. Pure: no game state,
 * so the testkit can call it with fixture data.
 * <p>
 * Magical Power per rarity is Hypixel's table (Common 3 ... Mythic 22, Divine 28, Special 3, Very Special 5). Only the best
 * member of an upgrade family counts, so a family's current power is its best owned member's, and buying a higher member
 * gains the difference. Hegemony Artifact counts twice and the Rift Prism is a fixed 11. Owned rarity is the one the bag
 * shows (a recombobulated accessory counts one tier up); a buy is priced and counted at its base rarity.
 */
public final class AccessoryRanker {

    /** One accessory Hypixel lists: id, display name, base rarity. */
    public record Acc(String id, String name, String tier) {
    }

    /** One suggested buy. {@code upgrade}: its family is already in the bag. */
    public record Row(String id, String name, int mpGain, double price, boolean upgrade) {
        public double coinsPerMp() {
            return price / mpGain;
        }
    }

    /** Ranked rows (at most the limit) and how many buys had no price. */
    public record Result(List<Row> rows, int unpriced) {
    }

    /** In Best Overall a family's top member wins when its coins per MP is within this factor of the family's best step. */
    static final double SKIP_AHEAD = 1.25;

    private AccessoryRanker() {
    }

    public static int mp(String id, String tier) {
        if ("RIFT_PRISM".equals(id)) {
            return 11;
        }
        int base = switch (tier == null ? "COMMON" : tier.toUpperCase(Locale.ROOT).replace(' ', '_')) {
            case "UNCOMMON", "VERY_SPECIAL" -> 5;
            case "RARE" -> 8;
            case "EPIC" -> 12;
            case "LEGENDARY" -> 16;
            case "MYTHIC", "ADMIN" -> 22;
            case "DIVINE" -> 28;
            case "ULTIMATE", "SUPREME" -> 1;
            default -> 3; // COMMON, SPECIAL
        };
        return "HEGEMONY_ARTIFACT".equals(id) ? base * 2 : base;
    }

    /**
     * @param catalog every accessory by id
     * @param familyOf id -> its family's key (ids missing from it are their own family)
     * @param ignored ids never suggested (not obtainable, event-only)
     * @param owned id -> the rarity it shows in the bag
     * @param price coins for one, 0 or less when unknown
     */
    public static Result rank(Map<String, Acc> catalog, Map<String, String> familyOf, Set<String> ignored,
                              Map<String, String> owned, ToDoubleFunction<String> price, Mode mode, int limit) {
        Map<String, Integer> familyMp = new HashMap<>();
        for (Map.Entry<String, String> e : owned.entrySet()) {
            familyMp.merge(familyOf.getOrDefault(e.getKey(), e.getKey()), mp(e.getKey(), e.getValue()), Math::max);
        }
        Map<String, List<Row>> byFamily = new HashMap<>();
        int unpriced = 0;
        for (Acc a : catalog.values()) {
            if (owned.containsKey(a.id()) || ignored.contains(a.id())) {
                continue;
            }
            String family = familyOf.getOrDefault(a.id(), a.id());
            Integer have = familyMp.get(family);
            int gain = mp(a.id(), a.tier()) - (have == null ? 0 : have);
            if (gain <= 0) {
                continue;
            }
            boolean upgrade = have != null;
            if ((mode == Mode.UPGRADE_ONLY && !upgrade) || (mode == Mode.BUY_NEW && upgrade)) {
                continue;
            }
            double p = price.applyAsDouble(a.id());
            if (!(p > 0)) {
                unpriced++;
                continue;
            }
            byFamily.computeIfAbsent(family, k -> new ArrayList<>(4))
                    .add(new Row(a.id(), a.name() == null ? a.id() : a.name(), gain, p, upgrade));
        }
        Comparator<Row> cpm = Comparator.comparingDouble(Row::coinsPerMp).thenComparing(Row::id);
        Comparator<Row> cheapest = Comparator.comparingDouble(Row::price).thenComparing(Row::id);
        Comparator<Row> most = Comparator.comparingInt(Row::mpGain).reversed().thenComparing(cheapest);
        List<Row> out = new ArrayList<>();
        for (List<Row> steps : byFamily.values()) {
            switch (mode) {
                case CHEAPEST_PER_MP -> out.addAll(steps);
                case CHEAPEST -> out.add(steps.stream().min(cheapest).orElseThrow());
                case MOST_MP -> out.add(steps.stream().min(most).orElseThrow());
                default -> out.add(bestStep(steps, cpm));
            }
        }
        out.sort(switch (mode) {
            case CHEAPEST -> cheapest;
            case MOST_MP -> most;
            default -> cpm;
        });
        return new Result(out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out), unpriced);
    }

    /** The family's best coins per MP, or its biggest step when that costs at most {@link #SKIP_AHEAD} times as much per
     *  MP: buying the top member at once beats buying each lower one and replacing it. */
    private static Row bestStep(List<Row> steps, Comparator<Row> cpm) {
        Row best = steps.stream().min(cpm).orElseThrow();
        Row top = best;
        for (Row r : steps) {
            if (r.mpGain() > top.mpGain() && r.coinsPerMp() <= best.coinsPerMp() * SKIP_AHEAD) {
                top = r;
            }
        }
        return top;
    }
}
