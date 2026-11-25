package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.data.ExtraTables;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SkyBlockPV's HotM / HotF node hover (SkillTreeScreen.getTooltip): the name green or red, "Level n/max", the perk text with
 * its formulas filled in for this level (the bundled {@code sbpv_tips} table, from the original's data repository), Powder
 * Spent per powder, then ENABLED / DISABLED or SELECTED. Built once per tree when the profile model is built, never per frame.
 */
final class SbpvTreeTips {
    private SbpvTreeTips() {
    }

    /** {@code type} is the node type (PERK, CORE, ABILITY, UNLEVELABLE); {@code level} -1 when not unlocked. */
    static List<Component> tip(String id, String name, String type, int level, int max, boolean disabled, boolean selected, int treeLevel) {
        JsonObject all = ExtraTables.bundled().getAsJsonObject("sbpv_tips");
        JsonObject e = all == null ? null : all.getAsJsonObject(id);
        List<String> out = new ArrayList<>();
        out.add((disabled || level == -1 ? "§c" : "§a") + name);
        boolean levelable = type.equals("PERK") || type.equals("CORE");
        if (levelable) {
            out.add(level == max ? "§7Level " + level : "§7Level " + Math.abs(level) + "/§8" + max);
            out.add("");
        }
        // the original's formula context: level at least 1, effectiveLevel = level - 1 (at least 0), tree level at least 1
        Map<String, Double> vars = Map.of("level", (double) Math.max(1, level), "effectiveLevel", (double) Math.max(0, level - 1),
                "hotmLevel", (double) Math.max(1, treeLevel), "hotfLevel", (double) Math.max(1, treeLevel));
        if (e != null && e.has("t")) {
            Map<String, String> values = new LinkedHashMap<>();
            if (e.has("f")) {
                for (Map.Entry<String, JsonElement> f : e.getAsJsonObject("f").entrySet()) {
                    String formula = f.getValue().getAsString();
                    // an unlevelable node with no reward formula ("0") fills %reward% with nothing
                    boolean none = type.equals("UNLEVELABLE") && (formula.isEmpty() || formula.equals("0"));
                    values.put(f.getKey(), none ? "" : number(eval(formula, vars)));
                }
            }
            for (String line : ExtraTables.strings(e.get("t"))) {
                for (Map.Entry<String, String> v : values.entrySet()) line = line.replace("%" + v.getKey() + "%", v.getValue());
                out.add(line.replace("%%", "%"));
            }
        } else if (e != null && e.has("l")) {
            coreRewards(e.getAsJsonArray("l"), Math.max(0, level), out);
        }
        Map<String, long[]> spent = new LinkedHashMap<>();
        if (e != null && level > 0 && !type.equals("ABILITY") && !type.equals("CORE") && levelable && e.has("c")) {
            JsonArray c = e.getAsJsonArray("c");
            long total = 0, done = 0;
            for (int l = 1; l < max; l++) {
                long cost = (long) eval(c.get(1).getAsString(), Map.of("level", (double) l, "nextLevel", (double) l + 1));
                total += cost;
                if (l < level || level == max) done += cost;
            }
            spent.put(c.get(0).getAsString(), new long[] {done, total});
        }
        if (type.equals("ABILITY")) {
            if (selected) {
                out.add("");
                out.add("§a§lSELECTED");
            }
        } else if (!type.equals("CORE")) {
            if (level > 0) {
                out.add("");
                if (!spent.isEmpty()) {
                    out.add("§7Powder Spent");
                    for (Map.Entry<String, long[]> s : spent.entrySet()) {
                        long[] v = s.getValue();
                        String pct = v[0] < v[1] ? " §e(" + SbpvPages.round(v[0] * 100.0 / v[1]) + "%)" : "";
                        out.add(costColor(s.getKey()) + costName(s.getKey()) + "§7: " + commas(v[0]) + "§7/§8" + commas(v[1]) + pct);
                    }
                    out.add("");
                }
                out.add(disabled ? "§c§lDISABLED" : "§a§lENABLED");
            }
        }
        List<Component> res = new ArrayList<>(out.size());
        for (String l : out) res.add(Component.literal(l));
        return List.copyOf(res);
    }

    /** A core's rewards up to its level: each level lists its own lines (the "include" levels say which earlier ones it repeats). */
    private static void coreRewards(JsonArray levels, int level, List<String> out) {
        int shown = Math.min(Math.max(1, level), levels.size());
        JsonArray row = levels.get(shown - 1).getAsJsonArray();
        for (JsonElement inc : row.get(1).getAsJsonArray()) {
            int k = inc.getAsInt();
            if (k >= 1 && k <= levels.size()) out.addAll(ExtraTables.strings(levels.get(k - 1).getAsJsonArray().get(0)));
        }
        out.addAll(ExtraTables.strings(row.get(0)));
    }

    private static String costName(String kind) {
        return switch (kind) {
            case "FOREST", "DESERT" -> kind.charAt(0) + kind.substring(1).toLowerCase(Locale.ROOT) + " whispers";
            default -> kind.charAt(0) + kind.substring(1).toLowerCase(Locale.ROOT) + " powder";
        };
    }

    private static String costColor(String kind) {
        return switch (kind) {
            case "MITHRIL" -> "§2";
            case "FOREST" -> "§3";
            case "GEMSTONE" -> "§d";
            case "GLACITE" -> "§b";
            case "DESERT" -> "§6";
            default -> "§7";
        };
    }

    private static String commas(long v) {
        return NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    /** The original's number format: grouped, at most three decimals, no trailing zeros. */
    static String number(double v) {
        return NumberFormat.getNumberInstance(Locale.US).format(v);
    }

    /** The data's formulas: numbers, variables, + - * / ^, parentheses and floor/ceil/round/min/max. Unknown names read 0. */
    static double eval(String f, Map<String, Double> vars) {
        return new Object() {
            int i;

            double expr() {
                double v = term();
                while (true) {
                    skip();
                    if (peek('+')) v += term();
                    else if (peek('-')) v -= term();
                    else return v;
                }
            }

            double term() {
                double v = power();
                while (true) {
                    skip();
                    if (peek('*')) v *= power();
                    else if (peek('/')) v /= power();
                    else return v;
                }
            }

            double power() {
                double b = unary();
                skip();
                return peek('^') ? Math.pow(b, power()) : b;
            }

            double unary() {
                skip();
                return peek('-') ? -unary() : atom();
            }

            double atom() {
                skip();
                if (peek('(')) {
                    double v = expr();
                    skip();
                    peek(')');
                    return v;
                }
                int s = i;
                if (i < f.length() && (Character.isDigit(f.charAt(i)) || f.charAt(i) == '.')) {
                    while (i < f.length() && (Character.isDigit(f.charAt(i)) || f.charAt(i) == '.')) i++;
                    return Double.parseDouble(f.substring(s, i));
                }
                while (i < f.length() && (Character.isLetterOrDigit(f.charAt(i)) || f.charAt(i) == '_')) i++;
                String name = f.substring(s, i);
                skip();
                if (!peek('(')) return vars.getOrDefault(name, 0.0);
                List<Double> args = new ArrayList<>();
                do args.add(expr()); while (peekSkip(','));
                skip();
                peek(')');
                return switch (name) {
                    case "floor" -> Math.floor(args.get(0));
                    case "ceil" -> Math.ceil(args.get(0));
                    case "round" -> Math.round(args.get(0));
                    case "min" -> Math.min(args.get(0), args.size() > 1 ? args.get(1) : args.get(0));
                    case "max" -> Math.max(args.get(0), args.size() > 1 ? args.get(1) : args.get(0));
                    default -> args.get(0);
                };
            }

            boolean peekSkip(char c) {
                skip();
                return peek(c);
            }

            boolean peek(char c) {
                if (i < f.length() && f.charAt(i) == c) {
                    i++;
                    return true;
                }
                return false;
            }

            void skip() {
                while (i < f.length() && f.charAt(i) == ' ') i++;
            }
        }.expr();
    }
}
